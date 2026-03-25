package com.wusicmave

import android.Manifest
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val audioFiles = mutableStateListOf<AudioFile>()
    private var isAppReady = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (audioGranted && recordGranted) {
            initializePlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO)
        )

        setContent {
            MaterialTheme {
                // Force the entire app background to True Black
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    if (isAppReady.value) {
                        MusicPlayerScreen(audioFiles, mediaController)
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun initializePlayer() {
        val songs = getLocalMusic(this)
        audioFiles.addAll(songs)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            val mediaItems = songs.map { MediaItem.fromUri(it.uri) }
            mediaController?.setMediaItems(mediaItems)
            mediaController?.prepare()
            isAppReady.value = true
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

@Composable
fun MusicPlayerScreen(songList: List<AudioFile>, player: MediaController?) {
    Column(modifier = Modifier.fillMaxSize()) {

        Text(
            text = "WusicMave",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White, // White text
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(songList) { index, song ->
                SongItem(song = song) {
                    player?.seekToDefaultPosition(index)
                    player?.play()
                }
            }
        }

        NowPlayingBottomBar()
    }
}

@Composable
fun SongItem(song: AudioFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White, // White text
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray, // Light gray for artist
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NowPlayingBottomBar() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.Black) // Seamless black background
            .padding(16.dp)
    ) {
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White // White label
        )

        Spacer(modifier = Modifier.height(8.dp))

        AndroidView(
            factory = { context ->
                WaveformView(context).apply {
                    startVisualizing(PlaybackService.currentAudioSessionId)
                }
            },
            onRelease = { view ->
                view.stopVisualizing()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
    }
}
