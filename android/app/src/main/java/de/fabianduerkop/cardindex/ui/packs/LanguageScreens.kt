// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.packs

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.packs.LanguageProgress
import de.fabianduerkop.cardindex.ui.containerViewModelFactory

@Composable
fun languagesViewModel(): LanguagesViewModel =
    viewModel(factory = containerViewModelFactory { container, _ -> LanguagesViewModel(container) })

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * First run (issue #39): the user picks the card languages to recognize, with the download size from the catalog.
 * The packs download in the background; "Not now" leaves the choice to Settings.
 */
@Composable
fun LanguagePickerScreen(onDone: () -> Unit, viewModel: LanguagesViewModel = languagesViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    var picked by rememberSaveable { mutableStateOf(setOf(if (deviceLanguage in PACK_LANGUAGES) deviceLanguage else "en")) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.packs_picker_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.packs_picker_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CatalogNotice(state.catalog, onRetry = viewModel::refreshCatalog)
            Column {
                state.rows.forEach { row ->
                    LanguageCheckRow(row, checked = row.code in picked) {
                        picked = if (row.code in picked) picked - row.code else picked + row.code
                    }
                }
            }
            if (picked.isNotEmpty() && state.catalog == CatalogStatus.Ready) {
                Text(
                    stringResource(R.string.packs_picker_total, size(state.downloadSize(picked))),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.finishPicker(picked)
                        onDone()
                    },
                    enabled = picked.isNotEmpty() && state.catalog == CatalogStatus.Ready,
                ) { Text(stringResource(R.string.packs_picker_download)) }
                TextButton(onClick = {
                    viewModel.finishPicker(emptySet())
                    onDone()
                }) { Text(stringResource(R.string.packs_picker_later)) }
            }
        }
    }
}

@Composable
private fun LanguageCheckRow(row: LanguageRow, checked: Boolean, onToggle: () -> Unit) {
    val enabled = row.available
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(languageName(row.code), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            row.downloadSize?.takeIf { enabled }?.let { size(it) } ?: stringResource(R.string.packs_not_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogNotice(status: CatalogStatus, onRetry: () -> Unit) {
    when (status) {
        CatalogStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.packs_catalog_loading), style = MaterialTheme.typography.bodySmall)
        }
        is CatalogStatus.Unavailable -> Column {
            Text(
                stringResource(if (status.needsAppUpdate) R.string.packs_catalog_update_app else R.string.packs_catalog_unreachable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (!status.needsAppUpdate) TextButton(onClick = onRetry) { Text(stringResource(R.string.packs_retry)) }
        }
        CatalogStatus.Ready -> Unit
    }
}

/**
 * Settings → Card languages: installed languages with version, storage and state; remove, download a broken pack
 * again, add more. The catalog is polled when this opens.
 */
@Composable
fun LanguagesSection(viewModel: LanguagesViewModel = languagesViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    Text(
        stringResource(R.string.packs_section_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CatalogNotice(state.catalog, onRetry = viewModel::refreshCatalog)
    val installed = state.installed
    if (installed.isEmpty()) {
        Text(stringResource(R.string.packs_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    installed.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider()
        InstalledLanguageRow(row, onRemove = { removing = row.code }, onRepair = { viewModel.repair(row.code) })
    }
    if (state.modelStorage > 0 || state.modelBroken) {
        Text(
            if (state.modelBroken) {
                stringResource(R.string.packs_model_broken)
            } else {
                stringResource(R.string.packs_model_storage, size(state.modelStorage))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.modelBroken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    state.syncError?.takeIf { !state.syncing }?.let {
        Text(
            stringResource(R.string.packs_sync_failed, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    OutlinedButton(
        onClick = { adding = true },
        enabled = state.addable.any { it.available },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(stringResource(R.string.packs_add)) }

    if (adding) AddLanguagesDialog(state, onDismiss = { adding = false }) { picked ->
        viewModel.add(picked)
        adding = false
    }
    removing?.let { code ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.packs_remove_title, languageName(code))) },
            text = { Text(stringResource(R.string.packs_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(code)
                    removing = null
                }) { Text(stringResource(R.string.packs_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun InstalledLanguageRow(row: LanguageRow, onRemove: () -> Unit, onRepair: () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(languageName(row.code), style = MaterialTheme.typography.titleSmall)
                Text(
                    statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.broken || row.progress is LanguageProgress.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (row.broken || row.progress is LanguageProgress.Failed) {
                TextButton(onClick = onRepair) { Text(stringResource(R.string.packs_download_again)) }
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.packs_remove)) }
        }
        (row.progress as? LanguageProgress.Downloading)?.let { p ->
            LinearProgressIndicator(
                progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun statusText(row: LanguageRow): String {
    val progress = row.progress
    return when {
        progress is LanguageProgress.Downloading -> stringResource(R.string.packs_downloading, size(progress.done), size(progress.total))
        progress is LanguageProgress.Installing -> stringResource(R.string.packs_installing)
        progress is LanguageProgress.Failed -> stringResource(R.string.packs_failed, progress.reason)
        row.broken -> stringResource(R.string.packs_broken)
        row.installedVersion != null -> stringResource(R.string.packs_installed, row.installedVersion, size(row.storage))
        !row.available -> stringResource(R.string.packs_not_available)
        else -> stringResource(R.string.packs_waiting)
    }
}

@Composable
private fun AddLanguagesDialog(state: LanguagesUiState, onDismiss: () -> Unit, onAdd: (Set<String>) -> Unit) {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packs_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.addable.forEach { row ->
                    LanguageCheckRow(row, checked = row.code in picked) {
                        picked = if (row.code in picked) picked - row.code else picked + row.code
                    }
                }
                if (picked.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.packs_picker_total, size(state.downloadSize(picked))))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(picked) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.packs_picker_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
