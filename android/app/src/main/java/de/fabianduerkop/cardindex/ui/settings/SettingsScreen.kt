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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.BuildConfig
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.alerts.PriceAlertWorker
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.backup.BackupFileType
import de.fabianduerkop.cardindex.data.prices.PriceSourcePreference
import de.fabianduerkop.cardindex.data.prices.RefreshResult
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.ui.collections.NotificationsOffHint
import de.fabianduerkop.cardindex.ui.collections.PriceAlertRow
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.packs.LanguagesSection
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import de.fabianduerkop.cardindex.ui.components.TextActionInset

/**
 * Settings in Pearl (#100): one section per topic on the page, as in the study's settings, built from the shared
 * rows, buttons and notices. What each control stores is unchanged. About has the version, the privacy policy and the
 * tester feedback form, and the open-source licenses (#136).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenConsent: () -> Unit = {},
    onOpenContributions: () -> Unit = {},
    onOpenDesignGallery: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
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
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xl),
        ) {
            SettingsSection(stringResource(R.string.settings_languages)) { LanguagesSection() }
            SettingsSection(stringResource(R.string.settings_prices)) {
                PriceSourcePicker(state, viewModel::setPriceSource)
                SettingsHint(stringResource(R.string.settings_price_hint))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton(
                        stringResource(R.string.settings_price_refresh),
                        onClick = viewModel::refreshPrices,
                        enabled = !state.refreshing,
                    )
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                when (val result = state.refreshResult) {
                    is RefreshResult.Updated -> StatusNotice(stringResource(R.string.settings_price_refreshed, result.cards))
                    is RefreshResult.Failed -> StatusNotice(stringResource(R.string.settings_price_failed), tone = NoticeTone.ERROR)
                    null -> Unit
                }
            }
            // Pro (#76): only listed when the entitlement is active; nothing here grants or checks access.
            val alerts by viewModel.alerts.collectAsStateWithLifecycle()
            alerts?.let { list ->
                SettingsSection(stringResource(R.string.alerts_title)) {
                    SettingsHint(stringResource(if (list.isEmpty()) R.string.alerts_settings_none else R.string.alerts_settings_hint))
                    NotificationsOffHint(visible = list.isNotEmpty())
                    Column {
                        list.forEach { item ->
                            PriceAlertRow(item.alert, title = item.title, onDelete = { viewModel.deleteAlert(item.alert.uuid) })
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_appearance)) {
                // Read-only, so plain text: a row with a value looked like something to tap (#218).
                SettingsHint(stringResource(R.string.settings_theme_follows))
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
                // Turning this on only opens the consent screen; contributing starts when the user agrees there.
                SwitchRow(
                    title = stringResource(R.string.contribute_toggle),
                    hint = stringResource(R.string.contribute_toggle_hint),
                    checked = state.contributing,
                    onCheckedChange = { on -> if (on) onOpenConsent() else viewModel.stopContributing() },
                )
                SecondaryButton(stringResource(R.string.contribute_mine), onClick = onOpenContributions)
            }
            SettingsSection(stringResource(R.string.settings_backup)) {
                SettingsHint(stringResource(R.string.settings_backup_hint))
                val stamp = LocalDate.now().toString()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SecondaryButton(
                        stringResource(R.string.settings_export_json),
                        onClick = { exportJson.launch("cardindex-backup-$stamp.json") },
                        enabled = !state.backupBusy,
                    )
                    SecondaryButton(
                        stringResource(R.string.settings_export_csv),
                        onClick = { exportCsv.launch("cardindex-backup-$stamp.csv") },
                        enabled = !state.backupBusy,
                    )
                    SecondaryButton(
                        stringResource(R.string.settings_import),
                        // Some file managers label CSV as plain text or don't type it at all.
                        onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        enabled = !state.backupBusy,
                    )
                }
                if (state.backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (val message = state.backupMessage) {
                    is BackupMessage.Exported -> StatusNotice(
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
                        StatusNotice(listOfNotNull(copies, alerts).joinToString("\n"))
                    }
                    is BackupMessage.Failed -> StatusNotice(
                        stringResource(R.string.settings_backup_failed, message.reason),
                        tone = NoticeTone.ERROR,
                    )
                    null -> Unit
                }
            }
            if (DebugTools.AVAILABLE) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                SettingsSection(stringResource(R.string.settings_debug_title)) {
                    // Links start on the text column, not 12 dp inside it.
                    Column(Modifier.offset(x = -TextActionInset)) {
                        TextAction(
                            stringResource(R.string.collections_debug_sample),
                            onClick = { scope.launch { DebugTools.addSampleData(context.appContainer) } },
                        )
                        TextAction(stringResource(R.string.settings_debug_design_gallery), onClick = onOpenDesignGallery)
                    }
                }
                val debugPro by viewModel.debugPro.collectAsStateWithLifecycle()
                SettingsSection(stringResource(R.string.settings_debug_pro_title)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_pro),
                        hint = stringResource(R.string.settings_debug_pro_hint),
                        checked = debugPro,
                        onCheckedChange = viewModel::setDebugPro,
                    )
                    TextAction(
                        stringResource(R.string.settings_debug_check_alerts),
                        onClick = { PriceAlertWorker.runNow(context) },
                        alignToText = true,
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_about)) {
                SettingsHint(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                Column {
                    PrivacyPolicyLink()
                    ImprintLink()
                    SourceCodeLink()
                    TextAction(stringResource(R.string.settings_licenses), onClick = onOpenLicenses, alignToText = true)
                    TesterFeedbackLink()
                }
                val appName = stringResource(R.string.app_name)
                SettingsHint(stringResource(R.string.settings_disclaimer, appName))
                SettingsHint(stringResource(R.string.settings_credits, appName))
            }
        }
    }
}

/**
 * The main price as three stacked options, each naming its currency (#100). Segments cut "Cardmarket, EUR" to
 * "Cardmarket," at normal font sizes already; rows wrap instead.
 */
