package com.speaktosurvive.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_ASK_CANCEL = "com.speaktosurvive.app.ASK_CANCEL"
        const val ACTION_ASK_STOP = "com.speaktosurvive.app.ASK_STOP"
    }

    private var wantStart = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        AppState.resumeTick++
        if (wantStart) {
            wantStart = false
            if (Perms.allGranted(this)) {
                launchGuard(GuardService.ACTION_START)
            } else {
                Toast.makeText(
                    this,
                    "All permissions are needed. You can allow them in Settings.",
                    Toast.LENGTH_LONG
                ).show()
                AppState.tab = 2
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        addOnNewIntentListener { i -> handleIntent(i) }
        setContent {
            SosTheme {
                AppRoot(
                    onToggle = { toggle() },
                    onTest = { runWhenReady(GuardService.ACTION_TEST) },
                    onSosNow = { runWhenReady(GuardService.ACTION_SOS_NOW) },
                    onCancelSos = { launchGuard(GuardService.ACTION_CANCEL_SOS) },
                    onRequestPerm = { p -> requestPerm(p) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.resumeTick++
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks Settings and Contacts again (when a password is set).
        AppState.unlocked = false
    }

    private fun handleIntent(i: Intent?) {
        val action = i?.action
        if (action == ACTION_ASK_CANCEL) {
            if (AppState.sosActive) AppState.prompt = "cancel"
        } else if (action == ACTION_ASK_STOP) {
            if (AppState.guardRunning) AppState.prompt = "stop"
        }
    }

    private fun requestPerm(p: String) {
        if (Build.VERSION.SDK_INT >= 31 && p == Manifest.permission.BLUETOOTH_SCAN) {
            permLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
            )
        } else {
            permLauncher.launch(arrayOf(p))
        }
    }

    private fun toggle() {
        if (AppState.guardRunning) {
            launchGuard(GuardService.ACTION_STOP)
        } else {
            startFlow(GuardService.ACTION_START)
        }
    }

    private fun runWhenReady(action: String) {
        if (!AppState.guardRunning) {
            Toast.makeText(this, "Switch protection on first", Toast.LENGTH_SHORT).show()
            return
        }
        launchGuard(action)
    }

    private fun startFlow(action: String) {
        if (Prefs.contacts(this).isEmpty()) {
            Toast.makeText(this, "Add at least one emergency contact first", Toast.LENGTH_LONG).show()
            AppState.tab = 1
            return
        }
        val missing = Perms.required().filter { !Perms.granted(this, it) }
        if (missing.isNotEmpty()) {
            wantStart = true
            permLauncher.launch(missing.toTypedArray())
            return
        }
        launchGuard(action)
    }

    private fun launchGuard(action: String) {
        val i = Intent(this, GuardService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
