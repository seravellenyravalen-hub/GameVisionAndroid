package com.gamevision.companion

import android.app.Application
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

class GameVisionApplication : Application() {
    private val serverUrl = "https://gamevision-api-v2-production.up.railway.app"
    private val serverUri = URI(serverUrl)
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

    override fun onCreate() {
        super.onCreate()
        CookieHandler.setDefault(cookieManager)
        installSessionCookie()
    }

    fun installSessionCookie() {
        val token = AuthStore.token(this) ?: return
        runCatching {
            cookieManager.cookieStore.removeAll()
            val cookie = HttpCookie("gv_session", token).apply {
                path = "/"
                secure = true
                maxAge = 30L * 24L * 60L * 60L
            }
            cookieManager.cookieStore.add(serverUri, cookie)
        }
    }

    fun clearSessionCookie() {
        runCatching { cookieManager.cookieStore.removeAll() }
    }
}
