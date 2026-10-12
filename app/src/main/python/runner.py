# -*- coding: utf-8 -*-
import sys
import io
import os
import time
import traceback
import threading
import py_compile

# ---------------------------------------------------------------
# 执行沙箱状态
# ---------------------------------------------------------------
# 每线程独立的命名空间，避免多线程/重入时互相污染。
# 旧实现使用单一全局 dict 作为 exec 命名空间，导致：
#   1) 上一次脚本的变量、导入、被修改的内建名会泄漏到下一次执行
#   2) 并发执行时两个脚本互相覆盖对方变量，产生难以复现的随机崩溃
_TLS = threading.local()

# sys.stdout / sys.stderr 是进程级全局对象。
# 任何重定向都必须串行化，否则两个线程会互相踩踏：
#   A 保存真实 stdout -> A 写 bufferA -> B 保存 bufferA 当成"真实stdout"
#   -> A 还原 stdout -> B 还原成 bufferA（已废弃对象）
#   -> 此后所有输出都进废弃 buffer，控制台输出永久丢失
_IO_LOCK = threading.RLock()

# 全局持久命名空间（仅当显式要求 persist 时使用）
_PERSIST_NS = {
    '__name__': '__main__',
    '__doc__': None,
    '__package__': None,
    '__builtins__': __builtins__,
}

# 单次执行的最大输出字符数，防止 print 海量日志把 JVM 侧桥接层撑爆
_MAX_OUTPUT_CHARS = 512 * 1024


def _new_ns():
    return {
        '__name__': '__main__',
        '__doc__': None,
        '__package__': None,
        '__builtins__': __builtins__,
    }


def _ns(persist=False):
    """取当前线程的命名空间；persist=True 时使用全局持久空间"""
    if persist:
        return _PERSIST_NS
    ns = getattr(_TLS, 'ns', None)
    if ns is None:
        ns = _new_ns()
        _TLS.ns = ns
    return ns


def _reset_ns():
    """清空当前线程命名空间里的用户变量（保留 dunder 与 builtins）"""
    ns = getattr(_TLS, 'ns', None)
    if ns is not None:
        for k in list(ns.keys()):
            if not (k.startswith('__') and k.endswith('__')):
                ns.pop(k, None)
    _TLS.ns = None


def _clip(text):
    if text is None:
        return ''
    if len(text) > _MAX_OUTPUT_CHARS:
        return text[:_MAX_OUTPUT_CHARS] + '\n...(输出过长，已截断)'
    return text


def _run(code, ns, mode='exec'):
    """
    真正的执行体，返回 (是否成功, 输出文本)。

    关键点：
    - 全程持有 _IO_LOCK，stdout/stderr 重定向不会与其它执行交叉
    - finally 里硬还原 stdout/stderr，任何异常路径都不会留下被劫持的流
    - 捕获 BaseException 而不只是 Exception，SystemExit / KeyboardInterrupt
      这类非 Exception 派生异常不会穿透到 JVM 桥接层导致 Chaquopy 崩溃
    """
    buf = io.StringIO()
    ok = False
    with _IO_LOCK:
        old_out, old_err = sys.stdout, sys.stderr
        try:
            sys.stdout = buf
            sys.stderr = buf
            compiled = compile(code, '<minichat>', mode)
            if mode == 'eval':
                val = eval(compiled, ns)
                if val is not None:
                    buf.write(repr(val))
                    buf.write('\n')
            else:
                exec(compiled, ns)
            ok = True
        except SystemExit:
            # 用户脚本调用 sys.exit() 属正常终止，不算错误
            ok = True
        except BaseException:
            traceback.print_exc(file=buf)
            ok = False
        finally:
            # 先尝试 flush，再无条件还原，顺序不能颠倒
            try:
                buf.flush()
            except BaseException:
                pass
            sys.stdout = old_out
            sys.stderr = old_err
    return ok, _clip(buf.getvalue())


_STMT_PREFIXES = (
    'import ', 'from ', 'def ', 'class ', 'if ', 'elif ', 'else',
    'for ', 'while ', 'try', 'except', 'finally', 'with ',
    'return ', 'yield ', 'print(', 'raise ', 'assert ', 'pass',
    'break', 'continue', 'global ', 'nonlocal ', 'del ', 'lambda ',
    '@', '#', 'async ',
)


