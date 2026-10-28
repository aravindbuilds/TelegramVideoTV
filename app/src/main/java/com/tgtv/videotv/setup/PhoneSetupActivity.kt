package com.tgtv.videotv.setup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.videotv.R
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.ui.MainActivity
import com.tgtv.videotv.ui.QrLoginActivity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.security.SecureRandom

private const val TAG = "PhoneSetup"

/**
 * Shows a QR that opens the phone-typing page: on the phone the user picks
 * bot / phone-QR, fills api_id + api_hash (+ bot token), taps Connect and the
 * TV continues automatically.
 */
class PhoneSetupActivity : AppCompatActivity() {

    private var server: SetupServer? = null
    private var leaving = false
    @Volatile private var credsReceived = false

    @Volatile private var stateName = "waiting"
    @Volatile private var stateMsg = "Waiting for your phone..."

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_setup)
        statusView = findViewById(R.id.status)

        val steps = findViewById<LinearLayout>(R.id.steps)
        val baked = Credentials.bakedApiPresent()
        listOf(
            "?  Keep this TV and your phone on the SAME Wi-Fi network",
            "?  Scan this code with your phone camera",
            if (baked) "?  Enter only the bot token on your phone" else
                "?  Fill in API ID / API Hash (and bot token)",
            "?  Tap Connect - the TV continues automatically",
        ).forEach { text ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_phone_step, steps, false)
            row.findViewById<TextView>(R.id.stepText).text = text
            steps.addView(row)
        }

        startServer()

        lifecycleScope.launch {
            TdLib.authState.collect { render() }
        }
        lifecycleScope.launch {
            TdLib.authErrors.collect { setStatus("error", it) }
        }
    }

    private fun startServer() {
        val ip = QrImage.localIp()
        val urlView = findViewById<TextView>(R.id.url)
        if (ip == null) {
            urlView.text = "Connect this TV to Wi-Fi or Ethernet"
            setStatus("error", "No local network found")
            return
        }
        val token = ByteArray(5).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        for (port in 8765..8775) {
            try {
                val s = SetupServer(
                    port, token, assets, ::onCredentials, ::statusJson,
                    bakedId = Credentials.apiId(this),
                    bakedHash = Credentials.apiHash(this) ?: "",
                )
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                val url = "http://$ip:$port/?t=$token"
                findViewById<ImageView>(R.id.qr).setImageBitmap(QrImage.make(url, 720))
                urlView.text = "http://$ip:$port"
                return
            } catch (_: Exception) {
                // try the next port
            }
        }
        setStatus("error", "Couldn't start the setup server")
    }

    private fun onCredentials(mode: String, id: Int, hash: String, bot: String) {
        runOnUiThread {
            credsReceived = true
            val previousMode = Credentials.loginMode(this)
            setStatus("connecting", "Saving credentials...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.i(TAG, "phone-setup: saving mode=$mode id=$id")
                    if (previousMode != mode) {
                        Log.i(TAG, "phone-setup: resetting TDLib db")
                        TdLib.resetDb(applicationContext)
                        Log.i(TAG, "phone-setup: reset done")
                    }
                    Credentials.save(applicationContext, id, hash, bot, mode)
                    Log.i(TAG, "phone-setup: starting TDLib")
                    TdLib.start(applicationContext)
                    Log.i(TAG, "phone-setup: TDLib started")
                    if (mode == Credentials.MODE_USER) {
                        // Hand over to the QR screen so the phone can scan it
                        withContext(Dispatchers.Main) {
                            if (!leaving) {
                                leaving = true
                                delay(900)
                                Log.i(TAG, "phone-setup: opening QR screen")
                                startActivity(Intent(this@PhoneSetupActivity, QrLoginActivity::class.java))
                                finish()
                            }
                        }
                    } else {
                        // Bot: wait for authorization, then open the library
                        val ok = withTimeoutOrNull(30_000L) {
                            while (TdLib.authState.value !is TdApi.AuthorizationStateReady) delay(250)
                            true
                        } ?: false
                        Log.i(TAG, "phone-setup: bot ready=$ok")
                        withContext(Dispatchers.Main) {
                            if (!leaving) {
                                leaving = true
                                if (ok) {
                                    delay(800)
                                    startActivity(
                                        Intent(this@PhoneSetupActivity, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                    )
                                } else {
                                    setStatus("error", "Connecting failed - re-check the values on the phone")
                                }
                                finish()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "phone-setup failed", t)
                    withContext(Dispatchers.Main) {
                        setStatus("error", "Setup error: ${t.message}")
                    }
                }
            }
        }
    }

    private fun statusJson() = JSONObject().put("state", stateName).put("message", stateMsg)

    private fun setStatus(state: String, msg: String) {
        stateName = state
        stateMsg = msg
        statusView.text = msg
        statusView.setTextColor(
            when (state) {
                "ready" -> 0xFF4ADE80.toInt()
                "qr" -> 0xFF4ADE80.toInt()
                "error" -> 0xFFF87171.toInt()
                "connecting" -> 0xFFFBBF24.toInt()
                else -> 0xFF8A97AB.toInt()
            },
        )
    }

    private fun render() {
        if (!credsReceived || leaving) return
        when (val st = TdLib.authState.value) {
            is org.drinkless.tdlib.TdApi.AuthorizationStateReady -> {
                setStatus("ready", "Connected - starting your library...")
                leaving = true
                lifecycleScope.launch {
                    delay(1200)
                    startActivity(
                        Intent(this@PhoneSetupActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                    finish()
                }
            }
            is org.drinkless.tdlib.TdApi.AuthorizationStateWaitPhoneNumber ->
                setStatus("connecting", "Connecting to Telegram...")
            else ->
                setStatus(
                    "connecting",
                    when (st) {
                        null -> "Connecting to Telegram..."
                        else -> "Signing in..."
                    },
                )
        }
    }

    override fun onStop() {
        if (leaving && server != null) {
            server?.stop()
            server = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }
}
