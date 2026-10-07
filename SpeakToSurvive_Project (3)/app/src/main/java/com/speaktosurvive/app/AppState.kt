package com.speaktosurvive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Live state shared between the background service and the screen. */
object AppState {
    var guardRunning by mutableStateOf(false)
    var voiceStatus by mutableStateOf("Off")
    var sosActive by mutableStateOf(false)
    var statusLine by mutableStateOf("Protection is off")
    var lastEvent by mutableStateOf("")
    var resumeTick by mutableIntStateOf(0)
    var tab by mutableIntStateOf(0)

    // password prompt: "", "cancel", "stop" or "unlock"
    var prompt by mutableStateOf("")
    var unlocked by mutableStateOf(false)
    var pwFails by mutableIntStateOf(0)
    var pwLockUntil by mutableStateOf(0L)

    // Bluetooth relay
    var meshStatus by mutableStateOf("Off")
    var meshEvent by mutableStateOf("")
}

object Perms {
    fun granted(c: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun required(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(Manifest.permission.SEND_SMS)
        list.add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return list.toTypedArray()
    }

    fun allGranted(c: Context): Boolean = required().all { granted(c, it) }
}
