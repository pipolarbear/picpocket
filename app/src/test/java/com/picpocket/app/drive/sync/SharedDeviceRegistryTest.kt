package com.picpocket.app.drive.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedDeviceRegistryTest {

    // Mirrors DeviceRegistry's codec (ignoreUnknownKeys = true).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `decodes the registry`() {
        val decoded = json.decodeFromString<SharedDeviceRegistry>(
            """{"devices":[{"id":"a","name":"A","lastSeen":1}],"encrypted":true}""",
        )

        assertTrue(decoded.encrypted)
        assertEquals(1, decoded.devices.size)
        assertEquals("a", decoded.devices.single().id)
    }

    @Test
    fun `decodes a registry written before extra fields existed`() {
        val decoded = json.decodeFromString<SharedDeviceRegistry>(
            """{"devices":[],"encrypted":false}""",
        )

        assertEquals(0, decoded.devices.size)
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val decoded = json.decodeFromString<SharedDeviceRegistry>(
            """{"devices":[],"encrypted":false,"futureField":123}""",
        )

        assertEquals(0, decoded.devices.size)
    }
}
