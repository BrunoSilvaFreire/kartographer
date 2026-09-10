package io.kartographer.cli

import java.util.prefs.Preferences

internal val UI_METHODS = listOf("auto", "tui", "plain")

internal class KartoPreferences(
    private val store: Preferences = Preferences.userNodeForPackage(KartoPreferences::class.java)
) {
    var ui: String
        get() = store.get("ui", "auto").takeIf { it in UI_METHODS } ?: "auto"
        set(value) {
            require(value in UI_METHODS) { "Unsupported UI method: $value" }
            store.put("ui", value)
            store.flush()
        }
}
