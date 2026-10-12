package com.miniichatNext.carter.data.backup

import android.content.Context
import android.os.Environment
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.skills.SkillStore
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.util.XC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class FullBackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val settings: AppSettings? = null,
    val conversations: List<Conversation> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val assistants: List<Assistant> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val userProfile: UserProfile? = null,
    val mcpJson: String? = null
)

class BackupManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun createBackupData(): FullBackupData = withContext(Dispatchers.IO) {
        val convStore = ConversationStore(context)
        val provStore = ProviderStore(context)
        val asstStore = AssistantStore(context)
        val skillStore = SkillStore(context)
        val settingsRepo = SettingsRepository(context)
        val profileStore = UserProfileStore(context)
        val mcpStore = McpStore(context)

        val profile = runCatching { profileStore.snapshot() }.getOrNull()
        val currentSettings = runCatching { settingsRepo.settings.first() }.getOrNull()
        // 导出真实解密后的提供商列表（URL、已解密的明文 API key、模型列表配置）
        val providerList = provStore.snapshotDecrypted()

        FullBackupData(
            version = 1,
            timestamp = System.currentTimeMillis(),
            settings = currentSettings,
            conversations = convStore.snapshot(),
            providers = providerList,
            assistants = asstStore.snapshot(),
            skills = skillStore.snapshot(),
            userProfile = profile,
            mcpJson = mcpStore.exportFullJson()
        )
    }

    suspend fun exportFullBackup(customKey: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val data = createBackupData()
            val rawJson = json.encodeToString(data)
            val encryptedBytes = XC.e(rawJson.toByteArray(Charsets.UTF_8), customKey)

            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "MiniiChat_Backup_$timeStr.mnbk"

            val targetDir = Environment.getExternalStorageDirectory()
            val outFile = if (targetDir.exists() && targetDir.canWrite()) {
                File(targetDir, fileName)
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, fileName)
            }

            outFile.writeBytes(encryptedBytes)
            outFile.absolutePath
        }.getOrNull()
    }

    suspend fun restoreFullBackup(bytes: ByteArray, customKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val decryptedBytes = XC.d(bytes, customKey)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val backup = json.decodeFromString<FullBackupData>(jsonStr)

            val convStore = ConversationStore(context)
            val provStore = ProviderStore(context)
            val asstStore = AssistantStore(context)
            val settingsRepo = SettingsRepository(context)
            val profileStore = UserProfileStore(context)
            val mcpStore = McpStore(context)

            backup.settings?.let { settingsRepo.restore(it) }
            backup.userProfile?.let { profileStore.save(it) }

            if (backup.conversations.isNotEmpty()) {
                val existing = convStore.snapshot().associateBy { it.id }.toMutableMap()
                backup.conversations.forEach { existing[it.id] = it }
                convStore.save(existing.values.toList())
            }

            if (backup.providers.isNotEmpty()) {
                val existing = provStore.snapshot().associateBy { it.id }.toMutableMap()
                // 覆盖保存，provStore.save 内部会对明文的 API Key 在当前设备上自动执行本机加密存储
                backup.providers.forEach { existing[it.id] = it }
                provStore.save(existing.values.toList())
            }

            if (backup.assistants.isNotEmpty()) {
                val existing = asstStore.snapshot().associateBy { it.id }.toMutableMap()
                backup.assistants.forEach { existing[it.id] = it }
                asstStore.save(existing.values.toList())
            }

            if (!backup.mcpJson.isNullOrBlank()) {
                mcpStore.importFullJson(backup.mcpJson)
            }

            true
        }.getOrDefault(false)
    }

    companion object {
        suspend fun exportData(context: Context, customKey: String? = null): String? {
            return BackupManager(context).exportFullBackup(customKey)
        }

        suspend fun importData(context: Context, bytes: ByteArray, customKey: String? = null): Boolean {
            return BackupManager(context).restoreFullBackup(bytes, customKey)
        }
    }
}
