package com.gamevision.companion

import android.app.Application
import android.webkit.CookieManager

class GameVisionApplication : Application() {
    private val serverUrl = "https://gamevision-api-v2-production.up.railway.app"

    override fun onCreate() {
        super.onCreate()
        installSessionCookie()
    }

    fun installSessionCookie() {
        val token = AuthStore.token(this) ?: return
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie(serverUrl, "gv_session=$token; Path=/; Secure")
            manager.flush()
        }
    }

    fun clearSessionCookie() {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setCookie(serverUrl, "gv_session=; Path=/; Max-Age=0; Secure")
            manager.flush()
        }
    }
}
