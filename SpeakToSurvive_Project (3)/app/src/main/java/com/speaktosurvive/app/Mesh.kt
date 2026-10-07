package com.speaktosurvive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.random.Random

/** One emergency message as it travels between phones over Bluetooth. */
data class MeshAlert(
    val version: Int,
    val hops: Int,
    val id: Int,
    val lat: Double?,
    val lon: Double?,
    val numbers: List<String>,
    val tag: String
) {
    val isTest: Boolean get() = version == MeshCodec.VER_TEST
}

/**
 * Packs an alert into 24 bytes so it fits in a normal Bluetooth LE advertisement:
 *  byte 0      : version (2 bits) | hops left (4 bits) | contact count (2 bits)
 *  bytes 1-3   : alert id
 *  bytes 4-6   : latitude  (24 bit)
 *  bytes 7-9   : longitude (24 bit)
 *  bytes 10-14 : contact 1 phone number (digits, 40 bit)
 *  bytes 15-19 : contact 2 phone number
 *  bytes 20-23 : short name tag (4 ASCII characters)
 */
object MeshCodec {
    const val COMPANY_ID = 0x5354
    const val VER_REAL = 1
    const val VER_TEST = 2
    const val START_HOPS = 3
    const val PAYLOAD_LEN = 24
    private const val MAX24 = 16777215L

    /** Returns "+digits" or null when the number cannot be carried in the packet. */
    fun normalize(raw: String): String? {
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (!plus) {
            d = d.trimStart('0')
            if (d.length == 10) d = "91$d"
        }
        if (d.length < 7 || d.length > 12) return null
        return "+$d"
    }

    fun tagFor(name: String): String {
        val first = name.trim().split(" ").firstOrNull() ?: ""
        val clean = first.filter { it.code in 33..126 }.take(4)
        return if (clean.isEmpty()) "USER" else clean
    }

