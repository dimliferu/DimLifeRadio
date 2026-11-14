package ru.dimlife.radio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.Html
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray

private val Bg = Color(0xFF050807)
private val Panel = Color(0xFF101914)
private val Panel2 = Color(0xFF17251C)
private val Neon = Color(0xFF45F58B)
private val MainText = Color(0xFFF1FFF5)
private val Muted = Color(0xFF8FA397)
private val Line = Color(0xFF294333)

private val DIMLIFE_STATION = RadioStation(
    name = "DimLife Radio",
    streamUrl = "https://radio.dimlife.ru/live.mp3",
    country = "DimLife",
    genre = "Собственный эфир",
    uuid = "dimlife-radio-main",
    favicon = "https://radio.dimlife.ru/dimlife_radio.png"
)

data class RadioStation(
    val name: String,
    val streamUrl: String,
    val country: String = "",
    val genre: String = "",
    val uuid: String = streamUrl,
    val favicon: String = ""
)

data class TrackHistoryEntry(
    val title: String,
    val artist: String,
    val artwork: String?,
    val playedAt: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = ExoPlayer.Builder(this).build()
        setContent { MaterialTheme { DimLifeRadioApp(player) } }
    }
}

@Composable
private fun DimLifeRadioApp(player: ExoPlayer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("dimlife_radio", 0) }
    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        }
    }
    val trackHistory = remember {
        mutableStateListOf<TrackHistoryEntry>().apply {
            val saved = runCatching { JSONArray(prefs.getString("track_history_v2", "[]")) }.getOrNull()
            if (saved != null) for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                add(TrackHistoryEntry(
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    artwork = item.optString("artwork").takeIf { it.isNotBlank() },
                    playedAt = item.optLong("playedAt")
                ))
            }
        }
    }

    var activeTab by remember { mutableStateOf("Плеер") }
    var current by remember { mutableStateOf(DIMLIFE_STATION) }
    var message by remember { mutableStateOf("Выбери станцию и подключайся к эфиру") }
    var isPlaying by remember { mutableStateOf(false) }
    var trackTitle by remember { mutableStateOf("") }
    var trackArtist by remember { mutableStateOf("") }
    var trackArtwork by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                val newTitle = decodeMetadata(mediaMetadata.title?.toString().orEmpty())
                val newArtist = decodeMetadata(mediaMetadata.artist?.toString().orEmpty())
                val newArtwork = mediaMetadata.artworkUri?.toString()
                trackTitle = newTitle
                trackArtist = newArtist
                trackArtwork = newArtwork
                if (newTitle.isNotBlank() || newArtist.isNotBlank()) {
                    val entry = TrackHistoryEntry(
                        title = newTitle.ifBlank { "Неизвестный трек" },
                        artist = newArtist.ifBlank { "DimLife Radio" },
                        artwork = newArtwork,
                        playedAt = System.currentTimeMillis()
                    )
                    val last = trackHistory.firstOrNull()
                    if (last?.title != entry.title || last.artist != entry.artist) {
                        trackHistory.add(0, entry)
                        while (trackHistory.size > 100) trackHistory.removeAt(trackHistory.lastIndex)
                        val json = JSONArray()
                        trackHistory.forEach { item ->
                            json.put(org.json.JSONObject().apply {
                                put("title", item.title)
                                put("artist", item.artist)
                                put("artwork", item.artwork.orEmpty())
                                put("playedAt", item.playedAt)
                            })
                        }
                        prefs.edit().putString("track_history_v2", json.toString()).apply()
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                message = "Не удалось подключиться к DimLife Radio. Попробуй ещё раз."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun play(station: RadioStation) {
        if (station.streamUrl.isBlank()) {
            current = station
            message = "Собственная трансляция пока недоступна."
            return
        }
        current = station
        trackTitle = ""
        trackArtist = ""
        trackArtwork = null
        message = "Подключаемся к эфиру…"
        player.setMediaItem(MediaItem.fromUri(station.streamUrl))
        player.prepare()
        player.playWhenReady = true
        message = "Прямой эфир"
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = Bg, surface = Panel, primary = Neon, onPrimary = Bg,
        onBackground = MainText, onSurface = MainText
    )) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = Bg) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    "История" -> TrackHistoryScreen(trackHistory)
                    else -> PlayerScreen(
                        current = DIMLIFE_STATION,
                        playing = isPlaying,
                        message = message,
                        title = trackTitle,
                        artist = trackArtist,
                        artwork = trackArtwork,
                        favorite = favorites.contains(DIMLIFE_STATION.uuid),
                        onPlayPause = {
                            if (player.isPlaying) player.pause() else play(DIMLIFE_STATION)
                        },
                        onPrevious = {},
                        onNext = {},
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "DimLife Radio\\nhttps://radio.dimlife.ru/live.mp3\\nСлушаю в DimLife Радио")
                            }
                            context.startActivity(Intent.createChooser(send, "Поделиться DimLife Radio"))
                        },
                        onHistory = { activeTab = "История" },
                        onFavorite = {
                            if (favorites.contains(DIMLIFE_STATION.uuid)) favorites.remove(DIMLIFE_STATION.uuid)
                            else favorites.add(DIMLIFE_STATION.uuid)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                        }
                    )
                }
                }
                BottomNavigation(activeTab) { activeTab = it }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    current: RadioStation,
    playing: Boolean,
    message: String,
    title: String,
    artist: String,
    artwork: String?,
    favorite: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShare: () -> Unit,
    onHistory: () -> Unit,
    onFavorite: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(Neon, Color(0xFF167A43)))),
                contentAlignment = Alignment.Center) {
                Text("DL", color = Bg, fontWeight = FontWeight.Black, fontSize = 19.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("DimLife Радио", color = MainText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Частный InterNet Проект", color = Neon, fontSize = 9.sp, letterSpacing = 0.8.sp)
            }
            Text("● LIVE", color = if (playing) Neon else Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(25.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1A3927), Color(0xFF080E0A), Color(0xFF14251A)))),
            contentAlignment = Alignment.Center
        ) {
            val image = artwork ?: current.favicon
            if (image.isNotBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = "Обложка ${current.name}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DIMLIFE", color = Neon, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("RADIO", color = MainText, fontSize = 17.sp, letterSpacing = 8.sp)
                    Spacer(Modifier.height(22.dp))
                    EqualizerBars()
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(76.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            )
            Text("DIMLIFE RADIO", Modifier.align(Alignment.BottomStart).padding(16.dp),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        }

        Spacer(Modifier.height(22.dp))
        Text(
            title.ifBlank { current.name },
            color = MainText, fontSize = 23.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(5.dp))
        Text(
            artist.ifBlank { listOf(current.country, current.genre).filter { it.isNotBlank() }.joinToString(" • ") },
            color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Neon, Color(0xFFB5FFD0), Neon))))
        Spacer(Modifier.height(8.dp))
        Text(if (playing) "● ПРЯМОЙ ЭФИР" else message.uppercase(),
            color = if (playing) Neon else Muted, fontSize = 10.sp, letterSpacing = 1.1.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            PlayerControl("◀|", "Предыдущая станция", false, onPrevious)
            PlayerControl(if (playing) "Ⅱ" else "▶", "Воспроизведение", true, onPlayPause)
            PlayerControl("|▶", "Следующая станция", false, onNext)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerAction("↗", "Поделиться", onShare)
            PlayerAction("☷", "История", onHistory)
            PlayerAction(if (favorite) "♥" else "♡", if (favorite) "В избранном" else "В избранное", onFavorite)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EqualizerBars() {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(18, 34, 48, 27, 42, 20, 37, 15).forEach { height ->
            Box(Modifier.width(5.dp).height(height.dp).clip(CircleShape).background(Neon))
        }
    }
}

