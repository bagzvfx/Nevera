package com.nevera.corporation.data.sources

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Manages optional local YouTube Music session cookies for personal playlist & liked sync.
 */
object AccountSyncManager {

    private lateinit var prefs: SharedPreferences

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _lastSyncAtMs = MutableStateFlow(0L)
    val lastSyncAtMs: StateFlow<Long> = _lastSyncAtMs.asStateFlow()

    @Volatile
    private var cookieString: String = ""

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("nevera_account_sync", Context.MODE_PRIVATE)
        cookieString = prefs.getString("ytm_cookie", "").orEmpty()
        _lastSyncAtMs.value = prefs.getLong("last_sync_ms", 0L)
        _isSignedIn.value = extractSapisid(cookieString).isNotBlank()
    }

    fun saveCookie(cookie: String): Boolean {
        val clean = cookie.trim()
        val sapisid = extractSapisid(clean)
        if (sapisid.isBlank()) return false
        cookieString = clean
        _isSignedIn.value = true
        if (::prefs.isInitialized) {
            prefs.edit().putString("ytm_cookie", clean).apply()
        }
        return true
    }

    fun markSynced() {
        val now = System.currentTimeMillis()
        _lastSyncAtMs.value = now
        if (::prefs.isInitialized) {
            prefs.edit().putLong("last_sync_ms", now).apply()
        }
    }

    fun signOut() {
        cookieString = ""
        _isSignedIn.value = false
        if (::prefs.isInitialized) {
            prefs.edit().remove("ytm_cookie").apply()
        }
    }

    fun authHeaders(): Map<String, String> {
        val cookie = cookieString
        if (cookie.isBlank()) return emptyMap()
        val sapisid = extractSapisid(cookie)
        if (sapisid.isBlank()) return emptyMap()
        val ts = System.currentTimeMillis() / 1000L
        val hash = sha1Hex("$ts $sapisid https://music.youtube.com")
        return mapOf(
            "Cookie" to cookie,
            "Authorization" to "SAPISIDHASH ${ts}_$hash",
            "X-Origin" to "https://music.youtube.com"
        )
    }

    internal fun extractSapisid(cookie: String): String {
        val pairs = cookie.split(";").map { it.trim() }
        for (p in pairs) {
            if (p.startsWith("SAPISID=")) return p.removePrefix("SAPISID=").trim()
            if (p.startsWith("__Secure-3PAPISID=")) return p.removePrefix("__Secure-3PAPISID=").trim()
        }
        return ""
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
