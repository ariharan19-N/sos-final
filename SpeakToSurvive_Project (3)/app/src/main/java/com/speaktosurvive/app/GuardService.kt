package com.speaktosurvive.app

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Foreground service that keeps watching for the two hands-free triggers:
 *  - the secret voice code (offline speech recognition, nothing leaves the phone)
 *  - repeated power-button presses (each press switches the screen on or off)
 */
class GuardService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.speaktosurvive.app.START"
        const val ACTION_STOP = "com.speaktosurvive.app.STOP"
        const val ACTION_CANCEL_SOS = "com.speaktosurvive.app.CANCEL_SOS"
        const val ACTION_TEST = "com.speaktosurvive.app.TEST"
        const val ACTION_SOS_NOW = "com.speaktosurvive.app.SOS_NOW"
        private const val PRESS_WINDOW_MS = 6_000L
        private const val VOICE_COOLDOWN_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sos: SosManager
    private lateinit var mesh: MeshManager

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val pressTimes = ArrayDeque<Long>()
    private var keyword = ""
    private var lastVoiceTrigger = 0L
    private var running = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mesh = MeshManager(applicationContext) { refreshNotification() }
        sos = SosManager(applicationContext, mesh) { refreshNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGuard()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_SOS -> {
                sos.cancel()
                refreshNotification()
            }
            ACTION_TEST -> {
                if (ensureStarted()) sos.trigger("Test", test = true)
            }
            ACTION_SOS_NOW -> {
                if (ensureStarted()) sos.trigger("Manual SOS button")
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ start / stop

    private fun ensureStarted(): Boolean {
        if (running) return true
        if (!Perms.granted(this, Manifest.permission.RECORD_AUDIO)) {
            AppState.statusLine = "Microphone permission is missing"
            stopSelf()
            return false
        }
        Notif.ensureChannels(this)
        val note = Notif.build(this, "Starting...", false)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Perms.granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(Notif.ID_GUARD, note, type)
            } else {
                startForeground(Notif.ID_GUARD, note)
            }
        } catch (e: Exception) {
            AppState.statusLine = "Could not start protection: ${e.message}"
            stopSelf()
            return false
        }
        running = true
        AppState.guardRunning = true
        AppState.statusLine = "Protection is on"
        acquireWakeLock()
        registerScreenReceiver()
        startVoice()
        if (Prefs.helpOthers(this)) mesh.startScanning()
        refreshNotification()
        return true
    }

    private fun stopGuard() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        if (destroyed) return
        destroyed = true
        running = false
        main.removeCallbacksAndMessages(null)
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // already gone
        }
        screenReceiver = null
        closeVoice()
        sos.destroy()
        mesh.destroy()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        AppState.guardRunning = false
        AppState.voiceStatus = "Off"
        AppState.sosActive = false
        AppState.statusLine = "Protection is off"
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeakToSurvive:guard")
        wl.setReferenceCounted(false)
        wl.acquire()
        wakeLock = wl
    }

    // ------------------------------------------------------------------ voice trigger

    private fun startVoice() {
        if (!Prefs.voiceEnabled(this)) {
            AppState.voiceStatus = "Off"
            return
        }
        val phrase = Prefs.codeWord(this).trim().lowercase()
        if (Prefs.codeError(phrase) != null) {
            AppState.voiceStatus = "Error"
            AppState.lastEvent = "Code word is not valid. Fix it in Settings."
            return
        }
        keyword = phrase
        AppState.voiceStatus = "Loading"
        StorageService.unpack(
            this, "model", "model",
            { m: Model ->
                if (destroyed) {
                    try {
                        m.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                } else {
                    model = m
                    beginListening(m)
                }
            },
            { e: IOException ->
                AppState.voiceStatus = "Error"
                AppState.lastEvent = "Voice model problem: ${e.message}"
                refreshNotification()
            }
        )
    }

    private fun beginListening(m: Model) {
        try {
            val grammar = "[\"$keyword\", \"[unk]\"]"
            val rec = Recognizer(m, 16000.0f, grammar)
            val ss = SpeechService(rec, 16000.0f)
            ss.startListening(this)
            recognizer = rec
            speech = ss
            AppState.voiceStatus = "Listening"
        } catch (e: Exception) {
            AppState.voiceStatus = "Error"
            AppState.lastEvent = "Microphone problem: ${e.message}"
        }
        refreshNotification()
    }

    private fun closeVoice() {
        try {
            speech?.cancel()
            speech?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        speech = null
        try {
            recognizer?.close()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
    }

    private fun restartVoice() {
        if (destroyed || !running) return
        closeVoice()
        val m = model
        if (m != null) beginListening(m)
    }

    private fun heard(json: String?, field: String) {
        if (json == null || keyword.isEmpty()) return
        val text = try {
            JSONObject(json).optString(field, "")
        } catch (e: Exception) {
            ""
        }
        if (text.contains(keyword)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVoiceTrigger > VOICE_COOLDOWN_MS && !sos.isActive()) {
                lastVoiceTrigger = now
                sos.trigger("Voice code heard")
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) = heard(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = heard(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = heard(hypothesis, "text")

    override fun onError(exception: Exception?) {
        AppState.voiceStatus = "Error"
        refreshNotification()
        if (!destroyed && running) main.postDelayed({ restartVoice() }, 3000L)
    }

    override fun onTimeout() {
        // not used: we listen continuously
    }

    // ------------------------------------------------------------------ power button trigger

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val a = intent?.action
                if (a == Intent.ACTION_SCREEN_OFF || a == Intent.ACTION_SCREEN_ON) {
                    onPowerPress()
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(r, filter)
        screenReceiver = r
    }

    private fun onPowerPress() {
        if (!Prefs.powerEnabled(this)) return
        val now = SystemClock.elapsedRealtime()
        pressTimes.addLast(now)
        while (pressTimes.isNotEmpty() && now - pressTimes.first() > PRESS_WINDOW_MS) {
            pressTimes.removeFirst()
        }
        if (pressTimes.size >= Prefs.powerPresses(this)) {
            pressTimes.clear()
            sos.trigger("Power button pressed ${Prefs.powerPresses(this)} times")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun refreshNotification() {
        if (!running || destroyed) return
        val text = when {
            AppState.sosActive -> "SOS ACTIVE. Alerts are being sent."
            AppState.voiceStatus == "Listening" -> "Listening for your code word"
            AppState.voiceStatus == "Loading" -> "Loading the offline voice model..."
            Prefs.powerEnabled(this) -> "Watching the power button"
            else -> "Protection is on"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Notif.ID_GUARD, Notif.build(this, text, AppState.sosActive))
    }
}
