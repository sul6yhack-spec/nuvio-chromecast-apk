package com.nuvio.app.features.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
actual fun PlatformCastAction(modifier: Modifier) {
    val context = LocalContext.current
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MediaRouteButton(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(dp(viewContext, 48), dp(viewContext, 48))
                CastButtonFactory.setUpMediaRouteButton(viewContext, this)
                isEnabled = castContext != null
            }
        },
        update = { button -> button.isEnabled = castContext != null },
    )
}

@Composable
actual fun PlatformCastSessionEffect(
    url: String,
    title: String,
    positionMs: Long,
    torrentFileName: String?,
    relayLocalVideo: Boolean,
    subtitle: AddonSubtitle?,
    subtitleDelayMs: Int,
    canCast: Boolean,
    wasPlaying: Boolean,
    onPauseLocal: () -> Unit,
    onRestoreLocal: (positionMs: Long, resumePlayback: Boolean) -> Unit,
    onUnsupported: () -> Unit,
) {
    val context = LocalContext.current
    val current = rememberUpdatedState(
        CastRequest(
            url, title, positionMs, torrentFileName, relayLocalVideo, subtitle, subtitleDelayMs,
            canCast, wasPlaying, onPauseLocal, onRestoreLocal, onUnsupported,
        ),
    )
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }

    val scope = rememberCoroutineScope()
    DisposableEffect(castContext, url) {
        if (castContext == null) return@DisposableEffect onDispose {}
        var remoteClient: RemoteMediaClient? = null
        var localPlaybackShouldResume = false
        var localPlaybackWasPaused = false
        var castSession: CastSession? = null
        var prepareJob: Job? = null
        var relay: CastLocalMediaRelay? = null

        fun restoreLocalPlayback() {
            prepareJob?.cancel()
            prepareJob = null
            relay?.close()
            relay = null
            castSession = null
            if (localPlaybackWasPaused && current.value.url == url) {
                val request = current.value
                val remotePosition = remoteClient?.approximateStreamPosition?.coerceAtLeast(0L)
                    ?: request.positionMs
                request.onRestoreLocal(remotePosition, localPlaybackShouldResume)
            }
            localPlaybackWasPaused = false
            remoteClient = null
        }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                val request = current.value
                val type = castContentType(request.url, request.torrentFileName)
                val client = session.remoteMediaClient
                if (!request.canCast || type == null || client == null) {
                    request.onUnsupported()
                    castContext.sessionManager.endCurrentSession(true)
                    return
                }

                castSession = session
                prepareJob = scope.launch {
                    val subtitle = request.subtitle
                    val subtitleData = subtitle?.let {
                        withTimeoutOrNull(5_000L) {
                            try {
                                val body = httpGetTextWithHeaders(it.url, emptyMap())
                                val cues = PlayerSubtitleCueParser.parse(body, it.url)
                                if (cues.isEmpty()) null else castSubtitleVtt(cues, request.subtitleDelayMs)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    relay = if (request.relayLocalVideo || subtitleData != null) {
                        runCatching {
                            CastLocalMediaRelay.start(
                                context, request.url.takeIf { request.relayLocalVideo }, type, subtitleData,
                            )
                        }.getOrNull()
                    } else null
                    if (request.relayLocalVideo && relay == null) {
                        request.onUnsupported()
                        castContext.sessionManager.endCurrentSession(true)
                        return@launch
                    }

                    val subtitleTrack = relay?.takeIf { subtitleData != null }?.let { server ->
                        MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                            .setContentId(server.castSubtitleUrl)
                            .setContentType("text/vtt")
                            .setName(subtitle?.display.orEmpty())
                            .setLanguage(subtitle?.language.orEmpty())
                            .build()
                    }
                    val castUrl = if (request.relayLocalVideo) checkNotNull(relay).castVideoUrl else request.url
                    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                        putString(MediaMetadata.KEY_TITLE, request.title)
                    }
                    val media = MediaInfo.Builder(castUrl)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType(type)
                        .setMetadata(metadata)
                        .apply { if (subtitleTrack != null) setMediaTracks(listOf(subtitleTrack)) }
                        .build()

                    val latest = current.value
                    remoteClient = client
                    localPlaybackShouldResume = latest.wasPlaying
                    latest.onPauseLocal()
                    localPlaybackWasPaused = true
                    client.load(
                        MediaLoadRequestData.Builder()
                            .setMediaInfo(media)
                            .setAutoplay(latest.wasPlaying)
                            .setCurrentTime(latest.positionMs.coerceAtLeast(0L))
                            .apply { if (subtitleTrack != null) setActiveTrackIds(longArrayOf(subtitleTrack.id)) }
                            .build(),
                    ).setResultCallback { result ->
                        if (castSession == session && !result.status.isSuccess) {
                            request.onUnsupported()
                            castContext.sessionManager.endCurrentSession(true)
                        }
                    }
                }
            }

            override fun onSessionEnding(session: CastSession) = restoreLocalPlayback()
            override fun onSessionEnded(session: CastSession, error: Int) = restoreLocalPlayback()
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = restoreLocalPlayback()
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = restoreLocalPlayback()
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }

        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            val wasCasting = castSession != null
            restoreLocalPlayback()
            if (wasCasting) castContext.sessionManager.endCurrentSession(true)
        }
    }
}

private data class CastRequest(
    val url: String,
    val title: String,
    val positionMs: Long,
    val torrentFileName: String?,
    val relayLocalVideo: Boolean,
    val subtitle: AddonSubtitle?,
    val subtitleDelayMs: Int,
    val canCast: Boolean,
    val wasPlaying: Boolean,
    val onPauseLocal: () -> Unit,
    val onRestoreLocal: (Long, Boolean) -> Unit,
    val onUnsupported: () -> Unit,
)

private fun dp(context: android.content.Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
