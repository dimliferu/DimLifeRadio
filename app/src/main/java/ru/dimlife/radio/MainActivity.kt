package ru.dimlife.radio

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
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
import kotlinx.coroutines.delay
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sin
import org.json.JSONArray

private val Bg = Color(0xFF07070D)
private val Panel = Color(0xFF11111D)
private val Panel2 = Color(0xFF191827)
private val Neon = Color(0xFF9B68FF)
private val AccentBlue = Color(0xFF5D9CFF)
private val MainText = Color(0xFFF5F2FF)
private val Muted = Color(0xFFA39DB8)
private val Line = Color(0xFF38304F)

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
    val favorites = remember {
        mutableStateListOf<Track>().apply {
            val json = runCatching { JSONArray(prefs.getString("favorite_tracks_v1", "[]")) }.getOrNull()
            if (json != null) for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                add(Track(o.optString("title"), o.optString("artist"), o.optString("artwork").takeIf { it.isNotBlank() }, o.optLong("playedAt")))
            }
        }
    }
    var page by remember { mutableStateOf("Главная") }
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1300); showSplash = false }
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

    fun saveTracks(key: String, tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { t -> array.put(org.json.JSONObject().apply {
            put("title", t.title); put("artist", t.artist); put("artwork", t.artwork.orEmpty()); put("playedAt", t.playedAt)
        }) }
        prefs.edit().putString(key, array.toString()).apply()
    }
    fun currentTrack(): Track = Track(
        title.ifBlank { "DimLife Radio" },
        artist.ifBlank { "Собственный эфир" },
        cover,
        System.currentTimeMillis()
    )
    fun toggleFavorite() {
        val current = currentTrack()
        val existing = favorites.indexOfFirst { it.title == current.title && it.artist == current.artist }
        if (existing >= 0) favorites.removeAt(existing) else favorites.add(0, current)
        saveTracks("favorite_tracks_v1", favorites)
    }
    fun startRadio() {
        status = "Подключаемся к эфиру…"
        player.setMediaItem(MediaItem.fromUri(STATION.streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isNowPlaying: Boolean) {
                playing = isNowPlaying
                if (isNowPlaying) status = "Прямой эфир"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                status = when (playbackState) {
                    Player.STATE_BUFFERING -> "Подключаемся к эфиру…"
                    Player.STATE_READY -> if (player.isPlaying) "Прямой эфир" else "Готово к воспроизведению"
                    Player.STATE_ENDED -> "Эфир завершён"
                    else -> if (player.playWhenReady) "Подключаемся к эфиру…" else "Готово к подключению"
                }
            }
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
                        saveTracks("track_history_v2", history)
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
                                selectedContainerColor = Color(0xFF28203D),
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
            if (showSplash) {
                SplashScreen()
            } else Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
                when (page) {
                    "Главная" -> HomeScreen(
                        playing, title, artist, cover, status, favorites.any { it.title == title.ifBlank { "DimLife Radio" } && it.artist == artist.ifBlank { "Собственный эфир" } },
                        onMenu = { scope.launch { drawer.open() } },
                        onPlay = { if (player.isPlaying) player.pause() else startRadio() },
                        onFavorite = { toggleFavorite() },
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
                    "Избранное" -> FavoritesScreen(favorites, onBack = { page = "Главная" }, onRemove = { track ->
                        favorites.removeAll { it.title == track.title && it.artist == track.artist }
                        saveTracks("favorite_tracks_v1", favorites)
                    })
                    "Расписание" -> ScheduleScreen(onBack = { page = "Главная" })
                    "О радио" -> AboutScreen { page = "Главная" }
                    "Настройки" -> SettingsScreen(autoStart, notifications,
                        onAuto = { autoStart = it; prefs.edit().putBoolean("auto_start", it).apply() },
                        onNotifications = { notifications = it; prefs.edit().putBoolean("notifications", it).apply() },
                        onBack = { page = "Главная" })
                    else -> HomeScreen(playing, title, artist, cover, status, false, { scope.launch { drawer.open() } }, { if (player.isPlaying) player.pause() else startRadio() }, { toggleFavorite() }, { page = "Сейчас в эфире" }, { page = "История треков" }, {}, volume, { volume = it; player.volume = it })
                }
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
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☰", Modifier.clickable { onMenu() }.padding(6.dp), color = MainText, fontSize = 25.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DimLife Радио", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Больше, чем просто музыка", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(if (favorite) "♥" else "♡", Modifier.clickable { onFavorite() }.padding(6.dp), color = Neon, fontSize = 25.sp)
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(214.dp).shadow(22.dp, CircleShape).clip(CircleShape)
            .background(Brush.linearGradient(listOf(AccentBlue, Neon, Color(0xFF34215C))))
            .padding(5.dp), contentAlignment = Alignment.Center) {
            AsyncImage(model = cover ?: STATION.artwork, contentDescription = "DimLife Радио", modifier = Modifier.fillMaxSize().clip(CircleShape).border(2.dp, Neon.copy(alpha = .8f), CircleShape), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(22.dp))
        Text("Сейчас в эфире", color = Muted, fontSize = 12.sp)
        Text(title.ifBlank { "Хорошая музыка" }, color = MainText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text(artist.ifBlank { "для лучших людей" }, color = MainText, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (playing) "● LIVE" else status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        RadioWaveform(playing, Modifier.fillMaxWidth().height(42.dp))
        Spacer(Modifier.height(15.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("◖", color = Muted, fontSize = 19.sp)
            Slider(value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Color(0xFFC7B5FF), activeTrackColor = Neon, inactiveTrackColor = Line))
            Text("◗", color = Muted, fontSize = 19.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundControl("◀", false) { Toast.makeText(context, "Переключение треков недоступно в прямом эфире", Toast.LENGTH_SHORT).show() }
            RoundControl(if (playing) "Ⅱ" else "▶", true, onPlay)
            RoundControl("▶", false) { Toast.makeText(context, "Переключение треков недоступно в прямом эфире", Toast.LENGTH_SHORT).show() }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionItem("↗", "Поделиться", onShare)
            ActionItem("☷", "История", onHistory)
            ActionItem(if (favorite) "♥" else "♡", "В избранное", onFavorite)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun RadioWaveform(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radio-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "wave-phase"
    )
    Canvas(modifier) {
        val count = 40
        val gap = size.width * 0.012f
        val barWidth = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val wave = abs(sin(i * 0.43f + phase * 5.8f)).toFloat()
            val heightFactor = if (playing) 0.16f + wave * 0.78f else 0.12f + (i % 4) * 0.025f
            val barHeight = size.height * heightFactor
            drawRoundRect(
                color = Neon.copy(alpha = if (playing) 0.55f + wave * 0.45f else 0.45f),
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
private fun SplashScreen() {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111020), Bg, Color(0xFF090812)))).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("DimLife", color = MainText, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("Радио", color = Color(0xFFC8B7FF), fontSize = 30.sp)
        Spacer(Modifier.height(28.dp))
        RadioWaveform(true, Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 18.dp))
        Spacer(Modifier.height(24.dp))
        Text("Больше, чем просто музыка", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(44.dp))
        CircularProgressIndicator(color = Neon, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
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
private fun FavoritesScreen(favorites: List<Track>, onBack: () -> Unit, onRemove: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        PageHeader("Избранное", onBack)
        Text("Любимые композиции из эфира DimLife Radio", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Здесь пока пусто. Нажми сердечко на плеере, чтобы сохранить текущий трек.", color = Muted, textAlign = TextAlign.Center)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(favorites, key = { "${it.playedAt}-${it.title}-${it.artist}" }) { track ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (track.artwork != null) AsyncImage(track.artwork, "Обложка", Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) { Text("♫", color = Neon, fontSize = 23.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = MainText, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = Muted, fontSize = 12.sp)
                    }
                    Text("♥", Modifier.clickable { onRemove(track) }.padding(8.dp), color = Neon, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun ScheduleScreen(onBack: () -> Unit) {
    val slots = listOf(
        Triple("00:00 – 06:00", "Ночная волна", "Спокойная музыка для твоих мыслей"),
        Triple("06:00 – 10:00", "Утренний заряд", "Лёгкий старт дня"),
        Triple("10:00 – 18:00", "Дневной эфир", "Лучшие хиты и новинки"),
        Triple("18:00 – 22:00", "Вечерний настрой", "Атмосфера твоего вечера"),
        Triple("22:00 – 00:00", "Ночная волна", "Спокойная музыка для твоих мыслей")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
        PageHeader("Расписание", onBack)
        Spacer(Modifier.height(18.dp))
        slots.forEach { slot ->
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(15.dp)).background(Panel).padding(15.dp)) {
                Text(slot.first, color = Muted, fontSize = 11.sp)
                Text(slot.second, color = MainText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Text(slot.third, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
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
