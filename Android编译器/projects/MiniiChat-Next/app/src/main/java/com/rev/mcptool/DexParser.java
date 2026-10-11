package com.rev.mcptool;

import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.MultiDexIO;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.iface.value.EncodedValue;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 模块一：DEX 解析模块
 * 依托本地 app/src/main/java/lanchon/multidexlib2 源码实现
 */
public final class DexParser {

    private static final int A = 34; // 默认 Android API
    private static final String B = "L";
    private static final String C = ";";

    private final File d;
    private final int e;
    private final Opcodes f;

    public DexParser(File file) {
        this(file, A);
    }

    public DexParser(File file, int apiLevel) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("DEX/APK 文件不存在: " + file);
        }
        this.d = file;
        this.e = apiLevel > 0 ? apiLevel : A;
        this.f = Opcodes.forApi(this.e);
    }

    // ==================== 结构化结果模型 (POJO) ====================

    public static final class ClassItem {
        public String descriptor;
        public String dotName;
        public String dexEntry;

        public ClassItem(String descriptor, String dotName, String dexEntry) {
            this.descriptor = descriptor;
            this.dotName = dotName;
            this.dexEntry = dexEntry;
        }
    }

    public static final class ClassListResult {
        public boolean success;
        public String error;
        public int total;
        public List<ClassItem> classes;

        public ClassListResult(boolean success, String error, List<ClassItem> classes) {
            this.success = success;
            this.error = error;
            this.classes = classes != null ? classes : Collections.emptyList();
            this.total = this.classes.size();
        }
    }

    public static final class ClassSmaliResult {
        public boolean success;
        public String error;
        public String descriptor;
        public String dotName;
        public String dexEntry;
        public String smali;

        public ClassSmaliResult(boolean success, String error, String descriptor, String dotName, String dexEntry, String smali) {
            this.success = success;
            this.error = error;
            this.descriptor = descriptor;
            this.dotName = dotName;
            this.dexEntry = dexEntry;
            this.smali = smali;
        }
    }

    public static final class MethodSmaliResult {
        public boolean success;
        public String error;
        public String classDescriptor;
        public String methodName;
        public String methodSignature;
        public String returnType;
        public List<String> parameterTypes;
        public int accessFlags;
        public String smali;

        public MethodSmaliResult(boolean success, String error, String classDescriptor, String methodName,
                                 String methodSignature, String returnType, List<String> parameterTypes,
                                 int accessFlags, String smali) {
            this.success = success;
            this.error = error;
            this.classDescriptor = classDescriptor;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes != null ? parameterTypes : Collections.emptyList();
            this.accessFlags = accessFlags;
            this.smali = smali;
        }
    }

    // ==================== 1. 列出 DEX/APK 中所有类 ====================

    public ClassListResult a() {
        List<ClassItem> list = new ArrayList<>();
        try {
            MultiDexContainer<DexBackedDexFile> container = MultiDexIO.readMultiDexContainer(
                    this.d,
                    new BasicDexFileNamer(),
                    this.f
            );
            List<String> entryNames = container.getDexEntryNames();
            for (String entry : entryNames) {
                MultiDexContainer.DexEntry<DexBackedDexFile> dexEntry = container.getEntry(entry);
                if (dexEntry != null) {
                    DexFile dexFile = dexEntry.getDexFile();
                    for (ClassDef def : dexFile.getClasses()) {
                        String desc = def.getType();
                        list.add(new ClassItem(desc, b(desc), entry));
                    }
                }
            }
            list.sort(Comparator.comparing(o -> o.dotName));
            return new ClassListResult(true, null, list);
        } catch (Throwable t) {
            return new ClassListResult(false, t.getMessage(), null);
        }
    }

    // ==================== 2. 读取指定类完整 Smali ====================

    public ClassSmaliResult a(String targetClassName) {
        if (targetClassName == null || targetClassName.trim().isEmpty()) {
            return new ClassSmaliResult(false, "Target class name cannot be empty", null, null, null, null);
        }
        String desc = c(targetClassName.trim());
        String dot = b(desc);

        try {
            MultiDexContainer<DexBackedDexFile> container = MultiDexIO.readMultiDexContainer(
                    this.d,
                    new BasicDexFileNamer(),
                    this.f
            );
            for (String entry : container.getDexEntryNames()) {
                MultiDexContainer.DexEntry<DexBackedDexFile> dexEntry = container.getEntry(entry);
                if (dexEntry == null) continue;
                DexFile dexFile = dexEntry.getDexFile();
                for (ClassDef def : dexFile.getClasses()) {
                    if (def.getType().equals(desc)) {
                        String smali = a(def);
                        return new ClassSmaliResult(true, null, desc, dot, entry, smali);
                    }
                }
            }
            return new ClassSmaliResult(false, "Class not found: " + targetClassName, desc, dot, null, null);
        } catch (Throwable t) {
            return new ClassSmaliResult(false, t.getMessage(), desc, dot, null, null);
        }
    }

    // ==================== 3. 读取指定方法完整 Smali ====================

    public MethodSmaliResult a(String targetClassName, String targetMethodName) {
        if (targetClassName == null || targetMethodName == null) {
            return new MethodSmaliResult(false, "Class name or method name cannot be empty", null, null, null, null, null, 0, null);
        }
        String desc = c(targetClassName.trim());
        String targetM = targetMethodName.trim();

        try {
            MultiDexContainer<DexBackedDexFile> container = MultiDexIO.readMultiDexContainer(
                    this.d,
                    new BasicDexFileNamer(),
                    this.f
            );
            for (String entry : container.getDexEntryNames()) {
                MultiDexContainer.DexEntry<DexBackedDexFile> dexEntry = container.getEntry(entry);
                if (dexEntry == null) continue;
                DexFile dexFile = dexEntry.getDexFile();
                for (ClassDef def : dexFile.getClasses()) {
                    if (def.getType().equals(desc)) {
                        for (Method method : def.getMethods()) {
                            String mName = method.getName();
                            String fullSig = g(method);
                            if (mName.equals(targetM) || fullSig.equals(targetM)) {
                                String methodSmali = a(method);
                                List<String> paramTypes = new ArrayList<>();
                                for (CharSequence cs : method.getParameterTypes()) {
                                    paramTypes.add(cs.toString());
                                }
                                return new MethodSmaliResult(
                                        true, null, desc, mName, fullSig,
                                        method.getReturnType(), paramTypes,
                                        method.getAccessFlags(), methodSmali
                                );
                            }
                        }
                        return new MethodSmaliResult(false, "Method not found in class: " + targetMethodName, desc, targetMethodName, null, null, null, 0, null);
                    }
                }
            }
            return new MethodSmaliResult(false, "Class not found: " + targetClassName, desc, targetMethodName, null, null, null, 0, null);
        } catch (Throwable t) {
            return new MethodSmaliResult(false, t.getMessage(), desc, targetMethodName, null, null, null, 0, null);
        }
    }

    // ==================== Smali 生成逻辑 ====================

    private String a(ClassDef def) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(".class ").append(d(def.getAccessFlags())).append(" ").append(def.getType()).append("\n");
        if (def.getSuperclass() != null) {
            sb.append(".super ").append(def.getSuperclass()).append("\n");
        }
        if (def.getSourceFile() != null) {
            sb.append(".source \"").append(def.getSourceFile()).append("\"\n");
        }
        for (String iface : def.getInterfaces()) {
            sb.append(".implements ").append(iface).append("\n");
        }

        // static fields
        Iterable<? extends Field> sFields = def.getStaticFields();
        if (sFields != null && sFields.iterator().hasNext()) {
            sb.append("\n# static fields\n");
            for (Field f : sFields) {
                sb.append(".field ").append(e(f.getAccessFlags())).append(" ")
                  .append(f.getName()).append(":").append(f.getType());
                EncodedValue initVal = f.getInitialValue();
                if (initVal != null) {
                    sb.append(" = ").append(initVal.toString());
                }
                sb.append("\n");
            }
        }

        // instance fields
        Iterable<? extends Field> iFields = def.getInstanceFields();
        if (iFields != null && iFields.iterator().hasNext()) {
            sb.append("\n# instance fields\n");
            for (Field f : iFields) {
                sb.append(".field ").append(e(f.getAccessFlags())).append(" ")
                  .append(f.getName()).append(":").append(f.getType()).append("\n");
            }
        }

        // direct methods
        Iterable<? extends Method> dMethods = def.getDirectMethods();
        if (dMethods != null && dMethods.iterator().hasNext()) {
            sb.append("\n# direct methods\n");
            for (Method m : dMethods) {
                sb.append(a(m)).append("\n");
            }
        }

        // virtual methods
        Iterable<? extends Method> vMethods = def.getVirtualMethods();
        if (vMethods != null && vMethods.iterator().hasNext()) {
            sb.append("\n# virtual methods\n");
            for (Method m : vMethods) {
                sb.append(a(m)).append("\n");
            }
        }
        return sb.toString();
    }

    private String a(Method m) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(".method ").append(f(m.getAccessFlags())).append(" ")
          .append(m.getName()).append("(");
        for (CharSequence p : m.getParameterTypes()) {
            sb.append(p);
        }
        sb.append(")").append(m.getReturnType()).append("\n");

        MethodImplementation impl = m.getImplementation();
        if (impl != null) {
            sb.append("    .registers ").append(impl.getRegisterCount()).append("\n");
            for (Instruction ins : impl.getInstructions()) {
                sb.append("    ").append(ins.getOpcode().name);
                if (ins instanceof ReferenceInstruction) {
                    Reference ref = ((ReferenceInstruction) ins).getReference();
                    if (ref instanceof MethodReference) {
                        MethodReference mr = (MethodReference) ref;
                        sb.append(" ").append(mr.getDefiningClass()).append("->")
                          .append(mr.getName()).append("(");
                        for (CharSequence cp : mr.getParameterTypes()) {
                            sb.append(cp);
                        }
                        sb.append(")").append(mr.getReturnType());
                    } else if (ref instanceof FieldReference) {
                        FieldReference fr = (FieldReference) ref;
                        sb.append(" ").append(fr.getDefiningClass()).append("->")
                          .append(fr.getName()).append(":").append(fr.getType());
                    } else if (ref instanceof StringReference) {
                        sb.append(" \"").append(((StringReference) ref).getString()).append("\"");
                    } else if (ref instanceof TypeReference) {
                        sb.append(" ").append(((TypeReference) ref).getType());
                    }
                }
                sb.append("\n");
            }
        }
        sb.append(".end method\n");
        return sb.toString();
    }

    private static String g(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        for (CharSequence param : method.getParameterTypes()) {
            sb.append(param);
        }
        sb.append(")").append(method.getReturnType());
        return sb.toString();
    }

    private static String d(int flags) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isPublic(flags)) sb.append("public ");
        if (Modifier.isPrivate(flags)) sb.append("private ");
        if (Modifier.isProtected(flags)) sb.append("protected ");
        if (Modifier.isAbstract(flags)) sb.append("abstract ");
        if (Modifier.isStatic(flags)) sb.append("static ");
        if (Modifier.isFinal(flags)) sb.append("final ");
        if (Modifier.isInterface(flags)) sb.append("interface ");
        return sb.toString().trim();
    }

    private static String e(int flags) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isPublic(flags)) sb.append("public ");
        if (Modifier.isPrivate(flags)) sb.append("private ");
        if (Modifier.isProtected(flags)) sb.append("protected ");
        if (Modifier.isStatic(flags)) sb.append("static ");
        if (Modifier.isFinal(flags)) sb.append("final ");
        if (Modifier.isVolatile(flags)) sb.append("volatile ");
        if (Modifier.isTransient(flags)) sb.append("transient ");
        return sb.toString().trim();
    }

    private static String f(int flags) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isPublic(flags)) sb.append("public ");
        if (Modifier.isPrivate(flags)) sb.append("private ");
        if (Modifier.isProtected(flags)) sb.append("protected ");
        if (Modifier.isStatic(flags)) sb.append("static ");
        if (Modifier.isFinal(flags)) sb.append("final ");
        if (Modifier.isSynchronized(flags)) sb.append("synchronized ");
        if (Modifier.isNative(flags)) sb.append("native ");
        if (Modifier.isAbstract(flags)) sb.append("abstract ");
        return sb.toString().trim();
    }

    private static String b(String desc) {
        if (desc == null) return "";
        if (desc.startsWith(B) && desc.endsWith(C)) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    private static String c(String name) {
        if (name == null) return "";
        if (name.startsWith(B) && name.endsWith(C)) {
            return name;
        }
        return B + name.replace('.', '/') + C;
    }
}