    private fun put(out: ByteArray, off: Int, value: Long, bytes: Int) {
        var v = value
        for (i in bytes - 1 downTo 0) {
            out[off + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }

    private fun get(b: ByteArray, off: Int, bytes: Int): Long {
        var v = 0L
        for (i in 0 until bytes) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    fun encode(a: MeshAlert): ByteArray {
        val out = ByteArray(PAYLOAD_LEN)
        val nums = a.numbers.take(2)
        val b0 = ((a.version and 3) shl 6) or ((a.hops and 15) shl 2) or (nums.size and 3)
        out[0] = b0.toByte()
        put(out, 1, (a.id.toLong() and MAX24), 3)
        val la = a.lat
        val lo = a.lon
        if (la != null && lo != null) {
            val x = Math.round((la + 90.0) / 180.0 * MAX24).coerceIn(1L, MAX24)
            val y = Math.round((lo + 180.0) / 360.0 * MAX24).coerceIn(1L, MAX24)
            put(out, 4, x, 3)
            put(out, 7, y, 3)
        }
        for (i in nums.indices) {
            val digits = nums[i].filter { it.isDigit() }
            put(out, 10 + i * 5, digits.toLongOrNull() ?: 0L, 5)
        }
        val t = a.tag.padEnd(4, ' ').take(4)
        for (i in 0 until 4) {
            val c = t[i].code
            out[20 + i] = (if (c in 32..126) c else 63).toByte()
        }
        return out
    }

    fun decode(b: ByteArray?): MeshAlert? {
        if (b == null || b.size < PAYLOAD_LEN) return null
        val b0 = b[0].toInt() and 0xFF
        val version = (b0 shr 6) and 3
        if (version != VER_REAL && version != VER_TEST) return null
        val hops = (b0 shr 2) and 15
        val count = b0 and 3
        if (count > 2) return null
        val id = get(b, 1, 3).toInt()
        val x = get(b, 4, 3)
        val y = get(b, 7, 3)
        var lat: Double? = null
        var lon: Double? = null
        if (x != 0L || y != 0L) {
            lat = x.toDouble() / MAX24 * 180.0 - 90.0
            lon = y.toDouble() / MAX24 * 360.0 - 180.0
        }
        val nums = ArrayList<String>()
        for (i in 0 until count) {
            val v = get(b, 10 + i * 5, 5)
            val s = v.toString()
            if (v > 0 && s.length in 7..12) nums.add("+$s")
        }
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val c = b[20 + i].toInt() and 0xFF
            if (c in 33..126) sb.append(c.toChar())
        }
        return MeshAlert(version, hops, id, lat, lon, nums, if (sb.isEmpty()) "USER" else sb.toString())
    }
}

/**
 * Bluetooth relay (Node A -> Node B -> Node C).
 *  - As the victim phone (A): broadcasts a tiny alert packet that includes the emergency contact numbers.
 *  - As a helper phone (B): hears the packet, re-broadcasts it further (hop counter) and, if it has
 *    mobile signal, sends the SMS to the contact numbers (C) on behalf of A.
 */
class MeshManager(private val ctx: Context, private val onChange: () -> Unit) {

    companion object {
        private const val ACTION_SENT = "com.speaktosurvive.app.MESH_SMS_SENT"
        private const val RELAY_DURATION_MS = 45_000L
        private const val RELAY_REFRESH_MS = 40_000L
        private const val SMS_RETRY_MS = 20_000L
        private const val SMS_UPDATE_MS = 120_000L
        private const val MAX_ALERTS_PER_HOUR = 6
        private const val MAX_CONCURRENT_RELAYS = 2
        private const val MOVE_METERS = 25f
    }

    private class Seen {
        var firstSeen = 0L
        var lastRelay = 0L
        var lastSmsTry = 0L
        var lastSmsOk = 0L
        var smsOk = false
        var lat: Double? = null
        var lon: Double? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var scanning = false
    private var scanner: BluetoothLeScanner? = null
    private var smsReceiver: BroadcastReceiver? = null

    private var ownId = 0
    private var ownCb: AdvertiseCallback? = null
    private val ownIds = HashSet<Int>()

    private val relays = LinkedHashMap<Int, AdvertiseCallback>()
    private val seen = HashMap<Int, Seen>()
    private val alertTimes = ArrayDeque<Long>()

    private fun adapter(): BluetoothAdapter? = btManager?.adapter

    private fun hasBt(perm: String): Boolean =
        if (Build.VERSION.SDK_INT >= 31) Perms.granted(ctx, perm) else true

    private fun canScan(): Boolean = hasBt(Manifest.permission.BLUETOOTH_SCAN)
    private fun canAdvertise(): Boolean = hasBt(Manifest.permission.BLUETOOTH_ADVERTISE)

    // ------------------------------------------------------------------ scanning (helper role)

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (scanning) return
        val ad = adapter()
        if (ad == null) {
            AppState.meshStatus = "No Bluetooth on this phone"
            return
        }
        if (!ad.isEnabled) {
            AppState.meshStatus = "Bluetooth is off"
            return
        }
        if (!canScan()) {
            AppState.meshStatus = "Bluetooth permission missing"
            return
        }
        try {
            val sc = ad.bluetoothLeScanner
            if (sc == null) {
                AppState.meshStatus = "Bluetooth is off"
                return
            }
            val filter = ScanFilter.Builder()
                .setManufacturerData(MeshCodec.COMPANY_ID, ByteArray(0))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            sc.startScan(listOf(filter), settings, scanCb)
            scanner = sc
            scanning = true
            registerSmsReceiver()
            AppState.meshStatus = "Watching for nearby alerts"
        } catch (e: Exception) {
            AppState.meshStatus = "Bluetooth scan problem"
        }
        onChange()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!scanning) return
        try {
            scanner?.stopScan(scanCb)
        } catch (e: Exception) {
            // ignore
        }
        scanning = false
        scanner = null
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null) handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            if (results != null) for (r in results) handle(r)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            AppState.meshStatus = "Bluetooth scan failed ($errorCode)"
            onChange()
        }
    }

    private fun handle(r: ScanResult) {
        val bytes = r.scanRecord?.getManufacturerSpecificData(MeshCodec.COMPANY_ID)
        val alert = MeshCodec.decode(bytes) ?: return
        onAlert(alert)
    }

    private fun onAlert(a: MeshAlert) {
        if (ownIds.contains(a.id)) return
        val now = SystemClock.elapsedRealtime()
        var s = seen[a.id]
        if (s == null) {
            while (alertTimes.isNotEmpty() && now - alertTimes.first() > 3_600_000L) alertTimes.removeFirst()
            if (alertTimes.size >= MAX_ALERTS_PER_HOUR) return
            alertTimes.addLast(now)
            s = Seen()
            s.firstSeen = now
            seen[a.id] = s
            announce(a)
        }
        if (a.hops > 0) relay(a, s, now)
        maybeSms(a, s, now)
    }

    private fun announce(a: MeshAlert) {
        val link = mapLink(a.lat, a.lon)
        val title = if (a.isTest) "Test alert nearby (Bluetooth)" else "Someone nearby needs help"
        val text = "${a.tag} sent an SOS by Bluetooth. Your phone is passing it on to their emergency contacts." +
            (if (link != null) " Tap to see the location." else "")
        Notif.nearbyAlert(ctx, a.id, title, text, link)
        AppState.meshEvent = "Received ${if (a.isTest) "test " else ""}alert from ${a.tag}"
        onChange()
    }

    private fun mapLink(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return "https://maps.google.com/?q=" + String.format(Locale.US, "%.6f,%.6f", lat, lon)
    }

    // ------------------------------------------------------------------ relay

    private fun relay(a: MeshAlert, s: Seen, now: Long) {
        if (!canAdvertise()) return
        if (s.lastRelay != 0L && now - s.lastRelay < RELAY_REFRESH_MS) return
        s.lastRelay = now
        val old = relays.remove(a.id)
        stopAdvert(old)
        while (relays.size >= MAX_CONCURRENT_RELAYS) {
            val firstKey = relays.keys.first()
            stopAdvert(relays.remove(firstKey))
        }
        val cb = startAdvert(a.copy(hops = a.hops - 1)) ?: return
        relays[a.id] = cb
        main.postDelayed({
            if (relays[a.id] === cb) {
                relays.remove(a.id)
                stopAdvert(cb)
            }
        }, RELAY_DURATION_MS)
    }

    // ------------------------------------------------------------------ SMS on behalf of the victim

    private fun maybeSms(a: MeshAlert, s: Seen, now: Long) {
        if (a.numbers.isEmpty()) return
        if (!Perms.granted(ctx, Manifest.permission.SEND_SMS)) return
        val moved = movedSince(s, a)
        val firstTry = !s.smsOk && (s.lastSmsTry == 0L || now - s.lastSmsTry > SMS_RETRY_MS)
        val update = s.smsOk && now - s.lastSmsOk > SMS_UPDATE_MS && moved
        if (!firstTry && !update) return
        s.lastSmsTry = now
        s.lat = a.lat
        s.lon = a.lon
        val text = relayText(a, s.smsOk)
        for (n in a.numbers.take(2)) sendSms(a.id, n, text)
    }

    private fun movedSince(s: Seen, a: MeshAlert): Boolean {
        val la = a.lat
        val lo = a.lon
        val sla = s.lat
        val slo = s.lon
        if (la == null || lo == null) return false
        if (sla == null || slo == null) return true
        val res = FloatArray(1)
        Location.distanceBetween(sla, slo, la, lo, res)
        return res[0] > MOVE_METERS
    }

    private fun relayText(a: MeshAlert, update: Boolean): String {
        val prefix = when {
            a.isTest -> "TEST ALERT (not real): "
            update -> "SOS UPDATE: "
            else -> "SOS! "
        }
        val link = mapLink(a.lat, a.lon)
        val tail = if (link != null) "Location: $link" else "Location unknown."
        return "$prefix${a.tag} needs help (relayed by a nearby phone using Speak to Survive). $tail"
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun sendSms(id: Int, number: String, text: String) {
        try {
            val intent = Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("id", id)
            val pi = PendingIntent.getBroadcast(
                ctx, id + number.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val sm = smsManager()
            val parts = sm.divideMessage(text)
            val sentIntents = ArrayList<PendingIntent>()
            for (i in 0 until parts.size) sentIntents.add(pi)
            sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)
        } catch (e: Exception) {
            AppState.meshEvent = "Could not send relay SMS: ${e.message}"
            onChange()
        }
    }

    private fun registerSmsReceiver() {
        if (smsReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getIntExtra("id", 0) ?: return
                val s = seen[id] ?: return
                if (resultCode == Activity.RESULT_OK) {
                    s.smsOk = true
                    s.lastSmsOk = SystemClock.elapsedRealtime()
                    AppState.meshEvent = "Relayed a nearby SOS to the contact by SMS"
                } else {
                    AppState.meshEvent = "No signal here to send the SMS. Still relaying by Bluetooth."
                }
                onChange()
            }
        }
        ContextCompat.registerReceiver(
            ctx, r, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        smsReceiver = r
    }

    // ------------------------------------------------------------------ own broadcast (victim role)

    fun isBluetoothOn(): Boolean = adapter()?.isEnabled == true

    fun broadcastOwn(test: Boolean, loc: Location?) {
        if (!isBluetoothOn()) {
            AppState.meshStatus = "Bluetooth is off. Turn it on to alert nearby phones."
            onChange()
            return
        }
        if (!canAdvertise()) {
            AppState.meshStatus = "Bluetooth permission missing"
            onChange()
            return
        }
        if (ownId == 0) {
            ownId = 1 + Random.nextInt(0xFFFFFE)
            ownIds.add(ownId)
        }
        val numbers = ArrayList<String>()
        for (c in Prefs.contacts(ctx)) {
            val n = MeshCodec.normalize(c.number)
            if (n != null && !numbers.contains(n)) numbers.add(n)
            if (numbers.size >= 2) break
        }
        val alert = MeshAlert(
            if (test) MeshCodec.VER_TEST else MeshCodec.VER_REAL,
            MeshCodec.START_HOPS,
            ownId,
            loc?.latitude,
            loc?.longitude,
            numbers,
            MeshCodec.tagFor(Prefs.userName(ctx))
        )
        stopAdvert(ownCb)
        ownCb = startAdvert(alert)
        AppState.meshStatus = if (ownCb != null) "Broadcasting to nearby phones" else "Bluetooth broadcast problem"
        onChange()
    }

    fun stopOwn() {
        stopAdvert(ownCb)
        ownCb = null
        ownId = 0
        if (scanning) AppState.meshStatus = "Watching for nearby alerts"
        else if (AppState.meshStatus.startsWith("Broadcasting")) AppState.meshStatus = "Off"
        onChange()
    }

    // ------------------------------------------------------------------ advertising helpers

    @SuppressLint("MissingPermission")
    private fun startAdvert(a: MeshAlert): AdvertiseCallback? {
        val adv = adapter()?.bluetoothLeAdvertiser ?: return null
        return try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MeshCodec.COMPANY_ID, MeshCodec.encode(a))
                .build()
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    AppState.meshStatus = "Bluetooth broadcast failed ($errorCode)"
                    onChange()
                }
            }
            adv.startAdvertising(settings, data, cb)
            cb
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvert(cb: AdvertiseCallback?) {
        if (cb == null) return
        try {
            adapter()?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        stopScanning()
        stopAdvert(ownCb)
        ownCb = null
        for (cb in relays.values) stopAdvert(cb)
        relays.clear()
        try {
            smsReceiver?.let { ctx.unregisterReceiver(it) }
        } catch (e: Exception) {
            // ignore
        }
        smsReceiver = null
        AppState.meshStatus = "Off"
    }
}
