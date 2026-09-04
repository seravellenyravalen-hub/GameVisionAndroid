package com.gamevision.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AuthActivity : Activity() {
    private val serverUrl = "https://gamevision-api-v2-production.up.railway.app"
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var action: Button
    private lateinit var switchMode: TextView
    private lateinit var status: TextView
    private var createMode = true

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if (AuthStore.token(this) != null) validateExistingSession() else showAuth() }

    private fun showAuth() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(34), dp(24), dp(24)); setBackgroundColor(Color.rgb(9, 12, 17)) }
        val logo = TextView(this).apply { text = "G/V"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; background = rounded(0xFFB8FF3D.toInt(), 24) }; root.addView(logo, LinearLayout.LayoutParams(dp(76), dp(76)))
        val title = TextView(this).apply { text = "GameVision"; textSize = 29f; setTextColor(0xFFF4F7FA.toInt()); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) }; root.addView(title)
        val subtitle = TextView(this).apply { text = "Your visual game companion"; textSize = 14f; setTextColor(0xFF8D9AAA.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(26)) }; root.addView(subtitle)
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(18)); background = rounded(0xFF151A22.toInt(), 20) }
        val heading = TextView(this).apply { textSize = 20f; setTextColor(0xFFF4F7FA.toInt()); typeface = Typeface.DEFAULT_BOLD }; card.addView(heading)
        val hint = TextView(this).apply { textSize = 12f; setTextColor(0xFF8D9AAA.toInt()); setPadding(0, dp(4), 0, dp(14)) }; card.addView(hint)
        email = field("Email"); password = field("Password").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        card.addView(email, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, 0, 0, dp(9)) }); card.addView(password, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, 0, 0, dp(12)) })
        action = Button(this).apply { background = rounded(0xFFB8FF3D.toInt(), 14); setTextColor(0xFF090C11.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setOnClickListener { submit() } }; card.addView(action, LinearLayout.LayoutParams(-1, dp(54)))
        status = TextView(this).apply { textSize = 11f; setTextColor(0xFF8D9AAA.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }; card.addView(status); root.addView(card, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        switchMode = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFB8FF3D.toInt()); setPadding(0, dp(18), 0, dp(18)); setOnClickListener { createMode = !createMode; updateCopy(heading, hint) } }; root.addView(switchMode)
        val footer = TextView(this).apply { text = "Free account • 50 AI actions per 24 hours • resets automatically"; textSize = 10f; setTextColor(0xFF596575.toInt()); gravity = Gravity.CENTER }; root.addView(footer)
        setContentView(root); updateCopy(heading, hint)
    }
    private fun updateCopy(heading: TextView, hint: TextView) { heading.text = if (createMode) "Create your account" else "Welcome back"; hint.text = if (createMode) "Sign in is required before GameVision can monitor or act." else "Sign in to continue to your GameVision workspace."; action.text = if (createMode) "CREATE ACCOUNT" else "SIGN IN"; switchMode.text = if (createMode) "Already have an account?  Sign in" else "New to GameVision?  Create account"; status.text = "" }
    private fun submit() {
        val e = email.text.toString().trim(); val p = password.text.toString(); if (e.isBlank() || p.isBlank()) { status.text = "Enter your email and password."; return }
        action.isEnabled = false; status.text = if (createMode) "Creating your account…" else "Signing in…"
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$serverUrl/api/auth/${if (createMode) "signup" else "login"}").openConnection() as HttpURLConnection; connection.requestMethod = "POST"; connection.connectTimeout = 8000; connection.readTimeout = 15000; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("email", e).put("password", p).toString().toByteArray(Charsets.UTF_8)) }; val code = connection.responseCode; val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty(); val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                if (code in 200..299) { val token = json.optString("token"); val user = json.optJSONObject("user") ?: JSONObject(); if (token.isBlank()) throw IllegalStateException("Missing session token"); AuthStore.save(this, token, user.optString("email", e), user.optInt("creditsRemaining", 50)); (application as? GameVisionApplication)?.installSessionCookie(); handler.post { launchMain() } }
                else { val message = json.optString("error").ifBlank { "Authentication failed (HTTP $code)." }; handler.post { action.isEnabled = true; status.text = message } }
            } catch (error: Exception) { handler.post { action.isEnabled = true; status.text = "Cannot reach the account service. Check your connection and try again." } } finally { connection?.disconnect() }
        }
    }
    private fun validateExistingSession() {
        executor.execute {
            var connection: HttpURLConnection? = null
            try { connection = URL("$serverUrl/api/auth/me").openConnection() as HttpURLConnection; connection.requestMethod = "GET"; connection.connectTimeout = 6000; connection.readTimeout = 8000; connection.setRequestProperty("Authorization", "Bearer ${AuthStore.token(this)}"); val code = connection.responseCode; if (code in 200..299) handler.post { launchMain() } else { AuthStore.clear(this); (application as? GameVisionApplication)?.clearSessionCookie(); handler.post { showAuth() } } } catch (_: Exception) { handler.post { showAuth() } } finally { connection?.disconnect() }
        }
    }
    private fun launchMain() { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() }
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; textSize = 13f; setTextColor(0xFFF4F7FA.toInt()); setHintTextColor(0xFF687586.toInt()); background = rounded(0xFF1A202A.toInt(), 14); setPadding(dp(13), 0, dp(13), 0); setSingleLine(true) }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
