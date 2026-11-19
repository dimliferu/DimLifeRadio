package ru.dimlife.radio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val ACTION_WIDGET_TOGGLE = "ru.dimlife.radio.WIDGET_TOGGLE"
private const val TAG_WIDGET = "DimLifeRadioWidget"
private const val FALLBACK_ARTWORK = "https://radio.dimlife.ru/dimlife_radio.png"
private val artworkExecutor = Executors.newFixedThreadPool(2)
private val artworkCache = ConcurrentHashMap<String, Bitmap>()
private val widgetArtworkKeys = ConcurrentHashMap<Int, String>()

object RadioWidgetUpdater {
    fun updateAll(context: Context, player: Player? = null) {
        updateProvider(context, CompactRadioWidgetProvider::class.java, R.layout.widget_compact, player)
        updateProvider(context, ExpandedRadioWidgetProvider::class.java, R.layout.widget_expanded, player)
    }

    private fun updateProvider(
        context: Context,
        provider: Class<out AppWidgetProvider>,
        layout: Int,
        player: Player?
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        val metadata = player?.mediaMetadata
        val title = metadata?.title?.toString()?.let(::decodeMetadata)?.takeIf { it.isNotBlank() } ?: "DimLife Radio"
        val artist = metadata?.artist?.toString()?.let(::decodeMetadata)?.takeIf { it.isNotBlank() } ?: "Слушайте прямой эфир"
        val playing = player?.isPlaying == true
        val artworkBytes = metadata?.artworkData
        val artworkUrl = metadata?.artworkUri?.toString()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val artworkKey = if (artworkBytes != null) "data:${artworkBytes.contentHashCode()}" else (artworkUrl ?: FALLBACK_ARTWORK)
        ids.forEach { id ->
            widgetArtworkKeys[id] = artworkKey
            val views = RemoteViews(context.packageName, layout)
            views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_launcher)
            artworkCache[artworkKey]?.let { views.setImageViewBitmap(R.id.widget_artwork, it) }
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setTextViewText(R.id.widget_play, if (playing) "Ⅱ" else "▶")
            views.setOnClickPendingIntent(
                R.id.widget_play,
                PendingIntent.getBroadcast(
                    context,
                    7101,
                    Intent(context, RadioWidgetActionReceiver::class.java).setAction(ACTION_WIDGET_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    7102,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            manager.updateAppWidget(id, views)
            if (!artworkCache.containsKey(artworkKey)) {
                artworkExecutor.execute {
                    try {
                        val bitmap = if (artworkBytes != null) {
                            BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
                        } else {
                            val connection = URL(artworkUrl ?: FALLBACK_ARTWORK).openConnection()
                            connection.connectTimeout = 5000
                            connection.readTimeout = 7000
                            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                        }
                        if (bitmap != null) {
                            artworkCache[artworkKey] = bitmap
                            if (artworkCache.size > 20) {
                                val staleKey = artworkCache.keys.firstOrNull { it != artworkKey }
                                if (staleKey != null) artworkCache.remove(staleKey)
                            }
                            if (widgetArtworkKeys[id] == artworkKey) {
                                val updatedViews = RemoteViews(context.packageName, layout)
                                updatedViews.setImageViewBitmap(R.id.widget_artwork, bitmap)
                                manager.partiallyUpdateAppWidget(id, updatedViews)
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG_WIDGET, "Не удалось загрузить обложку виджета", error)
                    }
                }
            }
        }
    }
}

class CompactRadioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RadioWidgetUpdater.updateAll(context)
    }
}

class ExpandedRadioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RadioWidgetUpdater.updateAll(context)
    }
}

class RadioWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET_TOGGLE) return

        val pendingResult = goAsync()
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future: ListenableFuture<MediaController>

        try {
            future = MediaController.Builder(context.applicationContext, token).buildAsync()
        } catch (error: Exception) {
            Log.e(TAG_WIDGET, "Не удалось создать подключение к радиосервису", error)
            pendingResult.finish()
            return
        }

        future.addListener({
            try {
                val controller = future.get()
                if (controller.isPlaying || controller.playWhenReady) {
                    controller.pause()
                } else {
                    if (controller.currentMediaItem == null) {
                        controller.setMediaItem(MediaItem.fromUri("https://radio.dimlife.ru/live.mp3"))
                        controller.prepare()
                    }
                    controller.play()
                }
                RadioWidgetUpdater.updateAll(context, controller)
            } catch (error: Exception) {
                Log.e(TAG_WIDGET, "Ошибка управления радио из виджета", error)
            } finally {
                MediaController.releaseFuture(future)
                pendingResult.finish()
            }
        }, Executor { command -> Handler(Looper.getMainLooper()).post(command) })
    }
}
