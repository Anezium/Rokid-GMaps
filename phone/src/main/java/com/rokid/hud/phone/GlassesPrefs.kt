package com.rokid.hud.phone

import android.content.Context

object GlassesPrefs {
    const val PREFS_NAME = "rokid_glasses"

    private const val KEY_GLASSES_MAC = "glasses_mac"
    private const val KEY_GLASSES_NAME = "glasses_name"
    private const val LEGACY_KEY_GLASSES_ADDRESS = "glasses_address"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSelection(context: Context, address: String, name: String?) {
        prefs(context).edit()
            .putString(KEY_GLASSES_MAC, address)
            .putString(LEGACY_KEY_GLASSES_ADDRESS, address)
            .putString(KEY_GLASSES_NAME, name ?: "Rokid Glasses")
            .apply()
    }

    fun getAddress(context: Context): String? =
        prefs(context).getString(KEY_GLASSES_MAC, null)
            ?: prefs(context).getString(LEGACY_KEY_GLASSES_ADDRESS, null)

    fun getName(context: Context): String? =
        prefs(context).getString(KEY_GLASSES_NAME, null)
}
