package com.miniichatNext.carter.data.skills

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import com.miniichatNext.carter.debug.DebugLog

private val Context.skillsDataStore: DataStore<Preferences> by preferencesDataStore(name = "skills")

class SkillStore(private val context: Context) {

    private val mutex = Mutex()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsFlow: StateFlow<List<Skill>> = _skills.asStateFlow()

    suspend fun snapshot(): List<Skill> = _skills.value

    suspend fun refresh(): List<Skill> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readFromDisk()
            DebugLog.d(
                "Store", "skills.refresh count=${list.size} names=${list.map { it.name }}"
            )
            _skills.value = list
            // 保持旧版首选项记录同步以兼容旧版本安装时技能仅存储在那里
            context.skillsDataStore.edit { prefs ->
                prefs[LEGACY_KEY] = legacyJson.encodeToString(
                    ListSerializer(Skill.serializer()), list
                )
            }
            list
        }
    }

    suspend fun upsert(skill: Skill) {
        mutex.withLock {
            val cur = _skills.value.toMutableList()
            val idx = cur.indexOfFirst { it.id == skill.id || it.name == skill.name }
            if (idx >= 0) {
                cur[idx] = skill.copy(id = skill.id.ifBlank { cur[idx].id })
            } else {
                cur.add(skill)
            }
            writeToDisk(cur.first { it.id == skill.id || it.name == skill.name })
            _skills.value = cur
        }
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            val list = _skills.value
            val target = list.firstOrNull { it.id == id }
                ?: list.firstOrNull { it.name == id }
            val next = list.filterNot { it.id == target?.id }
            target?.let { removeFromDisk(it) }
            _skills.value = next
        }
    }

    fun deleteDirect(id: String) {
        runBlocking { delete(id) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        DebugLog.d("Store", "skills.setEnabled id=$id enabled=$enabled")
        mutex.withLock {
            val cur = _skills.value
            val idx = cur.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val next = cur.toMutableList()
                next[idx] = next[idx].copy(enabled = enabled)
                _skills.value = next
                writeToDisk(next[idx])
            }
        }
    }

    fun skillsRoot(): File {
        val d = File(context.filesDir, "skills")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun getSkillDir(name: String): File? =
        SkillFrontmatterParser.resolveSkillDir(skillsRoot(), name)

    suspend fun saveSkillFile(name: String, relativePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            saveSkillFiles(name, mapOf(relativePath to bytes))
        }

    suspend fun saveSkillFiles(name: String, files: Map<String, ByteArray>): Boolean =
        withContext(Dispatchers.IO) {
            val skillDir = getSkillDir(name) ?: return@withContext false
            val root = skillDir.canonicalFile
            runCatching {
                files.forEach { (relative, bytes) ->
                    val target = File(root, relative).canonicalFile
                    check(target.path.startsWith(root.path + File.separator))
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
                File(root, "SKILL.md").exists()
            }.getOrDefault(false)
        }

    private fun readFromDisk(): List<Skill> {
        val dir = skillsRoot()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parseDir(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun parseDir(d: File): Skill? {
        val skillFile = File(d, "SKILL.md")
        if (!skillFile.exists()) return null
        val raw = runCatching { skillFile.readText() }.getOrNull() ?: return null
        val parsed = SkillFrontmatterParser.parse(raw, fallbackName = d.name)
            ?: return null
        return Skill(
            id = parsed.name,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            enabled = true,
            createdAt = d.lastModified()
        )
    }

    private fun writeToDisk(skill: Skill) {
        val skillDir = File(skillsRoot(), skill.name)
        if (!skillDir.exists()) skillDir.mkdirs()
        val skillFile = File(skillDir, "SKILL.md")
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: ").append(skill.name).append('\n')
        if (skill.description.isNotBlank()) {
            sb.append("description: ").append(skill.description).append('\n')
        }
        sb.append("---\n\n")
        sb.append(skill.body)
        skillFile.writeText(sb.toString())
    }

    private fun removeFromDisk(skill: Skill) {
        getSkillDir(skill.name)?.deleteRecursively()
    }

    companion object {
        private val LEGACY_KEY = stringPreferencesKey("skills_json")
        private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
