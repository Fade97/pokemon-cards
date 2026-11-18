// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.BuildConfig
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.alerts.PriceAlertWorker
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.backup.BackupFileType
import de.fabianduerkop.cardindex.debug.DebugTools
import java.time.LocalDate
import kotlinx.coroutines.launch
import de.fabianduerkop.cardindex.data.prices.PriceSourcePreference
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.ui.collections.NotificationsOffHint
import de.fabianduerkop.cardindex.ui.collections.PriceAlertRow
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.packs.LanguagesSection

/**
 * Settings (mockup "Settings"): one card per topic. About has the version, the privacy policy and the tester
 * feedback form; licenses come later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenConsent: () -> Unit = {},
    onOpenContributions: () -> Unit = {},
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
            val alerts by viewModel.alerts.collectAsStateWithLifecycle()
            alerts?.let { list ->
                SettingsSection(stringResource(R.string.alerts_title)) {
                    Text(
                        stringResource(if (list.isEmpty()) R.string.alerts_settings_none else R.string.alerts_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NotificationsOffHint(visible = list.isNotEmpty())
                    list.forEach { item ->
                        PriceAlertRow(item.alert, title = item.title, onDelete = { viewModel.deleteAlert(item.alert.uuid) })
                    }
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
            SettingsSection(stringResource(R.string.contribute_section)) {
                SwitchRow(
                    title = stringResource(R.string.contribute_toggle),
                    hint = stringResource(R.string.contribute_toggle_hint),
                    checked = state.contributing,
                    onCheckedChange = { on -> if (on) onOpenConsent() else viewModel.stopContributing() },
                )
                TextButton(onClick = onOpenContributions, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.contribute_mine))
                }
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
                    is BackupMessage.Imported -> {
                        val result = message.result
                        val copies = stringResource(
                            R.string.settings_imported,
                            result.copiesAdded,
                            result.copiesUpdated,
                            result.copiesUnchanged,
                            result.copiesSkipped,
                        )
                        val alerts = if (result.hasAlerts) {
                            stringResource(
                                R.string.settings_imported_alerts,
                                result.alertsAdded,
                                result.alertsUnchanged,
                                result.alertsSkipped,
                            )
                        } else {
                            null
                        }
                        ResultText(listOfNotNull(copies, alerts).joinToString("\n"))
                    }
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
                val debugPro by viewModel.debugPro.collectAsStateWithLifecycle()
                SettingsSection(stringResource(R.string.settings_debug_pro_title)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_pro),
                        hint = stringResource(R.string.settings_debug_pro_hint),
                        checked = debugPro,
                        onCheckedChange = viewModel::setDebugPro,
                    )
                    TextButton(onClick = { PriceAlertWorker.runNow(context) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.settings_debug_check_alerts))
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_about)) {
                ResultText(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                PrivacyPolicyLink()
                TesterFeedbackLink()
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

/** Opens the privacy policy (#70) in the browser; the page picks German or English from the browser's language. */
@Composable
internal fun PrivacyPolicyLink(modifier: Modifier = Modifier) {
    val url = BuildConfig.PRIVACY_POLICY_URL
    ExternalLink(
        url = url,
        label = stringResource(R.string.settings_privacy_policy),
        noBrowser = stringResource(R.string.settings_privacy_policy_no_browser, url),
        modifier = modifier,
    )
}

/** Opens the tester feedback form (#88), a GitHub issue form with the version and phone filled in. */
@Composable
private fun TesterFeedbackLink() {
    val url = TesterFeedback.url(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        device = TesterFeedback.device(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )
    ExternalLink(
        url = url,
        label = stringResource(R.string.settings_feedback),
        noBrowser = stringResource(R.string.settings_feedback_no_browser, TesterFeedback.NEW_ISSUE_URL),
    )
    ResultText(stringResource(R.string.settings_feedback_hint))
}

@Composable
private fun ExternalLink(url: String, label: String, noBrowser: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    TextButton(onClick = {
        // With no app for web links (no browser, or it is disabled) openUri throws instead of opening nothing:
        // Compose wraps the ActivityNotFoundException in an IllegalArgumentException.
        val opened = try {
            uriHandler.openUri(url)
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: ActivityNotFoundException) {
            false
        }
        if (!opened) {
            Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
        }
    }, modifier = modifier) {
        Text(label)
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
