package com.nuvio.app.features.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID

internal class CastLocalMediaRelay private constructor(
    host: String,
    private val videoUrl: URL?,
    private val videoType: String,
    private val subtitleBytes: ByteArray?,
) : NanoHTTPD(host, 0), AutoCloseable {
    private val path = "/" + UUID.randomUUID().toString()
    private val origin get() = "http://$hostname:$listeningPort"
    val castVideoUrl get() = "$origin$path/video"
    val castSubtitleUrl get() = "$origin$path/subtitle.vtt"

    override fun serve(session: IHTTPSession): Response {
        val isVideo = session.uri == "$path/video" && videoUrl != null
        val isSubtitle = session.uri == "$path/subtitle.vtt" && subtitleBytes != null
        if (!isVideo && !isSubtitle) return response(Response.Status.NOT_FOUND)
        val method = session.method
        return when {
            method == Method.OPTIONS -> cors(response(Response.Status.NO_CONTENT))
            method != Method.GET && method != Method.HEAD ->
                cors(response(Response.Status.METHOD_NOT_ALLOWED))
            isSubtitle -> {
                val data = checkNotNull(subtitleBytes)
                cors(newFixedLengthResponse(
                    Response.Status.OK, "text/vtt; charset=utf-8", data.inputStream(), data.size.toLong(),
                ))
            }
            else -> cors(relayVideo(method, session.headers["range"]))
        }
    }

    private fun relayVideo(method: Method, range: String?): Response {
        if (range != null && !SINGLE_RANGE.matches(range)) {
            return response(Response.Status.RANGE_NOT_SATISFIABLE)
        }
        val connection = checkNotNull(videoUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method.name
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) connection.setRequestProperty("Range", range)
            val status = Response.Status.lookup(connection.responseCode)
            if (status != Response.Status.OK && status != Response.Status.PARTIAL_CONTENT) {
                val rejectedRange = status == Response.Status.RANGE_NOT_SATISFIABLE
                return response(if (rejectedRange) Response.Status.RANGE_NOT_SATISFIABLE else Response.Status.SERVICE_UNAVAILABLE)
                    .also { if (rejectedRange) copyRangeHeaders(connection, it) }
                    .also { connection.disconnect() }
            }
            val length = connection.getHeaderFieldLong("Content-Length", -1)
            val body = if (method == Method.HEAD) {
                ByteArray(0).inputStream()
            } else {
                object : FilterInputStream(connection.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            }
            return if (length >= 0) {
                newFixedLengthResponse(status, videoType, body, length)
            } else {
                newChunkedResponse(status, videoType, body)
            }.also {
                copyRangeHeaders(connection, it)
                if (method == Method.HEAD) connection.disconnect()
            }
        } catch (error: java.io.IOException) {
            connection.disconnect()
            return response(Response.Status.SERVICE_UNAVAILABLE)
        }
    }

    private fun copyRangeHeaders(connection: HttpURLConnection, response: Response) {
        for (header in listOf("Accept-Ranges", "Content-Range")) {
            connection.getHeaderField(header)?.let { response.addHeader(header, it) }
        }
    }

    private fun cors(response: Response) = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Range")
        addHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Length, Content-Range")
        addHeader("Cache-Control", "no-store")
    }

    private fun response(status: Response.Status) = newFixedLengthResponse(status, MIME_PLAINTEXT, "")

    override fun close() = stop()

    companion object {
        private val SINGLE_RANGE = Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")

        fun start(
            context: Context,
            localVideoUrl: String?,
            contentType: String,
            subtitles: ByteArray?,
        ): CastLocalMediaRelay {
            val target = localVideoUrl?.let {
                val uri = URI(it)
                require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]"))
                require(uri.userInfo == null)
                uri.toURL()
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = checkNotNull(manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }) { "Wi-Fi network unavailable" }
            val address = checkNotNull(manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            }?.address?.hostAddress) { "Wi-Fi IPv4 address unavailable" }
            return CastLocalMediaRelay(address, target, contentType, subtitles).apply {
                start(SOCKET_READ_TIMEOUT, true)
            }
        }
    }
}

internal fun castContentType(url: String, filename: String?): String? {
    val path = (filename?.takeIf { it.isNotBlank() } ?: url)
        .substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> "application/x-mpegURL"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mkv") || path.endsWith(".avi") -> null
        else -> "video/mp4"
    }
}

internal fun castSubtitleVtt(cues: List<SubtitleSyncCue>, delayMs: Int): ByteArray = buildString {
    append("WEBVTT\n\n")
    fun timestamp(time: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d.%03d", time / 3_600_000, time / 60_000 % 60, time / 1_000 % 60, time % 1_000,
    )
    for (cue in cues) {
        val start = (cue.startTimeMs + delayMs).coerceAtLeast(0)
        val end = cue.endTimeMs + delayMs
        if (end <= start) continue
        append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n')
        append(cue.text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        append("\n\n")
    }
}.encodeToByteArray()
