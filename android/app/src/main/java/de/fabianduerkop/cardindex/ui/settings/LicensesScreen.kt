// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.licenses.LibraryLicense
import de.fabianduerkop.cardindex.data.licenses.OpenSourceLicenses
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open-source licenses (#136), reached from About in Settings: every library the APK ships with its version and
 * license, generated at build time (OpenSourceLicenses). Tapping a library shows the license text, or its link
 * when the build only knows where the text lives (e.g. the ML Kit terms).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val libraries by produceState<List<LibraryLicense>?>(null) {
        value = withContext(Dispatchers.Default) { OpenSourceLicenses.load(context) }
    }
    var open by remember { mutableStateOf<LibraryLicense?>(null) }
    DetailScaffold(title = stringResource(R.string.settings_licenses), onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
        ) {
            item {
                SettingsHint(
                    stringResource(R.string.settings_licenses_hint, stringResource(R.string.app_name)),
                    Modifier.padding(bottom = Spacing.sm),
                )
            }
            items(libraries.orEmpty(), key = { it.id }) { library ->
                LibraryRow(library, onClick = { open = library })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    open?.let { LicenseDialog(it, onDismiss = { open = null }) }
}

@Composable
private fun LibraryRow(library: LibraryLicense, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(listOfNotNull(library.name, library.version).joinToString(" "), style = MaterialTheme.typography.titleSmall)
        SettingsHint(library.licenses.joinToString { it.name }.ifEmpty { stringResource(R.string.settings_licenses_unknown) })
    }
}

@Composable
private fun LicenseDialog(library: LibraryLicense, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(library.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SettingsHint(listOfNotNull(library.id, library.version).joinToString(" · "))
                library.licenses.forEach { license ->
                    Text(license.name, style = MaterialTheme.typography.titleSmall)
                    when {
                        license.text != null -> Text(license.text, style = MaterialTheme.typography.bodySmall)
                        license.url != null -> ExternalLink(
                            url = license.url,
                            label = stringResource(R.string.settings_licenses_read),
                            noBrowser = stringResource(R.string.settings_licenses_no_browser, license.url),
                        )
                    }
                }
                library.website?.let { url ->
                    ExternalLink(
                        url = url,
                        label = stringResource(R.string.settings_licenses_website),
                        noBrowser = stringResource(R.string.settings_licenses_no_browser, url),
                    )
                }
            }
        },
        confirmButton = { TextAction(stringResource(R.string.action_close), onClick = onDismiss) },
    )
}
