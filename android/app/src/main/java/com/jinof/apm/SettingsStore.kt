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

/** The API endpoint, consent and credentials stay together in device-bound, no-backup storage. */
class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("inference", Context.MODE_PRIVATE)
    private val file = File(context.noBackupFilesDir, "external-api.enc")

    fun load(): InferenceConfig {
        if (file.isFile) return runCatching {
            val wrapper = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(wrapper.getString("iv"), Base64.NO_WRAP)))
            val value = JSONObject(String(cipher.doFinal(Base64.decode(wrapper.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
            InferenceConfig(value.getString("endpoint"), value.getString("model"), value.getBoolean("consent"), value.getString("key"))
        }.getOrElse { InferenceConfig() }
        val explicitlySaved = preferences.contains("endpoint") || preferences.contains("model") || preferences.contains("updated_at")
        if (!explicitlySaved) return InferenceConfig()
        val legacy = InferenceConfig(
            endpoint = migratedEndpoint(preferences.getString("endpoint", "").orEmpty()),
            modelName = preferences.getString("model", "").orEmpty(),
            allowRemote = preferences.getBoolean("allow_remote", false),
        )
        // Persist the migration before clearing the legacy preferences. It never makes a request.
        return runCatching { persist(legacy); preferences.edit().clear().commit(); legacy }.getOrElse { InferenceConfig() }
    }

    fun save(candidate: InferenceConfig): InferenceConfig {
        val normalized = candidate.copy(endpoint = candidate.endpoint.trim().trimEnd('/'), modelName = candidate.modelName.trim(), apiKey = candidate.apiKey.trim())
        // Revoking consent also works while the endpoint/model are empty or being edited.
        val saved = if (normalized.allowRemote) EndpointPolicy.validate(normalized) else normalized
        persist(saved)
        preferences.edit().clear().commit()
        return saved
    }

    private fun persist(config: InferenceConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = JSONObject().put("endpoint", config.endpoint).put("model", config.modelName)
            .put("consent", config.allowRemote).put("key", config.apiKey).toString()
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
