package io.github.overnite22.relay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Folder

/** Liste déroulante « libellé : valeur ». */
@Composable
fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, tag: String = "", onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(0.45f))
            Text(
                options.firstOrNull { it.first == selected }?.second ?: "—",
                Modifier.weight(0.55f), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(open, { open = false }) {
            for ((value, title) in options) {
                DropdownMenuItem({ Text(title) }, { open = false; onSelect(value) })
            }
        }
    }
}

/** Boutons segmentés (choix exclusif). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, tag: String = "", onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag)) {
        options.forEachIndexed { i, (value, title) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.testTag("$tag-$i"),
            ) { Text(title, maxLines = 1) }
        }
    }
}

/** Interrupteur avec libellé et explication facultative. */
@Composable
fun SwitchRow(label: String, checked: Boolean, detail: String = "", tag: String = "", onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked, onChange)
    }
}

/** Dossiers à plat, indentés par profondeur (sélecteurs). */
fun AppSession.flattenedFolders(excluding: String? = null): List<Pair<Folder, Int>> {
    val out = ArrayList<Pair<Folder, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (f in tree.folders(parent)) {
            if (f.id == excluding) continue
            out.add(f to depth)
            walk(f.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

@Composable
fun FolderPicker(app: AppSession, title: String, selected: String?, excluding: String? = null, rootLabel: String = "Racine", tag: String = "", onSelect: (String?) -> Unit) {
    val options = listOf<Pair<String?, String>>(null to rootLabel) +
        app.flattenedFolders(excluding).map { (f, d) -> f.id to " ".repeat(d) + f.name }
    ChoiceField(title, options, selected, tag, onSelect)
}

/** Note d'explication sous une section. */
@Composable
fun Footnote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
