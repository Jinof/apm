package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPolicyTest {
    @Test
    fun defaultsToLoopbackWithoutRemotePermission() {
        val result = EndpointPolicy.validate(InferenceConfig())

        assertEquals("http://127.0.0.1:11434", result.endpoint)
        assertFalse(result.allowRemote)
    }

    @Test
    fun rejectsPrivateLanEndpointWithoutExplicitPermission() {
        assertThrows(IllegalArgumentException::class.java) {
            EndpointPolicy.validate(
                InferenceConfig(endpoint = "http://192.0.2.2:11434", allowRemote = false),
            )
        }
    }

    @Test
    fun acceptsPrivateLanEndpointWithExplicitPermission() {
        val result = EndpointPolicy.validate(
            InferenceConfig(endpoint = "http://192.0.2.2:11434/", allowRemote = true),
        )

        assertEquals("http://192.0.2.2:11434", result.endpoint)
        assertEquals(true, result.allowRemote)
    }
}
