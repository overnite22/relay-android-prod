package io.github.overnite22.relay.sessions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.guac.Keysym
import io.github.overnite22.relay.ui.RelayRed
import io.github.overnite22.relay.ui.fileSize
import io.github.overnite22.relay.ui.icon

private val Bar = Color(0xF0161B22)

/** Session plein écran (SSH, RDP, VNC, console Proxmox) avec barres d'outils. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(controller: SessionController, app: AppSession) {
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.isImeVisible
    var showHelp by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }

    BackHandler { app.minimizeSessions() }
    ImmersiveMode()
    NotificationPermission()

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        TopBar(controller, app)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(controller.uid) {
                AndroidView(
                    factory = { RemoteScreenView(it, controller) },
                    modifier = Modifier.fillMaxSize().testTag("remoteScreen"),
                )
            }
            Overlay(controller, app)
        }
        if (keyboardVisible) {
            KeyBar(controller)
        } else {
            BottomBar(controller, { showHelp = true }, { showFiles = true })
        }
    }
    if (showHelp) GestureHelp { showHelp = false }
    if (showFiles) FileBrowserSheet(controller) { showFiles = false }
    controller.sharedFile?.let { file ->
        LaunchedEffect(file) {
            controller.sharedFile = null
            LocalFiles.share(context, file)
        }
    }
}

/** Barres système masquées pendant la session (glisser depuis le bord pour les afficher). */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Android 13+ : la notification « sessions ouvertes » demande une autorisation (une seule fois). */
@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("prefs", 0)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun TopBar(controller: SessionController, app: AppSession) {
    var switcher by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Bar).statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ app.closeSession(controller) }, Modifier.testTag("sessionClose")) { Icon(Icons.Filled.Close, "Fermer la session", tint = Color.White) }
        IconButton({ app.minimizeSessions() }, Modifier.testTag("sessionMinimize")) { Icon(Icons.Filled.KeyboardArrowDown, "Réduire", tint = Color.White) }
        Text(
            controller.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp), textAlign = TextAlign.Center,
        )
        if (controller.target.recording) Icon(Icons.Filled.FiberManualRecord, "Enregistrement en cours", tint = RelayRed, modifier = Modifier.size(18.dp))
        if (app.sessions.size > 1) {
            Box {
                TextButton({ switcher = true }, Modifier.testTag("sessionSwitcher")) {
                    Icon(Icons.Filled.ViewAgenda, null, tint = Color.White)
                    Text(" ${app.sessions.size}", color = Color.White)
                }
                DropdownMenu(switcher, { switcher = false }) {
                    for (s in app.sessions) {
                        DropdownMenuItem(
                            { Text(s.title, fontWeight = if (s.uid == controller.uid) FontWeight.Bold else FontWeight.Normal) },
                            { switcher = false; app.presentedSessionId = s.uid },
                            leadingIcon = { Icon(s.target.kind.icon, null) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Réduire toutes les sessions") }, { switcher = false; app.minimizeSessions() })
                }
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun BottomBar(controller: SessionController, onHelp: () -> Unit, onFiles: () -> Unit) {
    var pointerMenu by remember { mutableStateOf(false) }
    var clipMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Bar).navigationBarsPadding().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ controller.toggleKeyboard(false) }, Modifier.testTag("sessionKeyboard")) { Icon(Icons.Filled.Keyboard, "Clavier", tint = Color.White) }
        Box {
            IconButton({ pointerMenu = true }, Modifier.testTag("sessionPointer")) {
                Icon(if (controller.mode == PointerMode.TOUCHPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp, "Pointeur", tint = Color.White)
            }
            DropdownMenu(pointerMenu, { pointerMenu = false }) {
                for (m in PointerMode.entries) {
                    DropdownMenuItem(
                        { Text(m.title, fontWeight = if (m == controller.mode) FontWeight.Bold else FontWeight.Normal) },
                        { pointerMenu = false; controller.updateMode(m) },
                        leadingIcon = { Icon(if (m == PointerMode.TOUCHPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp, null) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem({ Text("Taille d'origine") }, { pointerMenu = false; controller.resetZoom() })
            }
        }
        if (controller.filesystem != null) {
            IconButton(onFiles, Modifier.testTag("sessionFiles")) { Icon(Icons.Filled.Folder, "Fichiers", tint = Color.White) }
        }
        Box {
            IconButton({ clipMenu = true }, Modifier.testTag("sessionClipboard")) { Icon(Icons.Filled.ContentPaste, "Presse-papiers", tint = Color.White) }
            DropdownMenu(clipMenu, { clipMenu = false }) {
                DropdownMenuItem({ Text("Envoyer le presse-papiers") }, { clipMenu = false; controller.sendClipboard() })
                DropdownMenuItem({ Text("Taper le presse-papiers") }, { clipMenu = false; controller.typeClipboard() })
            }
        }
        Box {
            IconButton({ moreMenu = true }, Modifier.testTag("sessionMore")) { Icon(Icons.Filled.MoreVert, "Plus", tint = Color.White) }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                if (controller.target.kind != EntryKind.SSH) {
                    DropdownMenuItem({ Text("Ctrl+Alt+Suppr") }, { moreMenu = false; controller.sendCtrlAltDel() })
                    DropdownMenuItem({ Text("Touche Windows") }, { moreMenu = false; controller.sendKey(emptyList(), Keysym.SUPER_LEFT) })
                    DropdownMenuItem({ Text("Alt+Tab") }, { moreMenu = false; controller.sendKey(listOf(Keysym.ALT_LEFT), Keysym.TAB) })
                }
                DropdownMenuItem({ Text("Ctrl+C") }, { moreMenu = false; controller.sendKey(listOf(Keysym.CONTROL_LEFT), 0x63) })
                DropdownMenuItem({ Text("Ctrl+D") }, { moreMenu = false; controller.sendKey(listOf(Keysym.CONTROL_LEFT), 0x64) })
                DropdownMenuItem({ Text("Ctrl+Z") }, { moreMenu = false; controller.sendKey(listOf(Keysym.CONTROL_LEFT), 0x7A) })
                DropdownMenuItem({ Text("Échap") }, { moreMenu = false; controller.sendKey(emptyList(), Keysym.ESCAPE) })
                HorizontalDivider()
                DropdownMenuItem({ Text("Reconnecter") }, { moreMenu = false; controller.reconnect() }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                DropdownMenuItem({ Text("Aide des gestes") }, { moreMenu = false; onHelp() })
            }
        }
    }
}

/** Barre au-dessus du clavier : touches absentes du clavier Android et modificateurs collants. */
@Composable
private fun KeyBar(controller: SessionController) {
    var fn by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp).testTag("keyBar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyButton("Échap", "keyEsc") { controller.sendSpecial(Keysym.ESCAPE) }
        KeyButton("Ctrl", "keyCtrl", active = controller.ctrl) { controller.ctrl = !controller.ctrl }
        KeyButton("Alt", "keyAlt", active = controller.alt) { controller.alt = !controller.alt }
        KeyButton("Tab", "keyTab") { controller.sendSpecial(Keysym.TAB) }
        KeyButton("←", "keyLeft") { controller.sendSpecial(Keysym.LEFT) }
        KeyButton("↑", "keyUp") { controller.sendSpecial(Keysym.UP) }
        KeyButton("↓", "keyDown") { controller.sendSpecial(Keysym.DOWN) }
        KeyButton("→", "keyRight") { controller.sendSpecial(Keysym.RIGHT) }
        KeyButton("Début", "keyHome") { controller.sendSpecial(Keysym.HOME) }
        KeyButton("Fin", "keyEnd") { controller.sendSpecial(Keysym.END) }
        KeyButton("Pg↑", "keyPageUp") { controller.sendSpecial(Keysym.PAGE_UP) }
        KeyButton("Pg↓", "keyPageDown") { controller.sendSpecial(Keysym.PAGE_DOWN) }
        KeyButton("Suppr", "keyDelete") { controller.sendSpecial(Keysym.DELETE) }
        for (c in listOf("|", "~", "/", "-", "`", "\\")) KeyButton(c, "keyChar${c.codePointAt(0)}") { controller.insertText(c) }
        Box {
            KeyButton("F1–F12", "keyFn") { fn = true }
            DropdownMenu(fn, { fn = false }) {
                for (n in 1..12) DropdownMenuItem({ Text("F$n") }, { fn = false; controller.sendSpecial(Keysym.function(n)) })
            }
        }
        KeyButton("Masquer", "keyHide") { controller.toggleKeyboard(true) }
    }
}

@Composable
private fun KeyButton(label: String, tag: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = if (active) androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)
    else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2A313C), contentColor = Color.White)
    FilledTonalButton(
        onClick, colors = colors, shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.widthIn(min = 44.dp).testTag(tag),
    ) { Text(label, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun Overlay(controller: SessionController, app: AppSession) {
    val context = LocalContext.current
    val card = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6161B22)).padding(24.dp)
    when (val phase = controller.phase) {
        SessionPhase.Preparing, SessionPhase.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(card.testTag("sessionConnecting"), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.size(12.dp))
                Text("Connexion à ${controller.target.title}…", color = Color.White)
                if (controller.target.recording) Text("● Session enregistrée", color = RelayRed, style = MaterialTheme.typography.bodySmall)
            }
        }
        is SessionPhase.Ended -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(card.widthIn(max = 340.dp).testTag("sessionEnded"), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (phase.error == null) Icons.Filled.LinkOff else Icons.Filled.ErrorOutline, null,
                    tint = if (phase.error == null) Color.Gray else Color(0xFFFFA000), modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(if (phase.error == null) "Session terminée" else "Session interrompue", color = Color.White, fontWeight = FontWeight.SemiBold)
                phase.error?.let { Text(it, color = Color.LightGray, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.size(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ app.closeSession(controller) }) { Text("Fermer", color = Color.White) }
                    Button({ controller.reconnect() }, Modifier.testTag("sessionReconnect")) {
                        Icon(Icons.Filled.Refresh, null)
                        Text(" Reconnecter")
                    }
                }
            }
        }
        SessionPhase.Connected -> {
            controller.incoming?.let { (name, bytes) ->
                Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Row(Modifier.clip(CircleShape).background(Color(0xE6161B22)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, tint = Color.White)
                        Text(" Réception de $name : ${fileSize(context, bytes.toLong())}", color = Color.White)
                    }
                }
            }
            controller.toast?.let { message ->
                Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        message, color = Color.White,
                        modifier = Modifier.clip(CircleShape).background(Color(0xE6161B22)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("sessionToast"),
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureHelp(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
        title = { Text("Gestes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pavé tactile (RDP, VNC)", fontWeight = FontWeight.Bold)
                Text("• Glisser un doigt : déplace le pointeur\n• Toucher : clic gauche au pointeur\n• Toucher à deux doigts : clic droit\n• Appui long puis glisser : glisser-déposer\n• Glisser à deux doigts : molette")
                Text("Toucher direct (SSH)", fontWeight = FontWeight.Bold)
                Text("• Toucher : clic à cet endroit\n• Glisser un doigt : sélectionner du texte (copié automatiquement)\n• Appui long : clic droit\n• Glisser à deux doigts : défiler, ou déplacer la vue zoomée")
                Text("Partout", fontWeight = FontWeight.Bold)
                Text("• Pincer : zoomer\n• Bouton Clavier : clavier avec Échap, Tab, Ctrl, Alt, flèches et F1–F12\n• Retour : réduire la session sans la fermer")
            }
        },
    )
}
