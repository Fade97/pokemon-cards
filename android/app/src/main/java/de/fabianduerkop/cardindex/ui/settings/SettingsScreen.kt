// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.backup.BackupFileType
import de.fabianduerkop.cardindex.debug.DebugTools
import java.time.LocalDate
import kotlinx.coroutines.launch
import de.fabianduerkop.cardindex.data.prices.PriceSourcePreference
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.packs.LanguagesSection

/** Settings (mockup "Settings"): one card per topic. Licenses come later. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = containerViewModelFactory { container, _ -> SettingsViewModel(container) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(resolver, it, BackupFileType.JSON) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.export(resolver, it, BackupFileType.CSV) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(resolver, it) }
    }
    TopLevelScaffold(title = stringResource(R.string.nav_settings), modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_languages)) { LanguagesSection() }
            SettingsSection(stringResource(R.string.settings_prices)) {
                PriceSourcePicker(state, viewModel::setPriceSource)
                Text(
                    stringResource(R.string.settings_price_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = viewModel::refreshPrices, enabled = !state.refreshing) {
                        Text(stringResource(R.string.settings_price_refresh))
                    }
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                when (val result = state.refreshResult) {
                    is RefreshResult.Updated -> ResultText(stringResource(R.string.settings_price_refreshed, result.cards))
                    is RefreshResult.Failed -> ResultText(stringResource(R.string.settings_price_failed))
                    null -> Unit
                }
            }
            SettingsSection(stringResource(R.string.settings_images)) {
                SwitchRow(
                    title = stringResource(R.string.settings_prefer_own_photos),
                    hint = stringResource(R.string.settings_prefer_own_photos_hint),
                    checked = state.preferOwnPhotos,
                    onCheckedChange = viewModel::setPreferOwnPhotos,
                )
            }
            SettingsSection(stringResource(R.string.settings_backup)) {
                Text(
                    stringResource(R.string.settings_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val stamp = LocalDate.now().toString()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { exportJson.launch("cardindex-backup-$stamp.json") }, enabled = !state.backupBusy) {
                        Text(stringResource(R.string.settings_export_json))
                    }
                    OutlinedButton(onClick = { exportCsv.launch("cardindex-backup-$stamp.csv") }, enabled = !state.backupBusy) {
                        Text(stringResource(R.string.settings_export_csv))
                    }
                    OutlinedButton(
                        // Some file managers label CSV as plain text or don't type it at all.
                        onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        enabled = !state.backupBusy,
                    ) { Text(stringResource(R.string.settings_import)) }
                }
                when (val message = state.backupMessage) {
                    is BackupMessage.Exported -> ResultText(
                        pluralStringResource(R.plurals.settings_exported, message.copies, message.copies),
                    )
                    is BackupMessage.Imported -> ResultText(
                        stringResource(
                            R.string.settings_imported,
                            message.result.copiesAdded,
                            message.result.copiesUpdated,
                            message.result.copiesUnchanged,
                            message.result.copiesSkipped,
                        ),
                    )
                    is BackupMessage.Failed -> ResultText(stringResource(R.string.settings_backup_failed, message.reason))
                    null -> Unit
                }
            }
            if (DebugTools.AVAILABLE) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                TextButton(
                    onClick = { scope.launch { DebugTools.addSampleData(context.appContainer) } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.collections_debug_sample)) }
            }
            PlaceholderText(
                text = stringResource(R.string.settings_placeholder),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceSourcePicker(state: SettingsUiState, onSelect: (PriceSourcePreference) -> Unit) {
    Text(stringResource(R.string.settings_main_price), style = MaterialTheme.typography.titleSmall)
    val options = PriceSourcePreference.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = state.priceSource == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    activeContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            PriceSourcePreference.AUTO -> R.string.settings_price_auto
                            PriceSourcePreference.CARDMARKET -> R.string.settings_price_cardmarket
                            PriceSourcePreference.TCGPLAYER -> R.string.settings_price_tcgplayer
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
    if (state.priceSource == PriceSourcePreference.AUTO) {
        val region = stringResource(
            if (state.regionSource == PriceSource.CARDMARKET) R.string.settings_price_cardmarket else R.string.settings_price_tcgplayer,
        )
        ResultText(stringResource(R.string.settings_price_auto_hint, region))
    }
}

@Composable
private fun ResultText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
internal fun SwitchRow(title: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
