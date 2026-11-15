package ru.dimlife.radio

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import org.json.JSONArray

private val Bg = Color(0xFF050807)
private val Panel = Color(0xFF101914)
private val Panel2 = Color(0xFF19291F)
private val Neon = Color(0xFF45F58B)
private val MainText = Color(0xFFF1FFF5)
private val Muted = Color(0xFF91A398)
private val Line = Color(0xFF294333)

private val STATION = RadioStation(
    name = "DimLife Radio",
    streamUrl = "https://radio.dimlife.ru/live.mp3",
    subtitle = "Частный InterNet Проект",
    artwork = "https://radio.dimlife.ru/dimlife_radio.png"
)

data class RadioStation(val name: String, val streamUrl: String, val subtitle: String, val artwork: String)
data class Track(val title: String, val artist: String, val artwork: String?, val playedAt: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = ExoPlayer.Builder(this).build()
        setContent { DimLifeApp(player) }
    }
}

@Composable
private fun DimLifeApp(player: ExoPlayer) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dimlife_radio", 0) }
    val history = remember {
        mutableStateListOf<Track>().apply {
            val json = runCatching { JSONArray(prefs.getString("track_history_v2", "[]")) }.getOrNull()
            if (json != null) for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                add(Track(o.optString("title"), o.optString("artist"), o.optString("artwork").takeIf { it.isNotBlank() }, o.optLong("playedAt")))
            }
        }
    }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) } }
    var page by remember { mutableStateOf("Главная") }
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Готово к подключению") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    var volume by remember { mutableFloatStateOf(1f) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", false)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun saveHistory() {
        val array = JSONArray()
        history.forEach { t -> array.put(org.json.JSONObject().apply {
            put("title", t.title); put("artist", t.artist); put("artwork", t.artwork.orEmpty()); put("playedAt", t.playedAt)
        }) }
        prefs.edit().putString("track_history_v2", array.toString()).apply()
    }
    fun startRadio() {
        status = "Подключаемся к эфиру…"
        player.setMediaItem(MediaItem.fromUri(STATION.streamUrl))
        player.prepare()
        player.playWhenReady = true
        status = "Прямой эфир"
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isNowPlaying: Boolean) { playing = isNowPlaying }
            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                val t = decode(metadata.title?.toString().orEmpty())
                val a = decode(metadata.artist?.toString().orEmpty())
                val art = metadata.artworkUri?.toString()
                title = t; artist = a; cover = art
                if (t.isNotBlank() || a.isNotBlank()) {
                    val entry = Track(t.ifBlank { "Неизвестный трек" }, a.ifBlank { "DimLife Radio" }, art, System.currentTimeMillis())
                    val last = history.firstOrNull()
                    if (last == null || last.title != entry.title || last.artist != entry.artist) {
                        history.add(0, entry)
                        while (history.size > 100) history.removeAt(history.lastIndex)
                        saveHistory()
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) { playing = false; status = "Ошибка подключения. Нажми Play для повтора." }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Neon, onPrimary = Bg, background = Bg, onBackground = MainText,
        surface = Panel, onSurface = MainText, secondary = Neon
    )) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Panel, drawerContentColor = MainText) {
                    Spacer(Modifier.height(24.dp))
                    Text("DimLife Радио", Modifier.padding(horizontal = 22.dp), color = MainText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("Больше, чем просто музыка", Modifier.padding(start = 22.dp, top = 4.dp, bottom = 22.dp), color = Muted, fontSize = 12.sp)
                    listOf("Главная" to "⌂", "Сейчас в эфире" to "♫", "Расписание" to "☷", "Избранное" to "♡", "История треков" to "◷", "О радио" to "ⓘ", "Настройки" to "⚙").forEach { (label, icon) ->
                        NavigationDrawerItem(
                            label = { Text(label, fontWeight = if (page == label) FontWeight.Bold else FontWeight.Normal) },
                            icon = { Text(icon, color = if (page == label) Neon else Muted, fontSize = 20.sp) },
                            selected = page == label,
                            onClick = { page = label; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFF193525),
                                selectedTextColor = Neon,
                                selectedIconColor = Neon,
                                unselectedTextColor = MainText
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("DIMLIFE • RADIO", Modifier.padding(22.dp), color = Neon, letterSpacing = 2.sp, fontSize = 11.sp)
                }
            }
        ) {
            Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
                when (page) {
                    "Главная" -> HomeScreen(
                        playing, title, artist, cover, status, favorites.contains(STATION.name),
                        onMenu = { scope.launch { drawer.open() } },
                        onPlay = { if (player.isPlaying) player.pause() else startRadio() },
                        onFavorite = {
                            if (favorites.contains(STATION.name)) favorites.remove(STATION.name) else favorites.add(STATION.name)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                        },
                        onNow = { page = "Сейчас в эфире" },
                        onHistory = { page = "История треков" },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "DimLife Radio — https://radio.dimlife.ru") }
                            context.startActivity(Intent.createChooser(send, "Поделиться DimLife Radio"))
                        },
                        volume = volume,
                        onVolume = { volume = it; player.volume = it }
                    )
                    "Сейчас в эфире" -> NowPlayingScreen(title, artist, cover, playing, status, onBack = { page = "Главная" }, onPlay = { if (player.isPlaying) player.pause() else startRadio() })
                    "История треков" -> TrackHistoryScreen(history, onBack = { page = "Главная" })
                    "Избранное" -> SimpleScreen("Избранное", if (favorites.isEmpty()) "Здесь будут ваши избранные станции. Пока доступен только DimLife Radio." else "♥  DimLife Radio\nСобственный эфир", onBack = { page = "Главная" })
                    "Расписание" -> SimpleScreen("Расписание", "Актуальное расписание пока не опубликовано. Мы не показываем вымышленные программы.", onBack = { page = "Главная" })
                    "О радио" -> AboutScreen { page = "Главная" }
                    "Настройки" -> SettingsScreen(autoStart, notifications,
                        onAuto = { autoStart = it; prefs.edit().putBoolean("auto_start", it).apply() },
                        onNotifications = { notifications = it; prefs.edit().putBoolean("notifications", it).apply() },
                        onBack = { page = "Главная" })
                    else -> HomeScreen(playing, title, artist, cover, status, false, { scope.launch { drawer.open() } }, { if (player.isPlaying) player.pause() else startRadio() }, {}, { page = "Сейчас в эфире" }, { page = "История треков" }, {}, volume, { volume = it; player.volume = it })
                }
                BottomBar(page) { page = it }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    playing: Boolean, title: String, artist: String, cover: String?, status: String, favorite: Boolean,
    onMenu: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit, onNow: () -> Unit,
    onHistory: () -> Unit, onShare: () -> Unit, volume: Float, onVolume: (Float) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Neon, Color(0xFF167A43)))), contentAlignment = Alignment.Center) {
                Text("DL", color = Bg, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("DimLife Радио", color = MainText, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text("Частный InterNet Проект", color = Neon, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Text("● LIVE", color = if (playing) Neon else Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text("☰", Modifier.clickable { onMenu() }, color = Neon, fontSize = 25.sp)
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            AsyncImage(model = cover ?: STATION.artwork, contentDescription = "DimLife Radio", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text("DIMLIFE RADIO", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 12.sp)
                Text("Собственный эфир", color = Neon, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(title.ifBlank { "DimLife Radio" }, color = MainText, fontSize = 23.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(artist.ifBlank { "DimLife • Собственный эфир" }, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Neon, Color(0xFFB5FFD0), Neon))))
        Text(if (playing) "● ПРЯМОЙ ЭФИР" else status.uppercase(), Modifier.padding(top = 10.dp), color = Neon, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("−", color = Muted)
            Slider(value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Neon, activeTrackColor = Neon, inactiveTrackColor = Line))
            Text("+", color = Muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundControl("♫", false) { onNow() }
            RoundControl(if (playing) "Ⅱ" else "▶", true, onPlay)
            RoundControl("♡", false) { onFavorite() }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionItem("↗", "Поделиться", onShare)
            ActionItem("☷", "История", onHistory)
            ActionItem(if (favorite) "♥" else "♡", "В избранном", onFavorite)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoundControl(symbol: String, primary: Boolean, onClick: () -> Unit) {
    Surface(Modifier.size(if (primary) 82.dp else 62.dp).clickable { onClick() }, shape = CircleShape, color = if (primary) Neon else Panel2, shadowElevation = if (primary) 12.dp else 0.dp) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, color = if (primary) Bg else Neon, fontSize = if (primary) 30.sp else 23.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ActionItem(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, color = Neon, fontSize = 25.sp)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun NowPlayingScreen(title: String, artist: String, cover: String?, playing: Boolean, status: String, onBack: () -> Unit, onPlay: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PageHeader("Сейчас в эфире", onBack)
        Spacer(Modifier.height(20.dp))
        AsyncImage(model = cover ?: STATION.artwork, contentDescription = "Обложка эфира", modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(22.dp))
        Text(title.ifBlank { "DimLife Radio" }, color = MainText, fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(artist.ifBlank { "Собственный эфир" }, color = Muted, modifier = Modifier.padding(top = 6.dp))
        Text(if (playing) "● В ЭФИРЕ 24/7" else status, color = Neon, modifier = Modifier.padding(top = 18.dp))
        Spacer(Modifier.height(22.dp))
        Button(onClick = onPlay, colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg)) { Text(if (playing) "Пауза" else "Слушать эфир") }
        Spacer(Modifier.height(18.dp))
        Text("DimLife Радио — твоя музыка, твоё настроение и твоя атмосфера.", color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TrackHistoryScreen(history: List<Track>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        PageHeader("История треков", onBack)
        Text("Композиции, прозвучавшие в эфире DimLife Radio", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
        if (history.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("История пока пуста.\nОна заполнится, когда поток передаст данные о композициях.", color = Muted, textAlign = TextAlign.Center)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(history, key = { "${it.playedAt}-${it.title}-${it.artist}" }) { track ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (track.artwork != null) AsyncImage(track.artwork, "Обложка", Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) { Text("♫", color = Neon, fontSize = 23.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = MainText, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = Muted, fontSize = 12.sp)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(track.playedAt)), color = Neon, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(autoStart: Boolean, notifications: Boolean, onAuto: (Boolean) -> Unit, onNotifications: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        PageHeader("Настройки", onBack)
        Spacer(Modifier.height(18.dp))
        SettingSwitch("Тёмная тема", "Фирменное оформление DimLife", true, {})
        SettingSwitch("Автозапуск", "Подключаться к радио автоматически", autoStart, onAuto)
        SettingSwitch("Уведомления", "Показывать статус эфира", notifications, onNotifications)
        Spacer(Modifier.height(20.dp))
        Text("О ПРИЛОЖЕНИИ", color = Neon, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        InfoRow("Версия", "0.1.4")
        InfoRow("Сайт", "https://radio.dimlife.ru")
        InfoRow("Поток", "MP3 • 128 Кбит/с")
    }
}

@Composable
private fun SettingSwitch(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MainText, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Bg, checkedTrackColor = Neon))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MainText)
        Text(value, color = Muted, fontSize = 12.sp)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PageHeader("О радио", onBack)
        Spacer(Modifier.height(22.dp))
        AsyncImage(STATION.artwork, "DimLife Radio", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(20.dp))
        Text("Хорошая музыка для лучших людей", color = MainText, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text("DimLife Радио — твоя музыка, твоё настроение и твоя атмосфера. Мы вещаем 24/7, чтобы быть рядом с тобой в любой момент.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(20.dp))
        Text("●  В эфире 24/7", color = Neon, fontWeight = FontWeight.Bold)
        Text("radio.dimlife.ru", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SimpleScreen(title: String, body: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        PageHeader(title, onBack)
        Spacer(Modifier.height(20.dp))
        Text(body, color = Muted, lineHeight = 24.sp)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", Modifier.clickable { onBack() }.padding(end = 18.dp), color = Neon, fontSize = 34.sp)
        Text(title, color = Neon, fontWeight = FontWeight.Bold, fontSize = 23.sp)
    }
}

@Composable
private fun BottomBar(page: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("Главная" to "◉", "Сейчас в эфире" to "♫", "Избранное" to "♥", "История треков" to "◷").forEach { (name, icon) ->
            val selected = page == name || (name == "Главная" && page !in listOf("Сейчас в эфире", "Избранное", "История треков"))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(if (selected) Color(0xFF193525) else Color.Transparent).clickable { onSelect(name) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, color = if (selected) Neon else Muted, fontSize = 19.sp)
                Text(when (name) { "Главная" -> "Плеер"; "История треков" -> "История"; else -> name }, color = if (selected) Neon else Muted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

private fun decode(value: String): String {
    if (value.isBlank()) return value
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    else { @Suppress("DEPRECATION") Html.fromHtml(value).toString().trim() }
}
