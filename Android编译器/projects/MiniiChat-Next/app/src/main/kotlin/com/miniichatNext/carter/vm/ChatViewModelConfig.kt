package com.miniichatNext.carter.vm

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 新建会话并立即切换过去。
 *
 * 统一走 [ChatViewModel.newConversationInternal]：
 * 会话的创建与选中必须是一次原子动作，否则会出现
 * 「会话已建好但界面还停在旧会话上」的中间态。
 */
fun ChatViewModel.newConversation() {
    viewModelScope.launch {
        newConversationInternal(select = true)
    }
}

fun ChatViewModel.deleteConversation(id: String) {
    viewModelScope.launch {
        val all = store.snapshot().filterNot { it.id == id }
        store.save(all)
        if (_activeId.value == id) {
            // 删除的是当前会话时，回落到最近更新的一条；
            // 一条都不剩则建一条空会话，避免界面停在无会话的空壳状态。
            val next = all.maxByOrNull { it.updatedAt }
            if (next != null) {
                _activeId.value = next.id
            } else {
                newConversationInternal(select = true)
            }
        }
    }
}

fun ChatViewModel.renameConversation(id: String, newTitle: String) {
    viewModelScope.launch {
        val all = store.snapshot().map {
            if (it.id == id) it.copy(title = newTitle) else it
        }
        store.save(all)
    }
}

fun ChatViewModel.deleteMessage(msgId: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val all = store.snapshot().map { conv ->
            if (conv.id == cid) conv.copy(messages = conv.messages.filterNot { it.id == msgId })
            else conv
        }
        store.save(all)
    }
}

fun ChatViewModel.editMessage(msgId: String, newContent: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val all = store.snapshot().map { conv ->
            if (conv.id == cid) conv.copy(messages = conv.messages.map {
                if (it.id == msgId) it.copy(content = newContent) else it
            })
            else conv
        }
        store.save(all)
    }
}

fun ChatViewModel.fetchModelsForProvider(providerId: String) {
    viewModelScope.launch {
        val p = providerStore.snapshot().find { it.id == providerId } ?: return@launch
        _fetchingModelsFor.value = providerId
        try {
            val models = client.listModels(p)
            val currentMap = _discoveredModels.value.toMutableMap()
            currentMap[providerId] = models
            _discoveredModels.value = currentMap
            _toast.value = "成功获取 ${models.size} 个模型"
        } catch (e: Exception) {
            _error.value = "获取模型列表失败: ${e.message}"
        } finally {
            _fetchingModelsFor.value = null
        }
    }
}

fun ChatViewModel.toggleSkill(skillId: String, enabled: Boolean) {
    val cid = _activeId.value ?: "global"
    val cur = _tempSkillOverrides.value.toMutableMap()
    val convMap = cur[cid]?.toMutableMap() ?: mutableMapOf()
    convMap[skillId] = enabled
    cur[cid] = convMap
    _tempSkillOverrides.value = cur
}

/**
 * 计算某个会话当前生效的技能集合。
 *
 * 优先级：助手自带的 enabledSkillIds 作为基线，会话级临时覆盖在其上增删。
 */
fun ChatViewModel.effectiveSkillIds(conversationId: String?): StateFlow<Set<String>> {
    val cid = conversationId ?: _activeId.value ?: "global"
    return combine(
        assistantStore.assistantsFlow,
        settings,
        _tempSkillOverrides
    ) { assistants, setts, overrides ->
        val activeA = assistants.find { it.id == setts.activeAssistantId }
        val baseSkills = activeA?.enabledSkillIds?.toMutableSet() ?: mutableSetOf()
        val convOverrides = overrides[cid] ?: emptyMap()
        convOverrides.forEach { (sid, en) ->
            if (en) baseSkills.add(sid) else baseSkills.remove(sid)
        }
        baseSkills
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
}
