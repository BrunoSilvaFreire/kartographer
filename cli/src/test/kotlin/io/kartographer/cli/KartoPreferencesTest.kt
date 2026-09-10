package io.kartographer.cli

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KartoPreferencesTest {
    @Test
    fun `ui preference persists and validates its value`() {
        val store = Preferences.userRoot().node("io/kartographer/test/${UUID.randomUUID()}")
        try {
            val preferences = KartoPreferences(store)
            assertEquals("auto", preferences.ui)

            preferences.ui = "plain"
            assertEquals("plain", KartoPreferences(store).ui)
            assertThrows(IllegalArgumentException::class.java) { preferences.ui = "other" }
        } finally {
            store.removeNode()
        }
    }
}
