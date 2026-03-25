package com.wusicmave

import android.Manifest
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// ── Derived models ────────────────────────────────────────────────────────
data class Album(val name: String, val artist: String, val songs: List<AudioFile>)
data class Artist(val name: String, val songs: List<AudioFile>)

// ── Palette ───────────────────────────────────────────────────────────────
private val Lime          = Color(0xFFC8FF00)
private val BGDeep        = Color(0xFF000000)
private val BGLift        = Color(0xFF080808)
private val BGPanel       = Color(0xFF0B0B0B)
private val Hair          = Color(0xFF111111)
private val TxtHi         = Color(0xFFFFFFFF)
private val TxtMid        = Color(0xFF464646)
private val TxtGhost      = Color(0xFF0F0F0F)
private val AccentPalette = listOf(
    Color(0xFF9B59FF), Color(0xFFFF5FA0), Color(0xFF00D4FF),
    Color(0xFFFF9F43), Color(0xFF1DD1A1), Color(0xFFEE5A24)
)

enum class AppTab(val label: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"),
    PLAYLISTS("Playlists"), SETTINGS("Settings")
}

// ── Activity ──────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val audioFiles = mutableStateListOf<AudioFile>()
    private var isAppReady = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.READ_MEDIA_AUDIO] == true &&
            perms[Manifest.permission.RECORD_AUDIO]    == true) initializePlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO)
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BGDeep) {
                    if (isAppReady.value) {
                        MusicPlayerScreen(audioFiles, mediaController)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color       = Lime,
                                strokeWidth = 1.5.dp,
                                modifier    = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initializePlayer() {
        val songs = getLocalMusic(this)
        audioFiles.addAll(songs)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
            mediaController?.prepare()
            isAppReady.value = true
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

// ── Root screen ───────────────────────────────────────────────────────────
@Composable
fun MusicPlayerScreen(songList: List<AudioFile>, player: MediaController?) {

    var currentTab   by remember { mutableStateOf(AppTab.SONGS) }
    var isPlaying    by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(-1)   }
    var shuffleOn    by remember { mutableStateOf(false) }
    var repeatMode   by remember { mutableStateOf(0)    }

    // Playback position — hoisted so both mini and full panels read the same state
    var scrubValue by remember { mutableStateOf(0f)   }
    var isSeeking  by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L)   }
    var durationMs by remember { mutableStateOf(1L)   }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentIndex = player?.currentMediaItemIndex ?: -1
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking && player != null) {
                positionMs = player.currentPosition
                val dur    = player.duration
                durationMs = if (dur > 0) dur else 1L
                scrubValue = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            }
            delay(500)
        }
    }

    val albums = remember(songList) {
        songList.groupBy { it.album }.entries
            .map { (name, songs) -> Album(name, songs.first().artist, songs) }
            .sortedBy { it.name }
    }
    val artists = remember(songList) {
        songList.groupBy { it.artist }.entries
            .map { (name, songs) -> Artist(name, songs) }
            .sortedBy { it.name }
    }

    // ── Expand/collapse animation ─────────────────────────────────────────
    val scope          = rememberCoroutineScope()
    val expandFraction = remember { Animatable(0f) }
    val panelSpring    = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)

    fun expand()   { scope.launch { expandFraction.animateTo(1f, panelSpring) } }
    fun collapse() { scope.launch { expandFraction.animateTo(0f, panelSpring) } }

    // System back collapses the panel if open
    BackHandler(enabled = expandFraction.value > 0.05f) { collapse() }

    val density       = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenH       = with(density) { configuration.screenHeightDp.dp.toPx() }
    val collapsedH    = with(density) { 68.dp.toPx() }

    Box(Modifier.fillMaxSize().background(BGDeep)) {

        // ── Background layer: tabs + nav (always present, panel covers when expanded)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (currentTab) {
                    AppTab.SONGS     -> SongsTab(songList, currentIndex, isPlaying, player)
                    AppTab.ALBUMS    -> AlbumsTab(albums, songList, currentIndex, isPlaying, player)
                    AppTab.ARTISTS   -> ArtistsTab(artists, songList, currentIndex, isPlaying, player)
                    AppTab.PLAYLISTS -> PlaylistsTab()
                    AppTab.SETTINGS  -> SettingsTab(
                        shuffleOn  = shuffleOn,
                        repeatMode = repeatMode,
                        onShuffleChange = { shuffleOn = it; player?.shuffleModeEnabled = it },
                        onRepeatChange  = {
                            repeatMode = it
                            player?.repeatMode = when (it) {
                                1    -> Player.REPEAT_MODE_ONE
                                2    -> Player.REPEAT_MODE_ALL
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }
                    )
                }
            }
            // Reserve space so last list items aren't hidden behind mini player
            Spacer(Modifier.height(68.dp))
            AppBottomNav(current = currentTab, onSelect = { currentTab = it })
        }

        // ── Expandable panel overlay ──────────────────────────────────────
        val panelOffsetY = ((1f - expandFraction.value) * (screenH - collapsedH)).toInt()
        val cornerRad    = (22f * (1f - expandFraction.value)).coerceAtLeast(0f).dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, panelOffsetY) }
                .clip(RoundedCornerShape(topStart = cornerRad, topEnd = cornerRad))
                .background(BGPanel)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (expandFraction.value > 0.38f) expand() else collapse()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // Dragging up (negative dragAmount) → expand
                            val delta = -dragAmount / (screenH - collapsedH)
                            scope.launch {
                                expandFraction.snapTo(
                                    (expandFraction.value + delta).coerceIn(0f, 1f)
                                )
                            }
                        }
                    )
                }
        ) {
            val frac = expandFraction.value

            // Full player — fades in from fraction 0.3 → 1.0
            val fullAlpha = ((frac - 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (fullAlpha > 0f) {
                FullPlayerContent(
                    modifier         = Modifier.fillMaxSize().alpha(fullAlpha),
                    song             = songList.getOrNull(currentIndex),
                    isPlaying        = isPlaying,
                    scrubValue       = scrubValue,
                    positionMs       = positionMs,
                    durationMs       = durationMs,
                    shuffleOn        = shuffleOn,
                    repeatMode       = repeatMode,
                    onPlayPause      = { if (isPlaying) player?.pause() else player?.play() },
                    onPrev           = { player?.seekToPreviousMediaItem(); player?.play() },
                    onNext           = { player?.seekToNextMediaItem(); player?.play() },
                    onShuffleToggle  = { shuffleOn = !shuffleOn; player?.shuffleModeEnabled = shuffleOn },
                    onRepeatToggle   = {
                        repeatMode = (repeatMode + 1) % 3
                        player?.repeatMode = when (repeatMode) {
                            1    -> Player.REPEAT_MODE_ONE
                            2    -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    onScrub          = { v -> isSeeking = true; scrubValue = v },
                    onScrubDone      = { player?.seekTo((scrubValue * durationMs).toLong()); isSeeking = false }
                )
            }

            // Mini player — fades out during first 25% of expansion
            val miniAlpha = (1f - frac * 4f).coerceIn(0f, 1f)
            if (miniAlpha > 0f) {
                MiniPlayerContent(
                    modifier    = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .align(Alignment.BottomCenter)
                        .alpha(miniAlpha)
                        .clickable { expand() },
                    song        = songList.getOrNull(currentIndex),
                    isPlaying   = isPlaying,
                    scrubValue  = scrubValue,
                    onPlayPause = { if (isPlaying) player?.pause() else player?.play() }
                )
            }
        }
    }
}

// ── Mini player ───────────────────────────────────────────────────────────
@Composable
private fun MiniPlayerContent(
    modifier:    Modifier,
    song:        AudioFile?,
    isPlaying:   Boolean,
    scrubValue:  Float,
    onPlayPause: () -> Unit
) {
    Column(modifier) {
        // Slim progress line at the very top
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF1A1A1A))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(scrubValue.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Lime)
            )
        }
        // Content row
        Row(
            modifier          = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tiny waveform-dot avatar
            Box(
                modifier         = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null,
                    tint = Lime, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = song?.title ?: "—",
                    color      = TxtHi,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text     = song?.artist ?: "Nothing selected",
                    color    = TxtMid,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector        = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint               = TxtHi,
                    modifier           = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ── Full player ───────────────────────────────────────────────────────────
@Composable
private fun FullPlayerContent(
    modifier:       Modifier,
    song:           AudioFile?,
    isPlaying:      Boolean,
    scrubValue:     Float,
    positionMs:     Long,
    durationMs:     Long,
    shuffleOn:      Boolean,
    repeatMode:     Int,
    onPlayPause:    () -> Unit,
    onPrev:         () -> Unit,
    onNext:         () -> Unit,
    onShuffleToggle:() -> Unit,
    onRepeatToggle: () -> Unit,
    onScrub:        (Float) -> Unit,
    onScrubDone:    () -> Unit
) {
    Column(modifier.background(BGDeep)) {

        // ── Drag handle ───────────────────────────────────────────────────
        Box(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.width(36.dp).height(3.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
            )
        }

        // ── Circular waveform ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory   = { ctx ->
                    CircularWaveformView(ctx).apply {
                        startVisualizing(PlaybackService.currentAudioSessionId)
                    }
                },
                onRelease = { view -> view.stopVisualizing() },
                modifier  = Modifier.fillMaxSize()
            )
        }

        // ── Bottom controls ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            // Track info
            AnimatedContent(
                targetState  = Pair(song?.title ?: "—", song?.artist ?: "Nothing selected"),
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically { it / 4 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically { -(it / 4) })
                },
                label = "fullTrackInfo"
            ) { (title, artist) ->
                Column {
                    Text(
                        text          = title,
                        color         = TxtHi,
                        fontWeight    = FontWeight.Bold,
                        fontSize      = 20.sp,
                        letterSpacing = (-0.4).sp,
                        maxLines      = 1,
                        overflow      = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = artist,
                        color    = Color(0xFF4A4A4A),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Scrubber
            Slider(
                value                 = scrubValue,
                onValueChange         = onScrub,
                onValueChangeFinished = onScrubDone,
                colors   = SliderDefaults.colors(
                    thumbColor         = Lime,
                    activeTrackColor   = Lime,
                    inactiveTrackColor = Color(0xFF1E1E1E),
                    activeTickColor    = Color.Transparent,
                    inactiveTickColor  = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(positionMs),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = TxtMid, letterSpacing = 0.sp)
                Text(formatDuration(durationMs),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = TxtMid, letterSpacing = 0.sp)
            }

            Spacer(Modifier.height(16.dp))

            // Transport controls
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onShuffleToggle, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle",
                        tint     = if (shuffleOn) Lime else TxtMid,
                        modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onPrev, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous",
                        tint = TxtHi, modifier = Modifier.size(32.dp))
                }
                Box(
                    modifier = Modifier.size(60.dp).clip(CircleShape).background(Lime)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint               = BGDeep,
                        modifier           = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next",
                        tint = TxtHi, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onRepeatToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint     = if (repeatMode > 0) Lime else TxtMid,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Bottom navigation ─────────────────────────────────────────────────────
@Composable
private fun AppBottomNav(current: AppTab, onSelect: (AppTab) -> Unit) {
    val tabs = listOf(
        AppTab.SONGS     to Icons.Filled.MusicNote,
        AppTab.ALBUMS    to Icons.Filled.Album,
        AppTab.ARTISTS   to Icons.Filled.Person,
        AppTab.PLAYLISTS to Icons.Filled.QueueMusic,
        AppTab.SETTINGS  to Icons.Filled.Settings
    )
    NavigationBar(containerColor = BGPanel, tonalElevation = 0.dp) {
        tabs.forEach { (tab, icon) ->
            NavigationBarItem(
                selected = current == tab,
                onClick  = { onSelect(tab) },
                icon     = { Icon(icon, contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                label    = { Text(tab.label, fontSize = 9.sp, letterSpacing = 0.5.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Lime,
                    selectedTextColor   = Lime,
                    unselectedIconColor = TxtMid,
                    unselectedTextColor = TxtMid,
                    indicatorColor      = Color(0xFF141414)
                )
            )
        }
    }
}

// ── Shared: tab header ────────────────────────────────────────────────────
@Composable
private fun TabHeader(title: String, subtitle: String, ghost: String = "") {
    Box(Modifier.fillMaxWidth().height(90.dp).clipToBounds()) {
        if (ghost.isNotEmpty()) {
            Text(ghost, fontSize = 108.sp, fontWeight = FontWeight.Black,
                color = TxtGhost, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp, y = 12.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 14.dp)) {
            Text(subtitle, color = TxtMid, fontSize = 9.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(title, color = TxtHi, fontSize = 24.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
        }
    }
    HorizontalDivider(color = Hair, thickness = 0.5.dp)
}

// ── Songs tab ─────────────────────────────────────────────────────────────
@Composable
private fun SongsTab(
    songList: List<AudioFile>, currentIndex: Int,
    isPlaying: Boolean, player: MediaController?
) {
    Column(Modifier.fillMaxSize()) {
        TabHeader("Songs", "${songList.size} tracks", "${songList.size}")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(songList) { index, song ->
                TrackRow(
                    song = song, index = index,
                    isCurrent = index == currentIndex,
                    isPlaying = isPlaying && index == currentIndex,
                    onClick   = { player?.seekToDefaultPosition(index); player?.play() }
                )
                HorizontalDivider(color = Hair, thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 68.dp))
            }
        }
    }
}

// ── Albums tab ────────────────────────────────────────────────────────────
@Composable
private fun AlbumsTab(
    albums: List<Album>, allSongs: List<AudioFile>,
    currentIndex: Int, isPlaying: Boolean, player: MediaController?
) {
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    BackHandler(enabled = selectedAlbum != null) { selectedAlbum = null }

    AnimatedContent(
        targetState = selectedAlbum,
        transitionSpec = {
            if (targetState != null)
                (fadeIn(tween(220)) + slideInHorizontally { it / 4 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally { -(it / 4) })
            else
                (fadeIn(tween(220)) + slideInHorizontally { -(it / 4) }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally { it / 4 })
        }, label = "albumNav"
    ) { album ->
        if (album == null) {
            Column(Modifier.fillMaxSize()) {
                TabHeader("Albums", "${albums.size} albums")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement   = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albums) { a -> AlbumCard(a, albums.indexOf(a)) { selectedAlbum = a } }
                }
            }
        } else {
            DrillDownList(album.name, album.artist, album.songs, allSongs,
                currentIndex, isPlaying, player) { selectedAlbum = null }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, index: Int, onClick: () -> Unit) {
    val color = AccentPalette[index % AccentPalette.size]
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(BGLift).clickable { onClick() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0E0E0E)),
            contentAlignment = Alignment.Center) {
            Text(album.name.take(1).uppercase(), fontSize = 52.sp,
                fontWeight = FontWeight.Black, color = color.copy(alpha = 0.25f))
            Icon(Icons.Filled.MusicNote, contentDescription = null,
                tint = color.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(album.name, color = TxtHi, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(album.artist, color = TxtMid, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${album.songs.size} tracks", color = Color(0xFF282828), fontSize = 9.sp)
        }
    }
}

// ── Artists tab ───────────────────────────────────────────────────────────
@Composable
private fun ArtistsTab(
    artists: List<Artist>, allSongs: List<AudioFile>,
    currentIndex: Int, isPlaying: Boolean, player: MediaController?
) {
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    BackHandler(enabled = selectedArtist != null) { selectedArtist = null }

    AnimatedContent(
        targetState = selectedArtist,
        transitionSpec = {
            if (targetState != null)
                (fadeIn(tween(220)) + slideInHorizontally { it / 4 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally { -(it / 4) })
            else
                (fadeIn(tween(220)) + slideInHorizontally { -(it / 4) }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally { it / 4 })
        }, label = "artistNav"
    ) { artist ->
        if (artist == null) {
            Column(Modifier.fillMaxSize()) {
                TabHeader("Artists", "${artists.size} artists")
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(artists) { index, a ->
                        ArtistRow(a, index) { selectedArtist = a }
                        HorizontalDivider(color = Hair, thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 76.dp))
                    }
                }
            }
        } else {
            DrillDownList(artist.name, "${artist.songs.size} songs", artist.songs, allSongs,
                currentIndex, isPlaying, player) { selectedArtist = null }
        }
    }
}

@Composable
private fun ArtistRow(artist: Artist, index: Int, onClick: () -> Unit) {
    val color = AccentPalette[index % AccentPalette.size]
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF111111)),
            contentAlignment = Alignment.Center) {
            Text(artist.name.take(1).uppercase(), color = color,
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, color = TxtHi, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artist.songs.size} songs", color = TxtMid, fontSize = 11.sp)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null,
            tint = TxtMid, modifier = Modifier.size(18.dp))
    }
}

// ── Drill-down list ───────────────────────────────────────────────────────
@Composable
private fun DrillDownList(
    title: String, subtitle: String, songs: List<AudioFile>,
    allSongs: List<AudioFile>, currentIndex: Int, isPlaying: Boolean,
    player: MediaController?, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back",
                    tint = TxtHi, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TxtHi, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TxtMid, fontSize = 12.sp)
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Lime)
                    .clickable {
                        val idx = allSongs.indexOf(songs.firstOrNull())
                        if (idx >= 0) { player?.seekToDefaultPosition(idx); player?.play() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play all",
                    tint = BGDeep, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = Hair, thickness = 0.5.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(songs) { index, song ->
                val globalIdx = allSongs.indexOf(song)
                TrackRow(
                    song = song, index = index,
                    isCurrent = globalIdx == currentIndex,
                    isPlaying = isPlaying && globalIdx == currentIndex,
                    onClick   = {
                        if (globalIdx >= 0) { player?.seekToDefaultPosition(globalIdx); player?.play() }
                    }
                )
                HorizontalDivider(color = Hair, thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 68.dp))
            }
        }
    }
}

