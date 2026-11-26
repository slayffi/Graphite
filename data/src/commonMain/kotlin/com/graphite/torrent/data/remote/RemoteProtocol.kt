package com.graphite.torrent.data.remote

import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.SpeedSample
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.settings.RemotePairing
import com.graphite.torrent.core.util.Base64Util
import com.graphite.torrent.data.settings.GraphiteJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of the LAN remote-control channel.
 *
 * The 256-bit key travels only inside the pairing QR code (camera, not network), so there is nothing
 * to intercept. Every message is AES-256-GCM with a random nonce; the authentication tag rejects any
 * modified or foreign message, and requests carry a timestamp and an id against replays.
 */
class RemoteCrypto(key: ByteArray) {
    init {
        require(key.size == 32) { "remote key must be 256 bits" }
    }

    private val secret = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun seal(plain: ByteArray, aad: ByteArray): Envelope {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return Envelope(Base64Util.encodeUrl(nonce), Base64Util.encodeUrl(cipher.doFinal(plain)))
    }

    /** Throws [GraphiteException] with [ErrorKind.REMOTE_AUTH] when the message was not made with our key. */
    fun open(envelope: Envelope, aad: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, Base64Util.decodeUrl(envelope.n)))
        cipher.updateAAD(aad)
        cipher.doFinal(Base64Util.decodeUrl(envelope.c))
    } catch (e: Exception) {
        throw GraphiteException(ErrorKind.REMOTE_AUTH, "message authentication failed", e)
    }

    companion object {
        private const val VERSION = "graphite-remote-v2"

        /** Requests and responses use different associated data, so one can never be passed off as the other. */
        val REQUEST_AAD: ByteArray = "$VERSION|req".toByteArray()

        /** A response only verifies for the request it answers: a recorded "ok" cannot be replayed for another call. */
        fun responseAad(requestId: String): ByteArray = "$VERSION|res|$requestId".toByteArray()

        fun newKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}

@Serializable
data class Envelope(val n: String, val c: String)

@Serializable
data class RpcRequest(val ts: Long, val id: String, val method: String, val params: JsonElement = JsonNull)

@Serializable
data class RpcResponse(val ok: Boolean, val result: JsonElement = JsonNull, val error: TorrentError? = null)

@Serializable
data class RemoteSnapshot(
    val torrents: List<TorrentItem>,
    val session: SessionSnapshot,
    val history: List<SpeedSample>,
    val noticeSeq: Long,
    val notices: List<ControllerNotice>,
    val serverName: String,
)

@Serializable data class IdsParam(val ids: List<String>, val deleteFiles: Boolean = false)
@Serializable data class IdParam(val id: String)
@Serializable data class UriParam(val uri: String)
@Serializable data class DataParam(val data: String)
@Serializable data class MoveParam(val id: String, val target: String)
@Serializable data class PrioritiesParam(val id: String, val priorities: Map<Int, FilePriority>)
@Serializable data class FlagParam(val id: String, val enabled: Boolean)
@Serializable data class PositionParam(val id: String, val position: Int)
@Serializable data class CategoryParam(val ids: List<String>, val category: String?)
@Serializable data class TagsParam(val id: String, val tags: List<String>)
@Serializable data class UrlsParam(val id: String, val urls: List<String>)
@Serializable data class PathParam(val path: String)
@Serializable data class SinceParam(val since: Long)

/** "graphite://pair?v=1&n=PC&p=48650&h=192.168.1.5,10.0.0.2&k=<base64url key>" */
object PairingCode {
    private const val PREFIX = "graphite://pair?"

    fun format(name: String, hosts: List<String>, port: Int, key: ByteArray): String =
        PREFIX + listOf(
            "v" to "1",
            "n" to name,
            "p" to port.toString(),
            "h" to hosts.joinToString(","),
            "k" to Base64Util.encodeUrl(key),
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    fun parse(text: String): RemotePairing? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val query = runCatching { URI(t).rawQuery }.getOrNull() ?: t.removePrefix(PREFIX)
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
        }.toMap()
        if (params["v"] != "1") return null
        val key = params["k"] ?: return null
        if (runCatching { Base64Util.decodeUrl(key) }.getOrNull()?.size != 32) return null
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val hosts = params["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return null
        return RemotePairing(name = params["n"].orEmpty().ifBlank { "PC" }, hosts = hosts, port = port, keyBase64 = key)
    }
}

internal val RemoteJson = GraphiteJson
