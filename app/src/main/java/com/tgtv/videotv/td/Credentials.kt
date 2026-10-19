package com.tgtv.videotv.td

import android.content.Context
import android.content.SharedPreferences

/** Holds one-time API credentials and the chosen login type ("bot" or "user"). */
object Credentials {
    private const val PREFS = "tgvideotv"
    const val KEY_API_ID = "api_id"
    const val KEY_API_HASH = "api_hash"
    const val KEY_BOT_TOKEN = "bot_token"
    const val KEY_LOGIN_MODE = "login_mode"
    const val KEY_TMDB_KEY = "tmdb_key"
    const val KEY_TMDB_DISABLED = "tmdb_disabled"
    const val MODE_BOT = "bot"
    const val MODE_USER = "user"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, apiId: Int, apiHash: String, botToken: String, mode: String) {
        prefs(context).edit()
            .putInt(KEY_API_ID, apiId)
            .putString(KEY_API_HASH, apiHash)
            .putString(KEY_BOT_TOKEN, botToken)
            .putString(KEY_LOGIN_MODE, mode)
            .apply()
    }

    /** Login type stored on the previous sign-in; null before the first setup. */
    fun loginMode(context: Context): String? =
        prefs(context).getString(KEY_LOGIN_MODE, null)

    fun hasAll(context: Context): Boolean =
        apiId(context) > 0 &&
            !apiHash(context).isNullOrBlank() &&
            !botToken(context).isNullOrBlank()

    /** API credentials are enough for TDLib to init - QR (user) login needs no bot token. */
    fun hasApi(context: Context): Boolean =
        apiId(context) > 0 && !apiHash(context).isNullOrBlank()

    /** Removes API ids and the bot token - used by the Sign out action. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun apiId(context: Context): Int = prefs(context).getInt(KEY_API_ID, 0)
    fun apiHash(context: Context): String? = prefs(context).getString(KEY_API_HASH, null)
    fun botToken(context: Context): String? = prefs(context).getString(KEY_BOT_TOKEN, null)

    /** Bring-your-own TMDB API key for metadata (title/year/rating); null when unset. */
    fun tmdbKey(context: Context): String? = prefs(context).getString(KEY_TMDB_KEY, null)

    fun setTmdbKey(context: Context, key: String?) {
        prefs(context).edit()
            .putString(KEY_TMDB_KEY, key?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    /** Metadata is on by default (built-in key); this is the manual off switch. */
    fun tmdbDisabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TMDB_DISABLED, false)

    fun setTmdbDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TMDB_DISABLED, disabled).apply()
    }
}
