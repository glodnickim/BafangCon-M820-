package com.test.bafangcon

import android.content.Context

class DevicePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_ADDRESS, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_ADDRESS, value).apply()
        }

    var lastDeviceName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_NAME, value).apply()
        }

    fun clear() {
        prefs.edit().remove(KEY_LAST_ADDRESS).remove(KEY_LAST_NAME).apply()
    }

    private companion object {
        private const val KEY_LAST_ADDRESS = "last_device_address"
        private const val KEY_LAST_NAME = "last_device_name"
    }
}