@Composable
private fun PlayerControl(label: String, description: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (primary) 76.dp else 60.dp).clickable { onClick() },
        shape = CircleShape,
        color = if (primary) Neon else Panel2,
        shadowElevation = if (primary) 12.dp else 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (primary) Bg else Neon, fontSize = if (primary) 27.sp else 20.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlayerAction(symbol: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(symbol, color = Neon, fontSize = 25.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun StationScreen(
    tab: String,
    query: String,
    onQuery: (String) -> Unit,
    stations: List<RadioStation>,
    selected: String,
    loading: Boolean,
    favorites: List<String>,
    onSearch: () -> Unit,
    onSelect: (RadioStation) -> Unit,
    onFavorite: (RadioStation) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            when (tab) { "Избранное" -> "Избранные станции"; "История" -> "История прослушивания"; else -> "Мировые станции" },
            color = Neon, fontSize = 24.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        if (tab == "Станции") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, modifier = Modifier.weight(1f),
                    placeholder = { Text("Название станции или жанр", color = Muted, fontSize = 12.sp) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MainText, unfocusedTextColor = MainText,
                        focusedBorderColor = Neon, unfocusedBorderColor = Line,
                        cursorColor = Neon, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch, colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg)) {
                    Text("НАЙТИ", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Популярные радиостанции со всего мира", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Neon)
            }
        } else if (stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (tab) {
                        "Избранное" -> "Здесь появятся станции, добавленные в избранное."
                        "История" -> "История прослушивания пока пуста."
                        else -> "Станции не найдены. Попробуй другой запрос."
                    }, color = Muted, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stations, key = { it.uuid }) { station ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(if (selected == station.uuid) Panel2 else Panel)
                            .clickable { onSelect(station) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (station.favicon.isNotBlank()) {
                            AsyncImage(station.favicon, contentDescription = null,
                                Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        } else {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF1D3026)),
                                contentAlignment = Alignment.Center) {
                                Text("♫", color = Neon, fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(station.name, color = MainText, fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(station.country, station.genre).filter { it.isNotBlank() }.joinToString(" • "),
                                color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (favorites.contains(station.uuid)) "♥" else "♡",
                            Modifier.clickable { onFavorite(station) }.padding(7.dp),
                            color = Neon, fontSize = 21.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Плеер" to "◉", "История" to "◷").forEach { (name, icon) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (selected == name) Color(0xFF193525) else Color.Transparent)
                    .clickable { onSelect(name) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(icon, color = if (selected == name) Neon else Muted, fontSize = 20.sp)
                Text(name, color = if (selected == name) Neon else Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TrackHistoryScreen(history: List<TrackHistoryEntry>) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text("История треков", color = Neon, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Композиции, прозвучавшие в эфире DimLife Radio", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет полученных данных о треках.\nИстория заполнится автоматически, когда радио передаст название композиции.",
                    color = Muted, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(history, key = { "${it.playedAt}-${it.title}-${it.artist}" }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Panel2).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!track.artwork.isNullOrBlank()) {
                            AsyncImage(track.artwork, contentDescription = "Обложка трека",
                                Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        } else {
                            Box(Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)).background(Panel),
                                contentAlignment = Alignment.Center) {
                                Text("♫", color = Neon, fontSize = 23.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = MainText, fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = Muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                            ).format(java.util.Date(track.playedAt)), color = Neon, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun decodeMetadata(value: String): String {
    if (value.isBlank()) return value
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    } else {
        @Suppress("DEPRECATION")
        Html.fromHtml(value).toString().trim()
    }
}
