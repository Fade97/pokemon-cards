// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.packs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.packs.LanguageProgress
import de.fabianduerkop.cardindex.ui.components.LumifoilWordmark
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.TextActionInset
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.settings.ConfirmDialog
import de.fabianduerkop.cardindex.ui.settings.SettingsHint
import de.fabianduerkop.cardindex.ui.settings.SwitchRow
import de.fabianduerkop.cardindex.ui.theme.Spacing

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
    val deviceLanguage = packLanguageOf(LocalConfiguration.current.locales[0])
    var touched by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    val picked = state.pickerChoice(touched, deviceLanguage)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LumifoilWordmark()
            Text(
                stringResource(R.string.packs_picker_title),
                Modifier.padding(top = Spacing.sm).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.packs_picker_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CatalogNotice(state.catalog, onRetry = viewModel::refreshCatalog)
            Column {
                state.rows.forEach { row ->
                    LanguageCheckRow(row, checked = row.code in picked) {
                        touched = if (row.code in picked) picked - row.code else picked + row.code
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (picked.isNotEmpty() && state.catalog == CatalogStatus.Ready) {
                Text(
                    stringResource(R.string.packs_picker_total, size(state.downloadSize(picked))),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            MobileDataSwitch(state.mobileData, viewModel::setMobileData)
            PrimaryButton(
                stringResource(R.string.packs_picker_download),
                onClick = {
                    viewModel.finishPicker(picked)
                    onDone()
                },
                enabled = picked.isNotEmpty() && state.catalog == CatalogStatus.Ready,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            )
            TextAction(
                stringResource(R.string.packs_picker_later),
                onClick = {
                    viewModel.finishPicker(emptySet())
                    onDone()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** "Use mobile data for language packs" (#137), in the first-run picker and in Settings. Off by default. */
@Composable
private fun MobileDataSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        title = stringResource(R.string.packs_mobile_data),
        hint = stringResource(R.string.packs_mobile_data_hint),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/** A language to pick, with its download size. The whole row is the checkbox, so it is one 48 dp target. */
@Composable
private fun LanguageCheckRow(row: LanguageRow, checked: Boolean, onToggle: () -> Unit) {
    val enabled = row.available
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .toggleable(value = checked && enabled, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = Spacing.md))
        Text(
            languageName(row.code),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            row.downloadSize?.takeIf { enabled }?.let { size(it) } ?: stringResource(R.string.packs_not_available),
            Modifier.padding(start = Spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Loading, offline or app-too-old state of the language catalog. Offline offers a retry. */
@Composable
private fun CatalogNotice(status: CatalogStatus, onRetry: () -> Unit) {
    when (status) {
        CatalogStatus.Loading -> Row(
            Modifier.heightIn(min = Spacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            SettingsHint(stringResource(R.string.packs_catalog_loading))
        }
        is CatalogStatus.Unavailable -> if (status.needsAppUpdate) {
            StatusNotice(stringResource(R.string.packs_catalog_update_app), tone = NoticeTone.WARNING)
        } else {
            StatusNotice(
                stringResource(R.string.packs_catalog_unreachable),
                tone = NoticeTone.ERROR,
                actionLabel = stringResource(R.string.packs_retry),
                onAction = onRetry,
            )
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

    SettingsHint(stringResource(R.string.packs_section_hint))
    CatalogNotice(state.catalog, onRetry = viewModel::refreshCatalog)
    val installed = state.installed
    if (installed.isEmpty()) {
        StatusNotice(stringResource(R.string.packs_none), tone = NoticeTone.WARNING)
    }
    if (installed.isNotEmpty()) {
        Column {
            installed.forEach { row ->
                InstalledLanguageRow(row, state.mobileData, onRemove = { removing = row.code }, onRepair = { viewModel.repair(row.code) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (state.modelBroken) {
        StatusNotice(stringResource(R.string.packs_model_broken), tone = NoticeTone.ERROR)
    } else if (state.modelStorage > 0) {
        SettingsHint(stringResource(R.string.packs_model_storage, size(state.modelStorage)))
    }
    // When the catalog itself is unreachable that notice already says why; the sync error would repeat it.
    state.syncError?.takeIf { !state.syncing && state.catalog == CatalogStatus.Ready }?.let {
        StatusNotice(stringResource(R.string.packs_sync_failed, it), tone = NoticeTone.ERROR)
    }
    SecondaryButton(
        stringResource(R.string.packs_add),
        onClick = { adding = true },
        enabled = state.addable.any { it.available },
        icon = R.drawable.ic_add,
    )
    MobileDataSwitch(state.mobileData, viewModel::setMobileData)

    if (adding) AddLanguagesDialog(state, onDismiss = { adding = false }) { picked ->
        viewModel.add(picked)
        adding = false
    }
    removing?.let { code ->
        ConfirmDialog(
            title = stringResource(R.string.packs_remove_title, languageName(code)),
            text = stringResource(R.string.packs_remove_text),
            confirmLabel = stringResource(R.string.packs_remove),
            onConfirm = {
                viewModel.remove(code)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun InstalledLanguageRow(row: LanguageRow, mobileData: Boolean, onRemove: () -> Unit, onRepair: () -> Unit) {
    val failed = row.broken || row.progress is LanguageProgress.Failed
    val name = languageName(row.code)
    Column(Modifier.padding(vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    statusText(row, mobileData),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Ends on the right edge like the switches below it, in the error colour: it removes recognition (#218).
            TextAction(
                stringResource(R.string.packs_remove),
                onClick = onRemove,
                modifier = Modifier.offset(x = TextActionInset),
                contentDescription = stringResource(R.string.packs_remove_language, name),
                destructive = true,
            )
        }
        (row.progress as? LanguageProgress.Downloading)?.let { p ->
            LinearProgressIndicator(
                progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            )
        }
        if (failed) {
            SecondaryButton(
                stringResource(R.string.packs_download_again),
                onClick = onRepair,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun statusText(row: LanguageRow, mobileData: Boolean): String {
    val progress = row.progress
    return when {
        progress is LanguageProgress.Downloading -> stringResource(R.string.packs_downloading, size(progress.done), size(progress.total))
        progress is LanguageProgress.Installing -> stringResource(R.string.packs_installing)
        progress is LanguageProgress.Failed -> stringResource(R.string.packs_failed, progress.reason)
        row.broken -> stringResource(R.string.packs_broken)
        row.installedVersion != null -> stringResource(R.string.packs_installed, row.installedVersion, size(row.storage))
        !row.available -> stringResource(R.string.packs_not_available)
        // Without the mobile data switch a pending download waits for an unmetered network (#137).
        else -> stringResource(if (mobileData) R.string.packs_waiting else R.string.packs_waiting_wifi)
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
                    Text(
                        stringResource(R.string.packs_picker_total, size(state.downloadSize(picked))),
                        Modifier.padding(top = Spacing.sm),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextAction(stringResource(R.string.packs_picker_download), onClick = { onAdd(picked) }, enabled = picked.isNotEmpty())
        },
        dismissButton = { TextAction(stringResource(android.R.string.cancel), onClick = onDismiss) },
    )
}
