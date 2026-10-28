package dev.jdtech.jellyfin.player.local.domain

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.libraryApi
import timber.log.Timber

/**
 * Plays an item's theme song quietly in the background while its page is open, like the web
 * client. The song keeps playing when moving to a page with the same song, e.g. from a show to
 * one of its seasons, and fades out when leaving.
 */
@Singleton
class ThemeSongPlayer
@Inject
constructor(
    private val application: Application,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
) {
    private val api = jellyfinApi.api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var songId: UUID? = null
    private var loadJob: Job? = null
    private var fadeJob: Job? = null

    /** Plays the theme song of [itemId], or of its show for seasons and episodes. */
    fun play(itemId: UUID) {
        if (!appPreferences.getValue(appPreferences.themeSongs)) return
        loadJob?.cancel()
        loadJob = scope.launch {
            val song =
                try {
                    api.libraryApi
                        .getThemeSongs(itemId, inheritFromParent = true)
                        .content
                        .items
                        .firstOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                    null
                }
            when {
                song == null -> fadeOut()
                // Same song as on the previous page, keep it going
                song.id == songId && player != null -> fadeTo(VOLUME)
                else -> start(song.id)
            }
        }
    }

    /** Fades out and stops, unless [play] is called again shortly, e.g. by the next page. */
    fun stop() {
        loadJob?.cancel()
        loadJob = scope.launch {
            delay(STOP_DELAY_MS)
            fadeOut()
        }
    }

    private fun start(id: UUID) {
        release()
        songId = id
        val url =
            api.createUrl(
                "/Audio/{itemId}/stream",
                pathParameters = mapOf("itemId" to id),
                queryParameters = mapOf("static" to true, "api_key" to api.accessToken),
            )
        player =
            ExoPlayer.Builder(application)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    // Don't take audio focus, other music keeps playing like with the web client
                    false,
                )
                .build()
                .apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    play()
                }
        fadeTo(VOLUME)
    }

    private fun fadeTo(target: Float) {
        val player = player ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val start = player.volume
            for (step in 1..FADE_STEPS) {
                player.volume = start + (target - start) * step / FADE_STEPS
                delay(FADE_MS / FADE_STEPS)
            }
        }
    }

    private suspend fun fadeOut() {
        fadeTo(0f)
        fadeJob?.join()
        release()
    }

    private fun release() {
        fadeJob?.cancel()
        player?.release()
        player = null
        songId = null
    }

    companion object {
        private const val VOLUME = 0.4f
        private const val FADE_MS = 1_000L
        private const val FADE_STEPS = 20
        private const val STOP_DELAY_MS = 500L
    }
}
