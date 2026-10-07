package com.speaktosurvive.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class Contact(val name: String, val number: String)

/** Simple on-device settings storage. Nothing here ever leaves the phone. */
object Prefs {
    const val MAX_CONTACTS = 5
    const val DEFAULT_CODE = "help me now"
    const val DEFAULT_PRESSES = 5

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("speak_to_survive", Context.MODE_PRIVATE)

    fun userName(c: Context): String = sp(c).getString("name", "") ?: ""
    fun setUserName(c: Context, v: String) {
        sp(c).edit().putString("name", v).apply()
    }

    fun codeWord(c: Context): String = sp(c).getString("code", DEFAULT_CODE) ?: DEFAULT_CODE
    fun setCodeWord(c: Context, v: String) {
        sp(c).edit().putString("code", v).apply()
    }

    fun voiceEnabled(c: Context): Boolean = sp(c).getBoolean("voice_on", true)
    fun setVoiceEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("voice_on", v).apply()
    }

    fun powerEnabled(c: Context): Boolean = sp(c).getBoolean("power_on", true)
    fun setPowerEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("power_on", v).apply()
    }

    fun powerPresses(c: Context): Int = sp(c).getInt("presses", DEFAULT_PRESSES)
    fun setPowerPresses(c: Context, v: Int) {
        sp(c).edit().putInt("presses", v).apply()
    }

    fun autoCall(c: Context): Boolean = sp(c).getBoolean("auto_call", true)
    fun setAutoCall(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("auto_call", v).apply()
    }


    fun helpOthers(c: Context): Boolean = sp(c).getBoolean("help_others", true)
    fun setHelpOthers(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("help_others", v).apply()
    }

    fun meshSend(c: Context): Boolean = sp(c).getBoolean("mesh_send", true)
    fun setMeshSend(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("mesh_send", v).apply()
    }

    fun meshAlways(c: Context): Boolean = sp(c).getBoolean("mesh_always", false)
    fun setMeshAlways(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("mesh_always", v).apply()
    }

    // ---- stop password (stored only as a salted hash)

    fun hasPassword(c: Context): Boolean = sp(c).getString("pw_hash", null) != null

    fun setPassword(c: Context, pw: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("pw_salt", saltHex).putString("pw_hash", hash(saltHex, pw)).apply()
    }

    fun clearPassword(c: Context) {
        sp(c).edit().remove("pw_salt").remove("pw_hash").apply()
    }

    fun checkPassword(c: Context, pw: String): Boolean {
        val salt = sp(c).getString("pw_salt", null) ?: return false
        val stored = sp(c).getString("pw_hash", null) ?: return false
        return MessageDigest.isEqual(hash(salt, pw).toByteArray(), stored.toByteArray())
    }

    private fun hash(salt: String, pw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest((salt + pw).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun contacts(c: Context): List<Contact> {
        val raw = sp(c).getString("contacts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Contact>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Contact(o.getString("n"), o.getString("p")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setContacts(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        for (ct in list) {
            arr.put(JSONObject().put("n", ct.name).put("p", ct.number))
        }
        sp(c).edit().putString("contacts", arr.toString()).apply()
    }

    /** Returns an error message, or null when the code phrase is acceptable. */
    fun codeError(s: String): String? {
        val t = s.trim().lowercase()
        if (!Regex("^[a-z]+( [a-z]+)*$").matches(t)) {
            return "Use only English letters and single spaces"
        }
        val words = t.split(" ")
        if (words.size < 2) return "Use at least 2 words so it is not said by accident"
        if (words.size > 4) return "Use at most 4 words"
        return null
    }

    fun numberOk(s: String): Boolean = Regex("^\\+?[0-9]{6,15}$").matches(s)
}
