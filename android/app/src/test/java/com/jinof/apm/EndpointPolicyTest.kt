package com.jinof.apm

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun mergeCountDefaultsToOneAndIsBoundedIndependentlyOfConcurrency() {
        assertEquals(1, InferenceConfig().mergeCount)
        for (count in listOf(0, 9, -1)) assertThrows(IllegalArgumentException::class.java) {
            EndpointPolicy.validate(InferenceConfig("http://localhost/v1", "model", true, mergeCount = count))
        }
        val c = EndpointPolicy.validate(InferenceConfig("http://localhost/v1", "model", true, concurrency = 2, mergeCount = 8))
        assertEquals(2, c.concurrency); assertEquals(8, c.mergeCount)
    }

    @org.junit.Test fun concurrencyDefaultsToEightAndRejectsOutOfRange() {
        org.junit.Assert.assertEquals(8, InferenceConfig().concurrency)
        for (value in listOf(0, 9, -1)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                EndpointPolicy.validate(InferenceConfig("http://localhost/v1", "model", true, concurrency = value))
            }
        }
        for (value in listOf(1, 2, 8)) {
            org.junit.Assert.assertEquals(value, EndpointPolicy.validate(
                InferenceConfig("http://localhost/v1", "model", true, concurrency = value)).concurrency)
        }
    }

    @Test fun defaultIsUnconfiguredAndCannotSendRequests() {
        val config = InferenceConfig()
        assertEquals("", config.endpoint)
        assertEquals("", config.modelName)
        assertFalse(config.allowRemote)
        assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(config) }
    }

    @Test fun everyEndpointRequiresConsentIncludingLoopback() {
        for (endpoint in listOf("http://127.0.0.1:11434/v1", "http://192.0.2.2:8094/v1", "https://api.example.com/v1")) {
            assertThrows(IllegalArgumentException::class.java) {
                EndpointPolicy.validate(InferenceConfig(endpoint, "vision", false))
            }
        }
    }

    @Test fun normalizesBaseUrlAndKeepsConsentAndSecret() {
        val config = EndpointPolicy.validate(InferenceConfig(" https://api.example.com/v1/ ", " vision ", true, "private-key"))
        assertEquals("https://api.example.com/v1", config.endpoint)
        assertEquals("vision", config.modelName)
        assertTrue(config.allowRemote)
        assertEquals("private-key", config.apiKey)
        assertFalse(config.toString().contains("private-key"))
        assertFalse(config.toString().contains("api.example.com"))
    }

    @Test fun rejectsEmbeddedCredentialsFragmentsAndHeaderInjection() {
        for (endpoint in listOf("https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret", "https://example.com:70000/v1", "https://example.com/../v1")) {
            assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.validate(InferenceConfig(endpoint, "vision", true)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointPolicy.validate(InferenceConfig("https://example.com/v1", "vision", true, "key\r\nOther: value"))
        }
    }

    @Test fun migratesOnlyTheLegacyEndpointPathWithoutChangingHost() {
        assertEquals("http://192.0.2.2:11434/v1", SettingsStore.migratedEndpoint("http://192.0.2.2:11434/"))
        assertEquals("https://models.example.com/old/v1", SettingsStore.migratedEndpoint("https://models.example.com/old"))
        assertEquals("https://models.example.com/old/v1", SettingsStore.migratedEndpoint("https://models.example.com/old/v1/"))
        assertEquals("", SettingsStore.migratedEndpoint("https://user:secret@example.com"))
    }
}
