package com.tgtv.videotv.setup

import android.content.res.AssetManager
import com.tgtv.videotv.td.Credentials
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Tiny LAN web server shown to the phone after scanning the QR code on the TV.
 * Every request must carry the one-time token printed in the QR URL.
 */
class SetupServer(
    port: Int,
    private val token: String,
    private val assets: AssetManager,
    private val onCredentials: (mode: String, apiId: Int, apiHash: String, botToken: String) -> Unit,
    private val status: () -> JSONObject,
) : NanoHTTPD(port) {

    private val hashRegex = Regex("""[0-9a-fA-F]{32}""")
    private val botTokenRegex = Regex("""\d{6,12}:[A-Za-z0-9_-]{30,}""")

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            try {
                session.parseBody(HashMap())
            } catch (_: Exception) {
                return json(false, "Bad request")
            }
        }
        if (session.parms["t"] != token) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain", "Scan the QR code on your TV again.",
            )
        }
        return when {
            session.uri == "/status" -> newFixedLengthResponse(
                Response.Status.OK, "application/json", status().toString(),
            )
            session.uri == "/save" && session.method == Method.POST -> save(session.parms)
            else -> {
                val html = assets.open("setup.html").bufferedReader().use { it.readText() }
                    .replace("__TOKEN__", token)
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
        }
    }

    private fun save(p: Map<String, String>): Response {
        val mode = p["mode"].orEmpty()
        val id = p["api_id"]?.trim()?.toIntOrNull()
        val hash = p["api_hash"]?.trim().orEmpty()
        val bot = p["bot_token"]?.trim().orEmpty()
        return when {
            mode != Credentials.MODE_BOT && mode != Credentials.MODE_USER ->
                json(false, "Choose 'Bot' or 'Phone (QR)' first.")
            id == null || id <= 0 -> json(false, "API ID should be a number.")
            !hashRegex.matches(hash) -> json(false, "API hash should be 32 letters/numbers.")
            mode == Credentials.MODE_BOT && !botTokenRegex.matches(bot) ->
                json(false, "Bot token must look like 123456:ABC-DEF... - copied twice or truncated?")
            else -> {
                onCredentials(mode, id, hash, bot)
                json(true, "Sent to your TV")
            }
        }
    }

    private fun json(ok: Boolean, msg: String) = newFixedLengthResponse(
        Response.Status.OK,
        "application/json",
        JSONObject().put("ok", ok).put("message", msg).toString(),
    )
}