// ── Playlists ─────────────────────────────────────────────────────────────
@Composable
private fun PlaylistsTab() {
    Column(Modifier.fillMaxSize()) {
        TabHeader("Playlists", "Your collections")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF0E0E0E)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = null,
                        tint = TxtMid, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("No playlists yet", color = TxtHi,
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Create a playlist to organize\nyour favorite tracks",
                    color = TxtMid, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(Lime)
                        .clickable { }.padding(horizontal = 28.dp, vertical = 13.dp)
                ) {
                    Text("New Playlist", color = BGDeep,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Settings ──────────────────────────────────────────────────────────────
@Composable
private fun SettingsTab(
    shuffleOn: Boolean, repeatMode: Int,
    onShuffleChange: (Boolean) -> Unit, onRepeatChange: (Int) -> Unit
) {
    var crossfade     by remember { mutableStateOf(false) }
    var highQuality   by remember { mutableStateOf(true)  }
    var notifications by remember { mutableStateOf(true)  }
    var lyrics        by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TabHeader("Settings", "Preferences")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionLabel("Playback") }
            item { ToggleRow("Shuffle", "Play tracks in random order",
                Icons.Filled.Shuffle, shuffleOn) { onShuffleChange(it) } }
            item {
                val label = when (repeatMode) { 1 -> "Repeat: One"; 2 -> "Repeat: All"; else -> "Repeat: Off" }
                ClickRow(label, "Tap to cycle mode", Icons.Filled.Repeat) {
                    onRepeatChange((repeatMode + 1) % 3) }
            }
            item { ToggleRow("Crossfade", "Smooth transitions between tracks",
                Icons.Filled.Tune, crossfade) { crossfade = it } }
            item { ToggleRow("High Quality Audio", "Use highest available bitrate",
                Icons.Filled.GraphicEq, highQuality) { highQuality = it } }
            item { SectionLabel("Display") }
            item { ToggleRow("Show Lyrics", "Display synced lyrics when available",
                Icons.Filled.Subject, lyrics) { lyrics = it } }
            item { SectionLabel("System") }
            item { ToggleRow("Playback Notifications", "Media controls in notification bar",
                Icons.Filled.Notifications, notifications) { notifications = it } }
            item { SectionLabel("About") }
            item { ClickRow("WusicMave", "Version 1.0 · Apache 2.0", Icons.Filled.Info) {} }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(title.uppercase(), color = Lime, fontSize = 9.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, icon: ImageVector,
                      checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingsIcon(icon, checked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TxtHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TxtMid, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor    = BGDeep, checkedTrackColor    = Lime,
                uncheckedThumbColor  = TxtMid, uncheckedTrackColor  = Color(0xFF1A1A1A),
                uncheckedBorderColor = Color(0xFF2A2A2A)))
    }
    HorizontalDivider(color = Hair, thickness = 0.5.dp, modifier = Modifier.padding(start = 74.dp))
}

@Composable
private fun ClickRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingsIcon(icon, false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TxtHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TxtMid, fontSize = 11.sp)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null,
            tint = TxtMid, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = Hair, thickness = 0.5.dp, modifier = Modifier.padding(start = 74.dp))
}

