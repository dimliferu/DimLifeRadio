package ru.dimlife.radio

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
                return metadata.buildUpon()
                    .setTitle(decodeMetadata(metadata.title?.toString().orEmpty()))
                    .setArtist(decodeMetadata(metadata.artist?.toString().orEmpty()))
                    .build()
            }
        }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // Release the session before its player so no controller can address a released player.
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
