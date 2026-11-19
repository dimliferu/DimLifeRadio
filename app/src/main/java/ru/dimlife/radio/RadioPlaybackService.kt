package ru.dimlife.radio

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.audiofx.Equalizer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class RadioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepStopTask: Runnable? = null
    private var equalizer: Equalizer? = null

    companion object {
        const val ACTION_START_SLEEP_TIMER = "ru.dimlife.radio.action.START_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "ru.dimlife.radio.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
        const val ACTION_EQUALIZER_ENABLED = "ru.dimlife.radio.action.EQUALIZER_ENABLED"
        const val ACTION_EQUALIZER_BAND = "ru.dimlife.radio.action.EQUALIZER_BAND"
        const val EXTRA_EQUALIZER_ENABLED = "equalizer_enabled"
        const val EXTRA_EQUALIZER_BAND = "equalizer_band"
        const val EXTRA_EQUALIZER_LEVEL = "equalizer_level"
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
            ACTION_EQUALIZER_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_EQUALIZER_ENABLED, false)
                getSharedPreferences("dimlife_radio_eq", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
                try { equalizer?.enabled = enabled } catch (_: Exception) { }
            }
            ACTION_EQUALIZER_BAND -> {
                val band = intent.getIntExtra(EXTRA_EQUALIZER_BAND, -1)
                val level = intent.getIntExtra(EXTRA_EQUALIZER_LEVEL, 0)
                val prefs = getSharedPreferences("dimlife_radio_eq", MODE_PRIVATE)
                prefs.edit().putInt("band_$band", level).apply()
                try {
                    val eq = equalizer
                    if (eq != null && band >= 0 && band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt()).toShort())
                } catch (_: Exception) { }
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
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEqualizer(audioSessionId)
            }

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


    private fun attachEqualizer(audioSessionId: Int) {
        try {
            equalizer?.release()
            val prefs = getSharedPreferences("dimlife_radio_eq", MODE_PRIVATE)
            val effect = Equalizer(0, audioSessionId)
            val count = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            val editor = prefs.edit()
                .putInt("band_count", count)
                .putInt("min_level", range[0].toInt())
                .putInt("max_level", range[1].toInt())
            for (i in 0 until count) {
                val center = effect.getCenterFreq(i.toShort())
                editor.putInt("band_freq_$i", center)
                val level = prefs.getInt("band_$i", 0).coerceIn(range[0].toInt(), range[1].toInt())
                effect.setBandLevel(i.toShort(), level.toShort())
            }
            editor.putBoolean("available", true).apply()
            effect.enabled = prefs.getBoolean("enabled", false)
            equalizer = effect
        } catch (_: Exception) {
            equalizer?.release()
            equalizer = null
            getSharedPreferences("dimlife_radio_eq", MODE_PRIVATE).edit().putBoolean("available", false).apply()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        sleepStopTask?.let(sleepHandler::removeCallbacks)
        sleepStopTask = null
        equalizer?.release()
        equalizer = null
        // Release the session before its player so no controller can address a released player.
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
