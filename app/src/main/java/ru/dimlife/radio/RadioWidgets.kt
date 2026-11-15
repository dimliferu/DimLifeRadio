package ru.dimlife.radio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import android.widget.RemoteViews
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

private const val ACTION_WIDGET_TOGGLE = "ru.dimlife.radio.WIDGET_TOGGLE"
private const val ACTION_WIDGET_OPEN = "ru.dimlife.radio.WIDGET_OPEN"

object RadioWidgetUpdater {
    fun updateAll(context: Context, player: androidx.media3.common.Player? = null) {
        updateProvider(context, CompactRadioWidgetProvider::class.java, R.layout.widget_compact, player)
        updateProvider(context, ExpandedRadioWidgetProvider::class.java, R.layout.widget_expanded, player)
    }

    private fun updateProvider(
        context: Context,
        provider: Class<out AppWidgetProvider>,
        layout: Int,
        player: androidx.media3.common.Player?
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        val title = player?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "DimLife Radio"
        val artist = player?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Частный InterNet Проект"
        val playing = player?.isPlaying == true
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setTextViewText(R.id.widget_play, if (playing) "Ⅱ" else "▶")
            views.setOnClickPendingIntent(R.id.widget_play, PendingIntent.getBroadcast(
                context, 7101, Intent(context, RadioWidgetActionReceiver::class.java).setAction(ACTION_WIDGET_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 7102, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            manager.updateAppWidget(id, views)
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
        val pending = goAsync()
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    if (controller.currentMediaItem == null) {
                        controller.setMediaItem(MediaItem.fromUri("https://radio.dimlife.ru/live.mp3"))
                        controller.prepare()
                    }
                    controller.play()
                }
                RadioWidgetUpdater.updateAll(context, controller)
                controller.release()
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }, Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    }
}
