package com.test.bafangcon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class PresetManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val presetNamesKey = "preset_names"

    fun getPresetNames(): List<String> {
        val names = prefs.getStringSet(presetNamesKey, emptySet()) ?: emptySet()
        return names.sorted()
    }

    fun savePreset(preset: AssistPreset) {
        val json = gson.toJson(preset)
        prefs.edit()
            .putString(presetKey(preset.name), json)
            .apply()

        val names = prefs.getStringSet(presetNamesKey, mutableSetOf()) ?: mutableSetOf()
        val mutableNames = HashSet(names)
        mutableNames.add(preset.name)
        prefs.edit().putStringSet(presetNamesKey, mutableNames).apply()
    }

    fun loadPreset(name: String): AssistPreset? {
        val json = prefs.getString(presetKey(name), null) ?: return null
        return try {
            gson.fromJson(json, AssistPreset::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun deletePreset(name: String) {
        prefs.edit().remove(presetKey(name)).apply()

        val names = prefs.getStringSet(presetNamesKey, mutableSetOf()) ?: mutableSetOf()
        val mutableNames = HashSet(names)
        mutableNames.remove(name)
        prefs.edit().putStringSet(presetNamesKey, mutableNames).apply()
    }

    fun presetExists(name: String): Boolean {
        return getPresetNames().contains(name)
    }

    fun createDefaultPresetsIfNeeded() {
        if (getPresetNames().isNotEmpty()) return

        val preset1 = AssistPreset(
            name = "Preset 1",
            gearSpeedLimit = listOf(34,34,34,34,34,36,36,39,39,39),
            gearCurrentLimit = listOf(17,17,17,35,35,55,55,75,75,100),
            motorStartingAngle = listOf(7,7,7,7,7,7,7,7,7,7),
            accelerationSettings = listOf(6,6,6,6,6,6,6,6,6,6),
            protocolVersion = 0
        )
        val preset2 = AssistPreset(
            name = "Preset 2",
            gearSpeedLimit = listOf(25,25,25,30,30,31,31,20,20,50),
            gearCurrentLimit = listOf(20,20,20,40,40,60,60,80,80,100),
            motorStartingAngle = listOf(7,7,7,7,7,7,7,7,7,7),
            accelerationSettings = listOf(5,5,5,5,5,5,5,5,5,5),
            protocolVersion = 0
        )
        savePreset(preset1)
        savePreset(preset2)
    }

    private fun presetKey(name: String): String = "preset_$name"

    companion object {
        private const val PREFS_NAME = "assist_presets"
    }
}