@Composable
private fun PriceSourcePicker(state: SettingsUiState, onSelect: (PriceSourcePreference) -> Unit) {
    Text(stringResource(R.string.settings_main_price), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        PriceSourcePreference.entries.forEach { option ->
            val (title, subtitle) = when (option) {
                PriceSourcePreference.AUTO -> stringResource(R.string.settings_price_auto) to stringResource(
                    R.string.settings_price_auto_sub,
                    stringResource(
                        if (state.regionSource == PriceSource.CARDMARKET) R.string.settings_price_cardmarket else R.string.settings_price_tcgplayer,
                    ),
                )
                PriceSourcePreference.CARDMARKET ->
                    stringResource(R.string.settings_price_cardmarket_name) to stringResource(R.string.settings_price_eur)
                PriceSourcePreference.TCGPLAYER ->
                    stringResource(R.string.settings_price_tcgplayer_name) to stringResource(R.string.settings_price_usd)
            }
            ChoiceRow(title, subtitle, selected = state.priceSource == option, onClick = { onSelect(option) })
        }
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
        alignToText = true,
    )
}

/** The provider's imprint (§ 5 DDG, #226) on the app's website. */
@Composable
private fun ImprintLink() {
    ExternalLink(
        url = IMPRINT_URL,
        label = stringResource(R.string.settings_imprint),
        noBrowser = stringResource(R.string.settings_imprint_no_browser, IMPRINT_URL),
        alignToText = true,
    )
}

/** The app's source, which GPLv3 asks to offer (#130). */
@Composable
private fun SourceCodeLink() {
    ExternalLink(
        url = SOURCE_CODE_URL,
        label = stringResource(R.string.settings_source_code),
        noBrowser = stringResource(R.string.settings_source_code_no_browser, SOURCE_CODE_URL),
        alignToText = true,
    )
}

private const val SOURCE_CODE_URL = "https://github.com/Fade97/pokemon-cards"
private const val IMPRINT_URL = "https://foilark.app/imprint"

/** Opens the tester feedback form (#88), a GitHub issue form with the version and phone filled in. */
@Composable
private fun TesterFeedbackLink() {
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val device = TesterFeedback.device(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())
    val android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    ExternalLink(
        url = TesterFeedback.url(appVersion, device, android),
        label = stringResource(R.string.settings_feedback),
        noBrowser = stringResource(R.string.settings_feedback_no_browser, TesterFeedback.NEW_ISSUE_URL),
        alignToText = true,
    )
    SettingsHint(stringResource(R.string.settings_feedback_hint))
    ExternalLink(
        url = TesterFeedback.mailto(stringResource(R.string.app_name), appVersion, device, android),
        label = stringResource(R.string.settings_feedback_email),
        noBrowser = stringResource(R.string.settings_feedback_email_no_app, TesterFeedback.EMAIL),
        alignToText = true,
    )
}

@Composable
internal fun ExternalLink(url: String, label: String, noBrowser: String, modifier: Modifier = Modifier, alignToText: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    TextAction(label, modifier = modifier, alignToText = alignToText, onClick = {
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
    })
}
