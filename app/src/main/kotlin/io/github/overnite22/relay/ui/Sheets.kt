package io.github.overnite22.relay.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Feuille plein écran (formulaires, navigateur de fichiers) : Annuler à gauche, action principale à droite. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissLabel: String = "Annuler",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.imePadding(),
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { TextButton(onDismiss, Modifier.testTag("sheetCancel")) { Text(dismissLabel) } },
                        actions = actions,
                    )
                },
                content = content,
            )
        }
    }
}

/** Bouton d'action principale d'une feuille (Enregistrer…). */
@Composable
fun SheetAction(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(label, fontWeight = FontWeight.Bold) }
}
