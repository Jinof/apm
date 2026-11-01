package com.jinof.apm

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsStoreTest {
    @Test
    fun noSavedSettingsRemainEmptyWithoutCreatingDefaults() = withIsolatedStore { context, store ->
        repeat(2) {
            val config = store.load()
            assertEquals("", config.endpoint)
            assertEquals("", config.modelName)
            assertEquals("", config.apiKey)
            assertFalse(config.allowRemote)
        }
        assertFalse(File(context.noBackupFilesDir, "external-api.enc").exists())
        assertTrue(context.legacyPreferences.all.isEmpty())
    }

    @Test
    fun savedApiConfigurationIsEncryptedAndReloadsExactly() = withIsolatedStore { context, store ->
        val config = InferenceConfig(
            endpoint = "https://api.example.test/gateway/v1",
            modelName = "external-vision-model",
            allowRemote = true,
            apiKey = "test-only-secret-key-1234567890",
        )
        assertEquals(config, store.save(config))
        val encryptedFile = File(context.noBackupFilesDir, "external-api.enc")
        assertTrue(encryptedFile.isFile)
        val contents = encryptedFile.readText()
        assertFalse(contents.contains(config.apiKey))
        assertFalse(contents.contains(config.endpoint))
        assertFalse(contents.contains(config.modelName))
        assertEquals(config, SettingsStore(context).load())
        assertTrue(context.legacyPreferences.all.isEmpty())
    }

    @Test
    fun explicitLegacySettingsMigrateOncePreservingBasePathAndConsent() {
        val endpoints = listOf(
            "http://192.168.1.20:11434" to "http://192.168.1.20:11434/v1",
            "https://api.example.test/gateway" to "https://api.example.test/gateway/v1",
            "https://api.example.test/gateway/v1/" to "https://api.example.test/gateway/v1",
        )
        for ((oldEndpoint, expectedEndpoint) in endpoints) {
            withIsolatedStore { context, store ->
                assertTrue(context.legacyPreferences.edit()
                    .putString("endpoint", oldEndpoint)
                    .putString("model", "explicit-old-model")
                    .putBoolean("allow_remote", true)
                    .putString("updated_at", "2026-09-01T00:00:00Z")
                    .commit())
                val migrated = store.load()
                assertEquals(expectedEndpoint, migrated.endpoint)
                assertEquals("explicit-old-model", migrated.modelName)
                assertEquals("", migrated.apiKey)
                assertTrue(migrated.allowRemote)
                assertTrue(File(context.noBackupFilesDir, "external-api.enc").isFile)
                assertTrue(context.legacyPreferences.all.isEmpty())
                assertEquals(migrated, SettingsStore(context).load())
            }
        }
    }

    @Test
    fun migrationPreservesRevokedConsentAndSaveRevokesImmediately() = withIsolatedStore { context, store ->
        assertTrue(context.legacyPreferences.edit()
            .putString("endpoint", "https://api.example.test/v1")
            .putString("model", "external-model")
            .putBoolean("allow_remote", false)
            .commit())
        val migrated = store.load()
        assertFalse(migrated.allowRemote)
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(migrated) }
        val allowed = store.save(migrated.copy(allowRemote = true, apiKey = "test-key"))
        assertEquals(allowed, EndpointPolicy.validate(store.load()))
        val revoked = store.save(allowed.copy(allowRemote = false))
        assertFalse(revoked.allowRemote)
        assertEquals(revoked, SettingsStore(context).load())
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(store.load()) }

        // Consent can still be revoked while a configuration form contains incomplete input.
        val incomplete = store.save(allowed.copy(endpoint = "", modelName = "", allowRemote = false))
        assertFalse(incomplete.allowRemote)
        assertEquals(incomplete, store.load())
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(store.load()) }
    }

    @Test
    fun profilesKeepIndependentSecretsAndConsentAcrossSwitchSaveAndDelete() = withIsolatedStore { context, store ->
        val home = InferenceConfig("http://192.168.2.10:3000/v1", "home-model", true, "home-secret")
        val away = InferenceConfig("https://device.example.ts.net/v1", "away-model", false, "away-secret")
        store.save(home)
        store.saveProfile("away", "外出", away)
        assertEquals(home, store.load())
        val inFlight = store.load()
        store.activateProfile("away")
        assertEquals(away, SettingsStore(context).load())
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(store.load()) }
        val editedAway = store.save(away.copy(modelName = "edited-away"))
        assertEquals(2, store.loadProfiles().profiles.size)
        assertEquals(home, store.loadProfiles().profiles.single { it.id == "default" }.config)
        assertEquals(editedAway, store.loadProfiles().active.config)
        assertEquals(home, inFlight)
        assertThrows(IllegalArgumentException::class.java) { store.deleteProfile("away") }
        store.activateProfile("default")
        assertEquals(home, store.load())
        val encrypted = File(context.noBackupFilesDir, "external-api.enc").readText()
        for (value in listOf(home.endpoint, home.apiKey, home.modelName, away.endpoint, away.apiKey, "edited-away")) {
            assertFalse(encrypted.contains(value))
        }
        store.deleteProfile("away")
        assertEquals(ApiProfiles.single(home), SettingsStore(context).loadProfiles())
        assertThrows(IllegalArgumentException::class.java) { store.activateProfile("missing") }
    }

    @Test
    fun oldEncryptedConfigurationRemainsByteIdenticalUntilExplicitEdit() = withIsolatedStore { context, store ->
        val old = InferenceConfig("https://old.example.test/proxy/v1", "old-model", true, "old-secret")
        store.save(old) // Ensure the same installed Keystore key exists for a v1 fixture.
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, keyStore.getKey("apm-external-api-v1", null))
        }
        val plain = org.json.JSONObject().put("endpoint", old.endpoint).put("model", old.modelName)
            .put("key", old.apiKey).put("consent", old.allowRemote).toString()
        val wrapper = org.json.JSONObject().put("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .put("data", android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray()), android.util.Base64.NO_WRAP))
        val file = File(context.noBackupFilesDir, "external-api.enc")
        file.writeText(wrapper.toString())
        val originalBytes = file.readBytes()
        repeat(2) {
            assertEquals(ApiProfiles.single(old), SettingsStore(context).loadProfiles())
            assertTrue(originalBytes.contentEquals(file.readBytes()))
        }
        store.saveProfile("away", "外出", InferenceConfig())
        assertEquals(old, store.load())
        assertEquals(InferenceConfig(), store.loadProfiles().profiles.single { it.id == "away" }.config)
        assertFalse(originalBytes.contentEquals(file.readBytes()))
        assertEquals(2, SettingsStore(context).loadProfiles().profiles.size)
    }

    private fun withIsolatedStore(test: (IsolatedSettingsContext, SettingsStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "settings-store-test-$id")
        check(directory.mkdir())
        val context = IsolatedSettingsContext(base, directory, "settings-store-test-$id")
        try {
            test(context, SettingsStore(context))
        } finally {
            // Only this test's storage is removed; the shared Android Keystore alias stays intact.
            base.deleteSharedPreferences(context.preferenceName)
            directory.deleteRecursively()
        }
    }

    private class IsolatedSettingsContext(
        base: Context,
        private val directory: File,
        val preferenceName: String,
    ) : ContextWrapper(base) {
        val legacyPreferences: SharedPreferences get() = getSharedPreferences("inference", MODE_PRIVATE)
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(if (name == "inference") preferenceName else name, mode)
    }
}