@Composable
private fun SettingsIcon(icon: ImageVector, active: Boolean) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E0E0E)),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null,
            tint = if (active) Lime else TxtMid, modifier = Modifier.size(18.dp))
    }
}

// ── Track row ─────────────────────────────────────────────────────────────
@Composable
private fun TrackRow(song: AudioFile, index: Int, isCurrent: Boolean,
                     isPlaying: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (isCurrent) BGLift else Color.Transparent, tween(200), label = "rowBg")
    Row(
        Modifier.fillMaxWidth().background(bg).clickable { onClick() }
            .padding(start = 24.dp, end = 24.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterEnd) {
            if (isCurrent && isPlaying) PlayingBars()
            else Text("%02d".format(index + 1), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (isCurrent) Lime else TxtMid, letterSpacing = 0.sp)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title,
                color = if (isCurrent) TxtHi else Color(0xFF9A9A9A),
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(song.artist,
                color = if (isCurrent) Color(0xFF5A5A5A) else TxtMid,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text(formatDuration(song.duration), fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, color = TxtMid, letterSpacing = 0.sp)
    }
}

// ── Equalizer bars ────────────────────────────────────────────────────────
@Composable
private fun PlayingBars() {
    val inf = rememberInfiniteTransition(label = "bars")
    val b1 by inf.animateFloat(0.25f, 1f,
        infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse), "b1")
    val b2 by inf.animateFloat(1f, 0.3f,
        infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse), "b2")
    val b3 by inf.animateFloat(0.5f, 0.95f,
        infiniteRepeatable(tween(460, easing = LinearEasing), RepeatMode.Reverse), "b3")
    Row(Modifier.height(14.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(b1, b2, b3).forEach { f ->
            Box(Modifier.width(2.5.dp).fillMaxHeight(f)
                .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                .background(Lime))
        }
    }
}

// ── Utility ───────────────────────────────────────────────────────────────
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
