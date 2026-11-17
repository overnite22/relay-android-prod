package io.github.overnite22.relay.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Recording
import io.github.overnite22.relay.kit.RecordingTimeline
import io.github.overnite22.relay.kit.RelayClient
import io.github.overnite22.relay.sessions.Frame
import io.github.overnite22.relay.sessions.FramePool
import io.github.overnite22.relay.sessions.GuacDisplay
import io.github.overnite22.relay.sessions.PointerMode
import io.github.overnite22.relay.sessions.RemoteScreenView
import io.github.overnite22.relay.sessions.ScreenHost
import io.github.overnite22.relay.sessions.ScreenModel
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Enregistrements de sessions de l'utilisateur. */
@Composable
fun RecordingsScreen(app: AppSession, nav: Navigator, tab: Tab, back: () -> Unit) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val c = app.client ?: return
        loading = true
        try {
            recordings = c.recordings()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    RelayScaffold("Enregistrements", back) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { StatusBanner(it) } }
                if (recordings.isEmpty() && !loading && error == null) item {
                    EmptyState("Aucun enregistrement", "Ouvrez une session avec « Ouvrir et enregistrer » pour la relire ici.", Icons.Filled.Movie)
                }
                items(recordings, key = { it.id }) { r ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { nav.push(tab, Route.Player(r)) }, onLongClick = { menuFor = r.id })
                                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("recording-${r.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KindBadge(r.kind)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name)
                                Text("${shortDate(r.at)} · ${fileSize(context, r.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (r.limited) {
                                Text(
                                    "Tronqué", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE67000), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(CircleShape).background(Color(0x26E67000)).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Filled.PlayCircle, "Lire", tint = RelayAccent, modifier = Modifier.size(30.dp))
                        }
                        DropdownMenu(menuFor == r.id, { menuFor = null }) {
                            DropdownMenuItem({ Text("Supprimer") }, { menuFor = null; pendingDelete = r }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                }
                item { Footnote("Appui long sur un enregistrement pour le supprimer.") }
            }
        }
    }
    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Supprimer cet enregistrement ?") },
            text = { Text("« ${r.name} » est supprimé du serveur Relay, pour tous vos appareils.") },
            confirmButton = {
                TextButton({
                    pendingDelete = null
                    scope.launch {
                        try {
                            app.client?.deleteRecording(r.id)
                            recordings = recordings.filter { it.id != r.id }
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Annuler") } },
        )
    }
}

/** Lecture d'un enregistrement : téléchargement, décodage, lecture, pause, déplacement et vitesse. */
class RecordingPlayer(val recording: Recording, private val scope: CoroutineScope) : ScreenHost {
    sealed class Phase {
        data object Loading : Phase()
        data object Ready : Phase()
        data class Failed(val message: String) : Phase()
    }

    var phase by mutableStateOf<Phase>(Phase.Loading); private set
    var position by mutableIntStateOf(0); private set
    var duration by mutableIntStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var speed by mutableStateOf(1.0)
    var skipIdle by mutableStateOf(true)

    override val screen = ScreenModel().apply {
        showsPointer = false
        mode = PointerMode.DIRECT
    }

    // Rendu hors du fil principal, dans l'ordre.
    private val renderThread = Executors.newSingleThreadExecutor { Thread(it, "relay-playback").apply { isDaemon = true } }
    private val renderer = renderThread.asCoroutineDispatcher()
    private val pool = FramePool()
    private var display = GuacDisplay(pool)
    private var timeline: RecordingTimeline? = null
    private var nextIndex = 0
    private var playJob: Job? = null

    init {
        screen.onRelease = { f -> f.bitmap?.let(pool::release) }
    }

    suspend fun load(client: RelayClient?) {
        if (phase != Phase.Loading || client == null) return
        try {
            val data = client.recordingData(recording.id)
            val decoded = withContext(Dispatchers.Default) { RecordingTimeline.decode(data) }
            if (decoded.frames.isEmpty()) {
                phase = Phase.Failed("Cet enregistrement est vide.")
                return
            }
            timeline = decoded
            duration = decoded.duration
            phase = Phase.Ready
            move(0)
            play()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: "Enregistrement illisible.")
        }
    }

    private val atEnd: Boolean get() = timeline?.let { nextIndex >= it.frames.size } ?: true

    fun toggle() = if (playing) pause() else play()

    fun play() {
        if (phase != Phase.Ready || playing) return
        playing = true
        playJob = scope.launch {
            if (atEnd) move(0)
            loop()
        }
    }

    fun pause() {
        playing = false
        playJob?.cancel()
        playJob = null
    }

    fun close() {
        pause()
        renderThread.shutdown()
    }

    private suspend fun render(from: Int, to: Int): Frame {
        val t = timeline!!
        return withContext(renderer) {
            for (i in from..to) for (c in t.frames[i].commands) display.apply(c)
            Frame(display.composite(), display.width, display.height)
        }
    }

    private suspend fun loop() {
        val t = timeline ?: return
        while (playing && scope.isActive && nextIndex < t.frames.size) {
            val wait = t.delay(nextIndex, speed, skipIdle)
            if (wait > 0.001) delay((wait * 1000).toLong())
            // Regroupe les trames rapprochées (moins de 15 ms) pour suivre la cadence.
            var end = nextIndex
            while (end + 1 < t.frames.size && t.delay(end + 1, speed, skipIdle) < 0.015) end++
            val frame = render(nextIndex, end)
            screen.show(frame)
            position = t.frames[end].time
            nextIndex = end + 1
        }
        playing = false
    }

    /** Se place à [time] (ms), puis reprend la lecture si elle était en cours. */
    fun seek(time: Int) {
        val wasPlaying = playing
        val running = playJob
        playing = false
        playJob = null
        scope.launch {
            running?.cancelAndJoin()
            move(time)
            if (wasPlaying) play()
        }
    }

    /** Affiche la trame de [time] (rejoue depuis le début pour reculer). */
    private suspend fun move(time: Int) {
        val t = timeline ?: return
        val target = t.frameIndex(time.coerceIn(0, duration))
        val frame = when {
            nextIndex == 0 || target < nextIndex - 1 -> {
                withContext(renderer) { display = GuacDisplay(pool) }
                render(0, target)
            }
            target >= nextIndex -> render(nextIndex, target)
            else -> null
        }
        frame?.let { screen.show(it) }
        nextIndex = target + 1
        position = t.frames[target].time
    }
}

/** Lecteur plein écran. */
@Composable
fun RecordingPlayerScreen(app: AppSession, recording: Recording, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { RecordingPlayer(recording, scope) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { player.load(app.client) }
    DisposableEffect(Unit) { onDispose { player.close() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Color(0xF0161B22)).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back, Modifier.testTag("playerClose")) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
            Text(recording.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortDate(recording.at), color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ RemoteScreenView(it, player) }, Modifier.fillMaxSize().testTag("playerScreen"))
            val card = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6161B22)).padding(24.dp)
            when (val p = player.phase) {
                RecordingPlayer.Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(card, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Téléchargement (${fileSize(context, recording.size)})…", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                is RecordingPlayer.Phase.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(card, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFA000), modifier = Modifier.size(36.dp))
                        Text(p.message, color = Color.White, textAlign = TextAlign.Center)
                        OutlinedButton(back) { Text("Fermer", color = Color.White) }
                    }
                }
                RecordingPlayer.Phase.Ready -> Unit
            }
        }
        if (player.phase == RecordingPlayer.Phase.Ready) {
            Column(Modifier.fillMaxWidth().background(Color(0xF0161B22)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Slider(
                    value = scrub ?: player.position.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { player.seek(it.toInt()) }; scrub = null },
                    valueRange = 0f..maxOf(player.duration, 1).toFloat(),
                    modifier = Modifier.testTag("playerSlider"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        RecordingTimeline.clock((scrub ?: player.position.toFloat()).toInt()), color = Color.White,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("playerPosition"),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton({ player.seek(player.position - 10_000) }) { Icon(Icons.Filled.Replay10, "Reculer de 10 secondes", tint = Color.White) }
                    IconButton({ player.toggle() }, Modifier.testTag("playerToggle")) {
                        Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.playing) "Pause" else "Lecture", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton({ player.seek(player.position + 10_000) }) { Icon(Icons.Filled.Forward10, "Avancer de 10 secondes", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text(RecordingTimeline.clock(player.duration), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        TextButton({ speedMenu = true }, Modifier.testTag("playerSpeed")) {
                            Icon(Icons.Filled.Speed, null, tint = Color.White)
                            Text(" " + speedLabel(player.speed), color = Color.White)
                        }
                        DropdownMenu(speedMenu, { speedMenu = false }) {
                            for (v in listOf(0.5, 1.0, 2.0, 4.0, 8.0)) DropdownMenuItem({ Text(speedLabel(v)) }, { speedMenu = false; player.speed = v })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        player.skipIdle, { player.skipIdle = !player.skipIdle }, { Text("Sauter les pauses") },
                        modifier = Modifier.testTag("playerSkipIdle"),
                    )
                }
            }
        }
    }
}

private fun speedLabel(v: Double) = if (v == 0.5) "×0,5" else "×${v.toInt()}"
