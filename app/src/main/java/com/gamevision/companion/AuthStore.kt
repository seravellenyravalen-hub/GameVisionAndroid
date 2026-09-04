package com.gamevision.companion

import android.content.Context

object AuthStore {
    private const val PREFS = "gamevision_auth"
    private const val TOKEN = "token"
    private const val EMAIL = "email"
    private const val CREDITS = "credits"

    fun token(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null)?.takeIf { it.isNotBlank() }
    fun email(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EMAIL, "") ?: ""
    fun credits(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CREDITS, 0)

    fun save(context: Context, token: String, email: String, credits: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, token).putString(EMAIL, email).putInt(CREDITS, credits).apply()
    }

    fun updateCredits(context: Context, credits: Int) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(CREDITS, credits).apply()
    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
