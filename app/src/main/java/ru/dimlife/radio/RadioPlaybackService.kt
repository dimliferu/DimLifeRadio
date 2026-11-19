package ru.dimlife.radio

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class RadioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepStopTask: Runnable? = null

    companion object {
        const val ACTION_START_SLEEP_TIMER = "ru.dimlife.radio.action.START_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "ru.dimlife.radio.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0)
                sleepStopTask?.let(sleepHandler::removeCallbacks)
                if (minutes > 0) {
                    val task = Runnable {
                        if (::player.isInitialized) player.pause()
                        sleepStopTask = null
                    }
                    sleepStopTask = task
                    sleepHandler.postDelayed(task, minutes * 60_000L)
                }
            }
            ACTION_CANCEL_SLEEP_TIMER -> {
                sleepStopTask?.let(sleepHandler::removeCallbacks)
                sleepStopTask = null
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                RadioWidgetUpdater.updateAll(this@RadioPlaybackService, player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                RadioWidgetUpdater.updateAll(this@RadioPlaybackService, player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                RadioWidgetUpdater.updateAll(this@RadioPlaybackService, player)
            }
        })
        mediaSession = MediaSession.Builder(this, object : ForwardingPlayer(player) {
            override fun getMediaMetadata(): MediaMetadata {
                val metadata = super.getMediaMetadata()
                val builder = metadata.buildUpon()
                    .setTitle(decodeMetadata(metadata.title?.toString().orEmpty()))
                    .setArtist(decodeMetadata(metadata.artist?.toString().orEmpty()))
                // Keep track artwork when supplied; use DimLife Radio artwork only as fallback.
                if (metadata.artworkUri == null && metadata.artworkData == null) {
                    builder.setArtworkUri(android.net.Uri.parse("https://radio.dimlife.ru/dimlife_radio.png"))
                }
                return builder.build()
            }
        }).build()
    }


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        sleepStopTask?.let(sleepHandler::removeCallbacks)
        sleepStopTask = null
        // Release the session before its player so no controller can address a released player.
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
