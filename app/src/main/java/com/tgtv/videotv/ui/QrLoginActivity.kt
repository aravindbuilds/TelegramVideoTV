package com.tgtv.videotv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
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

/** Shows the "link desktop device" QR code and waits until TDLib signs in. */
class QrLoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_login)

        val qrView = findViewById<ImageView>(R.id.qrCodeView)
        val status = findViewById<TextView>(R.id.qrStatus)

        if (!TdLib.isStarted && Credentials.hasApi(this)) {
            TdLib.start(applicationContext)
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
            TdLib.authErrors.collect { status.text = it }
        }

        lifecycleScope.launch {
            TdLib.authState.collect { st ->
                status.text = when (st) {
                    null -> "Connecting to Telegram..."
                    is TdApi.AuthorizationStateWaitTdlibParameters -> "Connecting to Telegram..."
                    is TdApi.AuthorizationStateWaitPhoneNumber -> "Preparing QR code..."
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                        getString(R.string.qr_instructions)
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
                    is TdApi.AuthorizationStateWaitEmailAddress ->
                        "Email verification needed - use phone login instead"
                    is TdApi.AuthorizationStateWaitCode ->
                        "Code required - use phone login instead"
                    is TdApi.AuthorizationStateWaitPassword ->
                        "2FA password required - use phone login instead"
                    else -> st.javaClass.simpleName
                }
            }
        }
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
