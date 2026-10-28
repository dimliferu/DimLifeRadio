package ru.dimlife.radio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Bg = Color(0xFF070B0A)
private val Panel = Color(0xFF101916)
private val Neon = Color(0xFF39FF88)
private val TextMain = Color(0xFFE9FFF1)
private val Muted = Color(0xFF8AA397)
private val Border = Color(0xFF234234)

data class RadioStation(
    val name: String,
    val streamUrl: String,
    val country: String = "",
    val genre: String = "",
    val uuid: String = streamUrl
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = ExoPlayer.Builder(this).build()
        setContent {
            MaterialTheme {
                DimLifeRadioApp(player)
            }
        }
    }
}

@Composable
private fun DimLifeRadioApp(player: ExoPlayer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val stations = remember { mutableStateListOf<RadioStation>() }
    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(context.getSharedPreferences("dimlife_radio", 0)
                .getStringSet("favorites", emptySet()) ?: emptySet())
        }
    }
    val customStations = remember {
        mutableStateListOf<RadioStation>().apply {
            addAll(loadCustomStations(context))
        }
    }
    var query by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("Популярные") }
    var current by remember { mutableStateOf<RadioStation?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Подключись к станции и слушай весь мир") }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                message = "Не удалось воспроизвести поток. Проверь ссылку или выбери другую станцию."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun play(station: RadioStation) {
        current = station
        message = "Подключаемся к эфиру…"
        player.setMediaItem(MediaItem.fromUri(station.streamUrl))
        player.prepare()
        player.playWhenReady = true
        message = "Сейчас в эфире"
    }

    fun loadStations(search: String = "") {
        loading = true
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { fetchStations(search) }
                stations.clear()
                stations.addAll(found)
                message = if (found.isEmpty()) "Станций не найдено. Попробуй другой запрос." else "Найдено станций: ${found.size}"
            } catch (e: Exception) {
                message = "Не удалось загрузить каталог. Проверь интернет и попробуй ещё раз."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadStations() }

    val visibleStations = when (activeTab) {
        "Избранное" -> (stations + customStations).distinctBy { it.uuid }.filter { favorites.contains(it.uuid) }
        "Мои URL" -> customStations.toList()
        else -> (stations + customStations).distinctBy { it.uuid }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).background(Neon, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("DL", color = Bg, fontWeight = FontWeight.Black, fontSize = 19.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("DIMLIFE", color = TextMain, fontWeight = FontWeight.Black, fontSize = 21.sp, letterSpacing = 2.sp)
                    Text("РАДИО • МИР В ТВОИХ НАУШНИКАХ", color = Neon, fontSize = 9.sp, letterSpacing = 1.1.sp)
                }
                Box(Modifier.background(Panel, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("● LIVE", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))

            Column(
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Text("СЕЙЧАС ИГРАЕТ", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(current?.name ?: "Выбери свою волну", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.let { listOf(it.country, it.genre).filter { v -> v.isNotBlank() }.joinToString(" • ") }.orEmpty().ifBlank { "DimLife Radio — твоя музыка, твой мир" }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (player.isPlaying) player.pause() else if (current != null) player.play() },
                        colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isPlaying) "Ⅱ  ПАУЗА" else "▶  СЛУШАТЬ", fontWeight = FontWeight.Black)
                    }
                    TextButton(
                        onClick = {
                            current?.let { station ->
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, "${station.name}\n${station.streamUrl}\nСлушаю в DimLife Радио")
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Поделиться станцией"))
                            }
                        },
                        enabled = current != null
                    ) { Text("↗ Поделиться", color = Neon) }
                }
                Text(message, color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Популярные", "Избранное", "Мои URL").forEach { tab ->
                    val selected = activeTab == tab
                    Text(
                        tab,
                        color = if (selected) Bg else Muted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).background(if (selected) Neon else Panel, RoundedCornerShape(12.dp))
                            .clickable { activeTab = tab }.padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (activeTab != "Мои URL") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Станция, страна, жанр", color = Muted, fontSize = 13.sp) },
                        singleLine = true,
                        colors = fieldColors(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { loadStations(query.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("НАЙТИ", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            } else {
                Text("ДОБАВИТЬ СВОЮ СТАНЦИЮ", color = Neon, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = customName, onValueChange = { customName = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Название станции", color = Muted) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://адрес-потока/stream", color = Muted) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val name = customName.trim()
                        val url = customUrl.trim()
                        if (name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                            val station = RadioStation(name, url, genre = "Моя станция", uuid = url)
                            customStations.removeAll { it.uuid == url }
                            customStations.add(0, station)
                            saveCustomStations(context, customStations)
                            customName = ""
                            customUrl = ""
                            message = "Станция сохранена на этом устройстве"
                        } else message = "Укажи название и корректный HTTP(S)-адрес потока"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ ДОБАВИТЬ СТАНЦИЮ", fontWeight = FontWeight.Black) }
            }

            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Neon)
                }
            } else if (visibleStations.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (activeTab == "Избранное") "Здесь появятся станции, которые ты добавишь в избранное." else "Пока нет станций. Добавь ссылку на поток во вкладке «Мои URL».", color = Muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleStations, key = { it.uuid }) { station ->
                        StationRow(
                            station = station,
                            isCurrent = current?.uuid == station.uuid,
                            favorite = favorites.contains(station.uuid),
                            onPlay = { play(station) },
                            onFavorite = {
                                if (favorites.contains(station.uuid)) favorites.remove(station.uuid) else favorites.add(station.uuid)
                                context.getSharedPreferences("dimlife_radio", 0).edit().putStringSet("favorites", favorites.toSet()).apply()
                            },
                            onDelete = if (activeTab == "Мои URL") ({
                                customStations.remove(station)
                                saveCustomStations(context, customStations)
                            }) else null
                        )
                    }
                }
            }
            Text("DIMLIFE • НЕЗАВИСИМАЯ ЦИФРОВАЯ СРЕДА", color = Color(0xFF466454), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedBorderColor = Neon,
    unfocusedBorderColor = Border,
    cursorColor = Neon,
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel
)