def _looks_like_expression(line):
    s = line.strip()
    if not s:
        return False
    if s.startswith(_STMT_PREFIXES):
        return False
    if s.endswith(':'):
        return False
    # "a = 1" 这类赋值不是表达式，交给 exec 处理
    # 但 "a == 1" 是表达式，用简单的启发式：排除单等号（非 ==/!=/<=/>=）
    depth = 0
    in_s = None
    i = 0
    while i < len(s):
        c = s[i]
        if in_s:
            if c == '\\':
                i += 2
                continue
            if c == in_s:
                in_s = None
        elif c in ('"', "'"):
            in_s = c
        elif c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == '=' and depth == 0:
            prev = s[i - 1] if i > 0 else ''
            nxt = s[i + 1] if i + 1 < len(s) else ''
            if prev not in ('=', '!', '<', '>') and nxt != '=':
                return False
        i += 1
    return True


def a(file_path):
    """执行指定 python 脚本文件"""
    if not file_path:
        return "文件路径为空"
    if not os.path.exists(file_path):
        return "文件不存在: %s" % file_path
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            source = f.read()
    except BaseException:
        buf = io.StringIO()
        traceback.print_exc(file=buf)
        return _clip(buf.getvalue())

    ns = _ns()
    _ok, out = _run(source, ns, 'exec')
    if out and out.strip():
        return out
    return "(执行成功，无输出)"


def b(user_code):
    """
    执行代码段；末行若为表达式则自动求值并回显 repr。
    每次调用复用当前线程命名空间，可在多轮对话间保留变量。
    """
    if not user_code or not user_code.strip():
        return "(空代码)"

    clean = user_code.replace('\r\n', '\n').replace('\r', '\n')
    lines = clean.split('\n')

    # 去掉尾部纯空行，用于判断末行是否为表达式
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return "(空代码)"

    ns = _ns()
    parts = []

    if len(lines) > 1 and _looks_like_expression(lines[-1]):
        head = '\n'.join(lines[:-1])
        last = lines[-1].strip()

        ok_h, out_h = _run(head, ns, 'exec')
        if out_h and out_h.strip():
            parts.append(out_h)

        if ok_h:
            ok_l, out_l = _run(last, ns, 'eval')
            if out_l and out_l.strip():
                parts.append(out_l)
            if not ok_l:
                # eval 失败说明末行其实是语句（如复杂赋值 / 解包），退化执行
                ok_e, out_e = _run(last, ns, 'exec')
                if out_e and out_e.strip():
                    parts.append(out_e)
    else:
        _ok, out = _run(clean, ns, 'exec')
        if out and out.strip():
            parts.append(out)

    text = ''.join(parts)
    return text if text.strip() else "(执行成功，未产生控制台输出)"


def f(user_code, timeout_seconds=20):
    """
    带超时的执行入口（供 Kotlin 侧调用）。

    注意：Python 没有安全强杀线程的手段，因此超时后工作线程仍在后台运行。
    但本函数会立即返回，让 JVM 侧不必无限等待 —— 这是修复
    "工具调用一直加载没有结果" 的关键：旧实现里 callAttr 是阻塞的，
    脚本一旦死循环，Java 侧就永久卡死在该调用上。
    """
    try:
        timeout_seconds = int(timeout_seconds)
    except BaseException:
        timeout_seconds = 20
    if timeout_seconds <= 0:
        timeout_seconds = 20
    if timeout_seconds > 300:
        timeout_seconds = 300

    box = {}

    def _worker():
        try:
            box['out'] = b(user_code)
        except BaseException:
            buf = io.StringIO()
            traceback.print_exc(file=buf)
            box['out'] = _clip(buf.getvalue())

    th = threading.Thread(target=_worker, name='minichat-py-exec')
    th.daemon = True
    th.start()
    th.join(timeout_seconds)

    if th.is_alive():
        return ("Python 执行超时（超过 %d 秒），已放弃等待。\n"
                "代码可能仍在后台运行，请检查是否存在无限循环、"
                "阻塞式网络请求或死锁。" % timeout_seconds)

    return box.get('out', '(无输出)')


def g(persist=False):
    """重置当前线程的命名空间"""
    _reset_ns()
    return "已重置 Python 执行命名空间"


def c(src_file, out_dir):
    """编译脚本为 .pyc"""
    try:
        if not os.path.exists(out_dir):
            os.makedirs(out_dir, exist_ok=True)
        dest = os.path.join(out_dir, os.path.basename(src_file) + 'c')
        py_compile.compile(src_file, cfile=dest, doraise=True)
        return "编译成功: %s" % dest
    except BaseException:
        buf = io.StringIO()
        traceback.print_exc(file=buf)
        return _clip(buf.getvalue())


def d():
    """环境信息"""
    try:
        loaded = len(sys.modules)
    except BaseException:
        loaded = -1
    try:
        threads = threading.active_count()
    except BaseException:
        threads = -1
    return "Python %s\n已加载模块数: %d\n活跃线程数: %d" % (
        sys.version, loaded, threads)


def e(src):
    """换行符归一化"""
    if not src:
        return ""
    return src.replace('\r\n', '\n')
