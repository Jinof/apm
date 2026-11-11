package com.jinof.apm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.json.JSONArray

data class ApiProfile(val id: String, val name: String, val config: InferenceConfig)
data class ApiProfiles(val profiles: List<ApiProfile>, val activeProfileId: String) {
    val active: ApiProfile get() = profiles.single { it.id == activeProfileId }
    companion object {
        fun single(config: InferenceConfig = InferenceConfig()) = ApiProfiles(
            listOf(ApiProfile("default", "默认配置", config)), "default",
        )
    }
}

/** Profiles, active selection and credentials remain in one encrypted atomic file. */
class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("inference", Context.MODE_PRIVATE)
    private val file = File(context.noBackupFilesDir, "external-api.enc")

    fun load(): InferenceConfig = loadProfiles().active.config

    fun loadProfiles(): ApiProfiles = synchronized(LOCK) {
        if (file.isFile) return@synchronized runCatching {
            val wrapper = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(wrapper.getString("iv"), Base64.NO_WRAP)))
            val value = JSONObject(String(cipher.doFinal(Base64.decode(wrapper.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
            if (!value.has("profiles")) {
                // Read old encrypted settings without rewriting them on application startup.
                ApiProfiles.single(configFromJson(value))
            } else {
                require(value.getInt("version") == 2)
                val array = value.getJSONArray("profiles")
                val profiles = (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    ApiProfile(entry.getString("id"), entry.getString("name"), configFromJson(entry))
                }
                require(profiles.isNotEmpty() && profiles.all { it.id.isNotBlank() && it.name.isNotBlank() })
                require(profiles.map { it.id }.distinct().size == profiles.size)
                ApiProfiles(profiles, value.getString("activeProfileId")).also { it.active }
            }
        }.getOrElse { ApiProfiles.single() }
        val explicitlySaved = preferences.contains("endpoint") || preferences.contains("model") || preferences.contains("updated_at")
        if (!explicitlySaved) return@synchronized ApiProfiles.single()
        val legacy = ApiProfiles.single(InferenceConfig(
            endpoint = migratedEndpoint(preferences.getString("endpoint", "").orEmpty()),
            modelName = preferences.getString("model", "").orEmpty(),
            allowRemote = preferences.getBoolean("allow_remote", false),
        ))
        runCatching { persist(legacy); preferences.edit().clear().commit(); legacy }.getOrElse { ApiProfiles.single() }
    }

    fun save(candidate: InferenceConfig): InferenceConfig = synchronized(LOCK) {
        val state = loadProfiles()
        saveProfile(state.active.id, state.active.name, candidate).active.config
    }

    fun saveProfile(id: String, name: String, candidate: InferenceConfig): ApiProfiles = synchronized(LOCK) {
        require(id.isNotBlank())
        require(candidate.mergeCount in 1..8) { "合并张数需为 1 至 8" }
        require(candidate.concurrency in 1..8) { "并发度需为 1 至 8" }
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 60) { "配置名称需为 1 至 60 个字符" }
        val normalized = candidate.copy(endpoint = candidate.endpoint.trim().trimEnd('/'), modelName = candidate.modelName.trim(), apiKey = candidate.apiKey.trim())
        val saved = if (normalized.allowRemote) EndpointPolicy.validate(normalized) else normalized
        val state = loadProfiles()
        val profile = ApiProfile(id, label, saved)
        val next = if (state.profiles.any { it.id == id }) state.profiles.map { if (it.id == id) profile else it }
            else state.profiles + profile
        state.copy(profiles = next).also { persist(it); preferences.edit().clear().commit() }
    }

    fun activateProfile(id: String): ApiProfiles = synchronized(LOCK) {
        val state = loadProfiles()
        require(state.profiles.any { it.id == id }) { "配置不存在" }
        if (state.activeProfileId == id) return@synchronized state
        state.copy(activeProfileId = id).also(::persist)
    }

    fun deleteProfile(id: String): ApiProfiles = synchronized(LOCK) {
        val state = loadProfiles()
        require(id != state.activeProfileId) { "请先切换到其他配置，再删除当前配置" }
        require(state.profiles.any { it.id == id }) { "配置不存在" }
        state.copy(profiles = state.profiles.filterNot { it.id == id }).also(::persist)
    }

    private fun configFromJson(value: JSONObject) = InferenceConfig(
        value.getString("endpoint"), value.getString("model"), value.getBoolean("consent"), value.getString("key"),
        (value.opt("concurrency") as? Int)?.takeIf { it in 1..8 } ?: 8,
        (value.opt("mergeCount") as? Int)?.takeIf { it in 1..8 } ?: 1,
    )

    private fun persist(state: ApiProfiles) {
        val entries = state.profiles.map { p -> JSONObject().put("id", p.id).put("name", p.name)
            .put("endpoint", p.config.endpoint).put("model", p.config.modelName)
            .put("consent", p.config.allowRemote).put("key", p.config.apiKey).put("concurrency", p.config.concurrency).put("mergeCount", p.config.mergeCount) }
        val value = JSONObject().put("version", 2).put("activeProfileId", state.activeProfileId)
            .put("profiles", JSONArray(entries)).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val wrapper = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(wrapper.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private val LOCK = Any()
        private const val KEY_ALIAS = "apm-external-api-v1"
        internal fun migratedEndpoint(endpoint: String): String = runCatching {
            val uri = URI(endpoint.trim())
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
            val oldPath = uri.path.orEmpty().trimEnd('/')
            val path = if (oldPath.endsWith("/v1")) oldPath else "$oldPath/v1"
            URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString()
        }.getOrDefault("")
    }
}
