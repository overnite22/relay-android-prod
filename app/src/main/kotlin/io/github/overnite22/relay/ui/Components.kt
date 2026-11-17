package io.github.overnite22.relay.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.kit.ConnectionTest
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.RelayDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val EntryKind.icon: ImageVector
    get() = when (this) {
        EntryKind.RDP -> Icons.Filled.Computer
        EntryKind.SSH -> Icons.Filled.Terminal
        EntryKind.VNC -> Icons.Filled.DesktopWindows
        EntryKind.WEB -> Icons.Filled.Public
        EntryKind.PVE -> Icons.Filled.Dns
        EntryKind.OTHER -> Icons.Filled.QuestionMark
    }

/** Pastille de type (icône sur fond coloré, comme l'arborescence de Relay web). */
@Composable
fun KindBadge(kind: EntryKind, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.verticalGradient(listOf(kind.color.copy(alpha = 0.85f), kind.color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, contentDescription = kind.title, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Témoin de disponibilité : vert si le port répond, rouge sinon, gris sans mesure. */
@Composable
fun StatusDot(test: ConnectionTest?) {
    val color = when {
        test == null -> Color.Gray.copy(alpha = 0.5f)
        test.reachable -> RelayGreen
        else -> RelayRed
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape))
}

/** Message d'erreur ou d'information. */
@Composable
fun StatusBanner(message: String, info: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (info) MaterialTheme.colorScheme.primaryContainer else Color(0x33E3B341))
            .padding(12.dp)
            .testTag("statusBanner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (info) Icons.Filled.Info else Icons.Filled.Warning, null,
            tint = if (info) MaterialTheme.colorScheme.primary else Color(0xFFD28A00),
        )
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Écran vide avec titre et explication. */
@Composable
fun EmptyState(title: String, detail: String = "", icon: ImageVector? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Copie dans le presse-papiers, marquée sensible (masquée dans l'aperçu d'Android 13+). */
fun copyToClipboard(context: android.content.Context, label: String, value: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(clip)
    if (sensitive && Build.VERSION.SDK_INT >= 28) {
        // Effacé après 90 s s'il n'a pas été remplacé entre-temps (comme l'app iPhone).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == value) cm.clearPrimaryClip() }
        }, 90_000)
    }
}

/** Bouton de copie avec confirmation visuelle. */
@Composable
fun CopyButton(label: String, sensitive: Boolean = false, value: suspend () -> String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(onClick = {
        scope.launch {
            val v = value()
            if (!v.isNullOrEmpty()) {
                copyToClipboard(context, label, v, sensitive)
                copied = true
            }
        }
    }) {
        Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, contentDescription = "Copier $label")
    }
}

/** Barre de titre commune, avec retour si [onBack] est fourni. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = floating,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

/** Titre de section d'une liste. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Date relative lisible à partir d'une date RFC 3339 du serveur. */
fun relativeDate(text: String): String {
    val d = RelayDate.parse(text) ?: return text
    return DateUtils.getRelativeTimeSpanString(d.toInstant().toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

fun shortDate(text: String): String {
    val d = RelayDate.parse(text) ?: return text
    return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", java.util.Locale.FRENCH)
        .format(d.atZoneSameInstant(java.time.ZoneId.systemDefault()))
}

fun fileSize(context: android.content.Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** Markdown en ligne (gras, italique, code, liens) pour les notes. */
fun markdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|\[(.+?)]\((https?://[^)\s]+)\)""")
    var i = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1] + g[2]) }
            g[3].isNotEmpty() || g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3] + g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))) { append(g[5]) }
            else -> withLink(androidx.compose.ui.text.LinkAnnotation.Url(g[7])) {
                withStyle(SpanStyle(color = linkColor)) { append(g[6]) }
            }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}
