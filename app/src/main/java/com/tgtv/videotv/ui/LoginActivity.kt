package com.tgtv.videotv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.videotv.R
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import kotlinx.coroutines.launch

/**
 * Two-step setup screen:
 * 1. choose "Sign in as a Bot" or "Sign in with Phone (QR)"
 * 2. fill in the minimal credential form for that choice.
 */
class LoginActivity : AppCompatActivity() {

    private enum class Mode { BOT, QR }

    private var mode: Mode? = null

    private lateinit var choicePane: LinearLayout
    private lateinit var formPane: LinearLayout
    private lateinit var hintText: TextView
    private lateinit var apiId: EditText
    private lateinit var apiHash: EditText
    private lateinit var botToken: EditText
    private lateinit var status: TextView
    private lateinit var continueButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        choicePane = findViewById(R.id.choicePane)
        formPane = findViewById(R.id.formPane)
        hintText = findViewById(R.id.hintText)
        apiId = findViewById(R.id.apiIdInput)
        apiHash = findViewById(R.id.apiHashInput)
        botToken = findViewById(R.id.botTokenInput)
        status = findViewById(R.id.loginStatus)
        continueButton = findViewById(R.id.continueButton)

        // Prefill saved values so editing / re-signing in is quick
        apiId.setText(Credentials.apiId(this).takeIf { it > 0 }?.toString() ?: "")
        apiHash.setText(Credentials.apiHash(this) ?: "")
        botToken.setText(Credentials.botToken(this) ?: "")

        findViewById<View>(R.id.botCard).setOnClickListener { enterMode(Mode.BOT) }
        findViewById<View>(R.id.qrCard).setOnClickListener {
            // With credentials built at build time there is no form worth
            // showing - go straight to the Telegram "link device" QR.
            if (Credentials.bakedApiPresent()) {
                mode = Mode.QR
                onSubmit()
            } else {
                enterMode(Mode.QR)
            }
        }
        findViewById<View>(R.id.backButton).setOnClickListener {
            mode = null
            choicePane.visibility = View.VISIBLE
            formPane.visibility = View.GONE
            status.text = ""
        }

        findViewById<View>(R.id.phoneButton).setOnClickListener {
            val m = mode ?: return@setOnClickListener
            val intent = Intent(this, com.tgtv.videotv.setup.PhoneSetupActivity::class.java)
            intent.putExtra(EXTRA_MODE, if (m == Mode.BOT) Credentials.MODE_BOT else Credentials.MODE_USER)
            startActivity(intent)
        }

        continueButton.setOnClickListener { onSubmit() }
    }

    private companion object {
        const val EXTRA_MODE = "login_mode"
    }

    private fun enterMode(m: Mode) {
        mode = m
        choicePane.visibility = View.GONE
        formPane.visibility = View.VISIBLE
        status.text = ""

        val isBot = m == Mode.BOT
        botToken.visibility = if (isBot) View.VISIBLE else View.GONE

        val signOutPending = Credentials.loginMode(this)?.let { saved ->
            saved != (if (isBot) Credentials.MODE_BOT else Credentials.MODE_USER)
        } == true

        // Credentials compiled at build time are prefilled invisibly; the
        // user never sees or types API keys, so keep that out of the copy.
        val baked = Credentials.bakedApiPresent()
        if (baked) {
            apiId.visibility = View.GONE
            apiHash.visibility = View.GONE
        }

        val pending =
            if (signOutPending) {
                "\nSwitching sign-in type erases the old Telegram session first."
            } else {
                ""
            }
        hintText.text = when {
            isBot && baked ->
                "Enter the @BotFather bot token,\nor type it on your phone." + pending
            isBot ->
                "Enter your API ID + API Hash (my.telegram.org/apps)\n" +
                "and the bot token you got from @BotFather." + pending
            !baked ->
                "Enter your API ID + API Hash (my.telegram.org/apps).\n" +
                "Then scan the code with your phone's Telegram app\n" +
                "(Settings > Devices > Link Desktop Device), on the same Wi-Fi." + pending
            else -> "Scan the code with your phone's Telegram app\nYou are already signed in." + pending
        }
        findViewById<View>(R.id.phoneButton).visibility =
            if (baked && !isBot) View.GONE else View.VISIBLE

        continueButton.text =
            if (isBot) "Save and connect" else "Save and show QR"
        continueButton.requestFocus()
    }

    private fun onSubmit() {
        val m = mode ?: return
        val idText = apiId.text.toString().trim()
        val hash = apiHash.text.toString().trim()
        val token = botToken.text.toString().trim()

        val id = idText.toIntOrNull()
        if (id == null || id <= 0) {
            status.text = "API ID must be a positive number (from my.telegram.org)"
            return
        }
        if (hash.length < 32) {
            status.text = "API Hash looks wrong (32 hex characters from my.telegram.org)"
            return
        }
        if (m == Mode.BOT && (!token.contains(':') || token.length < 30)) {
            status.text = "Bot token looks wrong: it is like \"123456:ABC-DEF...\" from @BotFather"
            return
        }

        val loginMode = if (m == Mode.BOT) Credentials.MODE_BOT else Credentials.MODE_USER
        val previousMode = Credentials.loginMode(this)
        val savedId = Credentials.apiId(this)
        val savedHash = Credentials.apiHash(this).orEmpty()
        val savedToken = Credentials.botToken(this).orEmpty()
        val apiChanged = savedId != id || savedHash != hash || savedToken != token

        status.text = ""
        val isBot = m == Mode.BOT
        Credentials.save(this, id, hash, if (isBot) token else "", loginMode)

        lifecycleScope.launch {
            status.text = if (isBot) "Connecting as bot..." else "Preparing QR sign-in..."
            // TDLib keeps exactly one session per database: erase it when the
            // sign-in type changes or none was stored before
            val needsReset = previousMode == null || previousMode != loginMode ||
                (isBot && previousMode == Credentials.MODE_BOT && apiChanged) ||
                (!isBot && previousMode == Credentials.MODE_USER && apiChanged)
            if (needsReset) {
                TdLib.resetDb(applicationContext)
            }
            TdLib.start(applicationContext)
            if (isBot) {
                Toast.makeText(this@LoginActivity, "Credentials saved. Connecting...", Toast.LENGTH_SHORT).show()
                finish() // back to MainActivity, which waits for Ready and loads the library
            } else {
                startActivity(Intent(this@LoginActivity, QrLoginActivity::class.java))
                finish()
            }
        }
    }
}
