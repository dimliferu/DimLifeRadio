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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dimlife_radio", 0) }
    val stations = remember { mutableStateListOf(DIMLIFE_STATION) }
    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        }
    }
    val history = remember {
        mutableStateListOf<String>().apply {
            val saved = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrNull()
            if (saved != null) for (i in 0 until saved.length()) add(saved.optString(i))
        }
    }

    var query by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("Плеер") }
    var current by remember { mutableStateOf(DIMLIFE_STATION) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Выбери станцию и подключайся к эфиру") }
    var isPlaying by remember { mutableStateOf(false) }
    var trackTitle by remember { mutableStateOf("") }
    var trackArtist by remember { mutableStateOf("") }
    var trackArtwork by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                trackTitle = decodeMetadata(mediaMetadata.title?.toString().orEmpty())
                trackArtist = decodeMetadata(mediaMetadata.artist?.toString().orEmpty())
                trackArtwork = mediaMetadata.artworkUri?.toString()
            }
            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                message = "Не удалось подключиться. Попробуй другую станцию."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun rememberStation(station: RadioStation) {
        history.remove(station.name)
        history.add(0, station.name)
        while (history.size > 30) history.removeAt(history.lastIndex)
        val json = JSONArray()
        history.forEach { json.put(it) }
        prefs.edit().putString("history", json.toString()).apply()
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
        rememberStation(station)
        message = "Прямой эфир"
    }

    fun loadStations(search: String = "") {
        loading = true
        scope.launch {
            try {
                val found = fetchStations(search)
                stations.clear()
                stations.add(DIMLIFE_STATION)
                stations.addAll(found.filterNot { it.uuid == DIMLIFE_STATION.uuid })
                message = if (found.isEmpty()) "Станции не найдены." else "Доступно станций: ${found.size}"
            } catch (_: Exception) {
                message = "Каталог временно недоступен. Собственная станция DimLife остаётся доступной."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadStations() }

    val visibleStations = when (activeTab) {
        "Избранное" -> stations.distinctBy { it.uuid }.filter { favorites.contains(it.uuid) }
        "История" -> history.mapNotNull { name -> stations.firstOrNull { it.name == name } }
        else -> stations.distinctBy { it.uuid }
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = Bg, surface = Panel, primary = Neon, onPrimary = Bg,
        onBackground = MainText, onSurface = MainText
    )) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = Bg) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    "Плеер" -> PlayerScreen(
                        current = current,
                        playing = isPlaying,
                        message = message,
                        title = trackTitle,
                        artist = trackArtist,
                        artwork = trackArtwork,
                        favorite = favorites.contains(current.uuid),
                        onPlayPause = {
                            if (player.isPlaying) player.pause() else play(current)
                        },
                        onPrevious = {
                            val index = stations.indexOfFirst { it.uuid == current.uuid }
                            if (stations.isNotEmpty()) play(stations[(index - 1).coerceAtLeast(0)])
                        },
                        onNext = {
                            val index = stations.indexOfFirst { it.uuid == current.uuid }
                            if (stations.isNotEmpty()) play(stations[(index + 1).coerceAtMost(stations.lastIndex)])
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${current.name}\n${current.streamUrl}\nСлушаю в DimLife Радио")
                            }
                            context.startActivity(Intent.createChooser(send, "Поделиться станцией"))
                        },
                        onHistory = { activeTab = "История" },
                        onFavorite = {
                            if (favorites.contains(current.uuid)) favorites.remove(current.uuid)
                            else favorites.add(current.uuid)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                        }
                    )
                    "Станции", "Избранное", "История" -> StationScreen(
                        tab = activeTab,
                        query = query,
                        onQuery = { query = it },
                        stations = visibleStations,
                        selected = current.uuid,
                        loading = loading,
                        favorites = favorites,
                        onSearch = { loadStations(query.trim()) },
                        onSelect = { play(it); activeTab = "Плеер" },
                        onFavorite = {
                            if (favorites.contains(it.uuid)) favorites.remove(it.uuid)
                            else favorites.add(it.uuid)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                        }
                    )
                    else -> PlayerScreen(
                        current, isPlaying, message, trackTitle, trackArtist, trackArtwork,
                        favorites.contains(current.uuid), { if (player.isPlaying) player.pause() else play(current) },
                        {}, {}, {}, { activeTab = "История" }, {}
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
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 5.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("Плеер" to "◉", "Станции" to "♫", "Избранное" to "♥", "История" to "◷").forEach { (name, icon) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (selected == name) Color(0xFF193525) else Color.Transparent)
                    .clickable { onSelect(name) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(icon, color = if (selected == name) Neon else Muted, fontSize = 19.sp)
                Text(name, color = if (selected == name) Neon else Muted, fontSize = 9.sp)
            }
        }
    }
}

private suspend fun fetchStations(search: String): List<RadioStation> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(search, "UTF-8")
    val endpoint = if (search.isBlank()) {
        "https://de1.api.radio-browser.info/json/stations?limit=100&hidebroken=true&order=clickcount&reverse=true"
    } else {
        "https://de1.api.radio-browser.info/json/stations/search?name=$encoded&limit=100&hidebroken=true&order=clickcount&reverse=true"
    }
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 15000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "DimLifeRadio/0.1.3 (Android)")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val array = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                if (!stream.startsWith("http://") && !stream.startsWith("https://")) continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(RadioStation(
                    name = name,
                    streamUrl = stream,
                    country = item.optString("country"),
                    genre = item.optString("tags").split(",").firstOrNull().orEmpty().trim(),
                    uuid = item.optString("stationuuid").ifBlank { stream },
                    favicon = item.optString("favicon")
                ))
            }
        }.distinctBy { it.uuid }
    } finally {
        connection.disconnect()
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