@Composable
private fun StationRow(
    station: RadioStation,
    isCurrent: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(if (isCurrent) Color(0xFF14291E) else Panel, RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(if (isCurrent) Neon else Color(0xFF1D3026), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(if (isCurrent) "▶" else "♫", color = if (isCurrent) Bg else Neon, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(station.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(station.country, station.genre).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { station.streamUrl }, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆", color = Neon, fontSize = 20.sp) }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("×", color = Muted, fontSize = 20.sp) }
    }
}

private suspend fun fetchStations(search: String): List<RadioStation> = withContext(Dispatchers.IO) {
    val endpoint = if (search.isBlank()) {
        "https://de1.api.radio-browser.info/json/stations/topclick/40"
    } else {
        "https://de1.api.radio-browser.info/json/stations/search?name=${java.net.URLEncoder.encode(search, "UTF-8")}&limit=40&hidebroken=true&order=clickcount&reverse=true"
    }
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000
        readTimeout = 12000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "DimLifeRadio/0.1 (Android)")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                if (!stream.startsWith("http://") && !stream.startsWith("https://")) continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    RadioStation(
                        name = name,
                        streamUrl = stream,
                        country = item.optString("country"),
                        genre = item.optString("tags").split(",").firstOrNull().orEmpty().trim(),
                        uuid = item.optString("stationuuid").ifBlank { stream }
                    )
                )
            }
        }.distinctBy { it.uuid }
    } finally {
        connection.disconnect()
    }
}

private fun loadCustomStations(context: android.content.Context): List<RadioStation> {
    val raw = context.getSharedPreferences("dimlife_radio", 0).getString("custom_stations", "[]") ?: "[]"
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(RadioStation(item.optString("name"), item.optString("url"), genre = "Моя станция", uuid = item.optString("url")))
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveCustomStations(context: android.content.Context, stations: List<RadioStation>) {
    val arr = JSONArray()
    stations.forEach {
        arr.put(org.json.JSONObject().put("name", it.name).put("url", it.streamUrl))
    }
    context.getSharedPreferences("dimlife_radio", 0).edit().putString("custom_stations", arr.toString()).apply()
}
