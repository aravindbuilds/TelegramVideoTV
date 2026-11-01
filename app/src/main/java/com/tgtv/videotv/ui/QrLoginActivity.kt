package com.tgtv.videotv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tgtv.videotv.R
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

private const val QR_SIZE = 512

/**
 * Shows the "link desktop device" QR code and waits until TDLib signs in.
 *
 * QR sign-in can pause at extra verification steps (login code, 2FA
 * password, e-mail) or the user may prefer plain phone-number login -
 * each gets an input pane, ported from the phone app's login flow.
 */
class QrLoginActivity : AppCompatActivity() {

    private enum class Step { CODE, PASSWORD, PHONE }

    private lateinit var qrPane: View
    private lateinit var inputPane: View
    private lateinit var inputTitle: TextView
    private lateinit var inputField: EditText
    private lateinit var inputStatus: TextView
    private lateinit var inputSubmit: Button
    private lateinit var inputBack: Button
    private lateinit var qrView: ImageView
    private lateinit var status: TextView

    /** What the input pane currently collects; null = QR pane is showing. */
    private var step: Step? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_login)

        qrPane = findViewById(R.id.qrPane)
        inputPane = findViewById(R.id.inputPane)
        inputTitle = findViewById(R.id.inputTitle)
        inputField = findViewById(R.id.inputField)
        inputStatus = findViewById(R.id.inputStatus)
        inputSubmit = findViewById(R.id.inputSubmit)
        inputBack = findViewById(R.id.inputBack)
        qrView = findViewById(R.id.qrCodeView)
        status = findViewById(R.id.qrStatus)

        findViewById<Button>(R.id.phoneInstead).setOnClickListener { showPhoneStep() }

        if (!TdLib.isStarted && Credentials.hasApi(this)) {
            TdLib.start(applicationContext)
        }

        inputSubmit.setOnClickListener { onSubmit() }
        inputBack.setOnClickListener { showQrPane(reRequestQr = true) }
        inputField.setOnEditorActionListener { _, _, _ ->
            onSubmit()
            true
        }

        lifecycleScope.launch {
            TdLib.qrLink.collect { link ->
                if (link.isNullOrBlank() || qrView.tag == link) return@collect
                qrView.tag = link
                status.text = ""
                val bmp = runCatching { buildQrBitmap(link) }.getOrNull()
                if (bmp != null) qrView.setImageBitmap(bmp)
            }
        }

        lifecycleScope.launch {
            TdLib.authErrors.collect { err ->
                if (step == null) status.text = err else inputStatus.text = err
            }
        }

        lifecycleScope.launch {
            TdLib.authState.collect { st ->
                when (st) {
                    null -> status.text = "Connecting to Telegram..."
                    is TdApi.AuthorizationStateWaitTdlibParameters ->
                        status.text = "Connecting to Telegram..."
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        // TdLib auto-fires the QR request here (no bot token);
                        // nothing to do but keep the QR pane visible
                        if (step == null) status.text = "Preparing QR code..."
                    }
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                        if (step == null) status.text = getString(R.string.qr_instructions)
                    }
                    is TdApi.AuthorizationStateWaitCode ->
                        showInput(
                            Step.CODE,
                            "Enter the login code",
                            "Telegram sent a code to your app or SMS.\nType it with the remote.",
                            hint = "5-digit code",
                            isPassword = false,
                            submitLabel = "Check code",
                        )
                    is TdApi.AuthorizationStateWaitPassword ->
                        showInput(
                            Step.PASSWORD,
                            "Enter your 2FA password",
                            "This account has two-step verification on.\nType your cloud password.",
                            hint = "Password",
                            isPassword = true,
                            submitLabel = "Check password",
                        )
                    is TdApi.AuthorizationStateWaitEmailAddress ->
                        status.text = "E-mail verification needed - use phone sign-in instead"
                    is TdApi.AuthorizationStateReady -> {
                        Toast.makeText(
                            this@QrLoginActivity,
                            "Signed in - loading your chats",
                            Toast.LENGTH_SHORT,
                        ).show()
                        delay(700)
                        startActivity(Intent(this@QrLoginActivity, MainActivity::class.java))
                        finish()
                        return@collect
                    }
                    else -> Unit
                }
            }
        }
    }

    // ----------------------------------------------------------- input pane

    private fun showInput(
        target: Step,
        title: String,
        message: String,
        hint: String,
        isPassword: Boolean,
        submitLabel: String,
    ) {
        step = target
        inputPane.visibility = View.VISIBLE
        qrPane.visibility = View.GONE
        inputTitle.text = "$title\n$message"
        status.text = ""
        inputStatus.text = ""
        inputField.setText("")
        inputField.hint = hint
        inputField.inputType = if (isPassword) {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            android.text.InputType.TYPE_CLASS_NUMBER
        }
        inputSubmit.text = submitLabel
        inputField.requestFocus()
    }

    private fun showPhoneStep() {
        step = Step.PHONE
        inputPane.visibility = View.VISIBLE
        qrPane.visibility = View.GONE
        inputTitle.text = "Sign in with phone number\nCountry code + number, e.g. +91 98765 43210"
        status.text = ""
        inputStatus.text = ""
        inputField.setText("")
        inputField.hint = "+91 98765 43210"
        inputField.inputType = android.text.InputType.TYPE_CLASS_PHONE
        inputSubmit.text = "Send code"
        inputField.requestFocus()
    }

    private fun onSubmit() {
        val current = step ?: return
        val value = inputField.text.toString().trim()
        when (current) {
            Step.CODE -> {
                if (value.length < 4) {
                    inputStatus.text = "That code looks too short"
                    return
                }
                inputStatus.text = "Checking code..."
                inputSubmit.isEnabled = false
                TdLib.submitCode(value)
            }
            Step.PASSWORD -> {
                if (value.isEmpty()) {
                    inputStatus.text = "Enter your 2FA password"
                    return
                }
                inputStatus.text = "Checking password..."
                inputSubmit.isEnabled = false
                TdLib.submitPassword(value)
            }
            Step.PHONE -> {
                val digits = value.filter { it.isDigit() }
                if (digits.length < 6) {
                    inputStatus.text = "Enter the number with country code, e.g. +919876543210"
                    return
                }
                inputStatus.text = "Requesting login code..."
                inputSubmit.isEnabled = false
                TdLib.requestPhoneCode(digits)
            }
        }
        inputSubmit.isEnabled = true
    }

    /** Leaves the input pane; [reRequestQr] re-fires the QR flow after a phone attempt. */
    private fun showQrPane(reRequestQr: Boolean) {
        step = null
        inputPane.visibility = View.GONE
        qrPane.visibility = View.VISIBLE
        status.text = "Preparing QR code..."
        if (reRequestQr) TdLib.fire(TdApi.RequestQrCodeAuthentication())
        qrView.requestFocus()
    }

    private suspend fun buildQrBitmap(link: String): Bitmap = withContext(Dispatchers.Default) {
        val bits = QRCodeWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            QR_SIZE,
            QR_SIZE,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(bits.width * bits.height)
        for (y in 0 until bits.height) {
            for (x in 0 until bits.width) {
                pixels[y * bits.width + x] =
                    if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888)
    }
}
