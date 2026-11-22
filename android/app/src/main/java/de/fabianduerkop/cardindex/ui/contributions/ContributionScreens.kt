// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.contributions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.contributions.ContributionConsent
import de.fabianduerkop.cardindex.data.contributions.ContributionKind
import de.fabianduerkop.cardindex.data.db.ContributionItem
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.packs.languageName
import de.fabianduerkop.cardindex.ui.settings.ConfirmDialog
import de.fabianduerkop.cardindex.ui.settings.PrivacyPolicyLink
import de.fabianduerkop.cardindex.ui.settings.SettingsHint
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ContributionsViewModel(private val container: AppContainer) : ViewModel() {
    val items: StateFlow<List<ContributionItem>> = container.contributions.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user agreed to the consent text shown: contributing is on from now. */
    fun accept() = container.settings.acceptContributionConsent(ContributionConsent.VERSION)

    fun withdraw(id: Long) {
        viewModelScope.launch { container.contributions.withdraw(id) }
    }

    fun withdrawAll() {
        viewModelScope.launch { container.contributions.withdrawAll() }
    }
}

@Composable
private fun contributionsViewModel(): ContributionsViewModel =
    viewModel(factory = containerViewModelFactory { container, _ -> ContributionsViewModel(container) })

/**
 * The consent screen (issue #14, ADR 0016): what is sent, what it is used for, and what withdrawing can and can't
 * undo; contributors confirm they are 16 or have a parent's OK. Only "I agree" turns contributing on; Back and "Not now" leave it off. The version shown is the one sent with
 * every upload.
 */
@Composable
fun ContributionConsentScreen(onBack: () -> Unit, viewModel: ContributionsViewModel = contributionsViewModel()) {
    DetailScaffold(title = stringResource(R.string.contribute_consent_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.contribute_consent_body), style = MaterialTheme.typography.bodyLarge)
            SettingsHint(stringResource(R.string.contribute_consent_version, ContributionConsent.VERSION))
            // Who processes the uploads, legal basis, retention and rights (#70).
            PrivacyPolicyLink()
            // Consent under 16 needs a parent's (Art. 8 GDPR, 16 in Germany); "I agree" waits for this box.
            var ageConfirmed by rememberSaveable { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(MaterialTheme.shapes.small)
                    .toggleable(value = ageConfirmed, role = Role.Checkbox, onValueChange = { ageConfirmed = it })
                    .heightIn(min = Spacing.touchTarget)
                    .padding(vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ageConfirmed, onCheckedChange = null, modifier = Modifier.padding(end = Spacing.md))
                Text(stringResource(R.string.contribute_age_confirm), style = MaterialTheme.typography.bodyLarge)
            }
            PrimaryButton(
                stringResource(R.string.contribute_agree),
                onClick = {
                    viewModel.accept()
                    onBack()
                },
                enabled = ageConfirmed,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(stringResource(R.string.contribute_decline), onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "My contributions": every upload with its state; withdraw one or all. */
@Composable
fun ContributionsScreen(onBack: () -> Unit, viewModel: ContributionsViewModel = contributionsViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    DetailScaffold(title = stringResource(R.string.contribute_mine), onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
        ) {
            item {
                SettingsHint(stringResource(R.string.contribute_withdraw_hint))
            }
            if (items.isEmpty()) {
                item { StatusNotice(stringResource(R.string.contribute_none), Modifier.padding(vertical = Spacing.lg)) }
            } else {
                item {
                    SecondaryButton(
                        stringResource(R.string.contribute_withdraw_all),
                        onClick = { confirmAll = true },
                        modifier = Modifier.padding(vertical = Spacing.md),
                    )
                }
                items(items, key = { it.contribution.id }) { item ->
                    ContributionRow(item, onWithdraw = { viewModel.withdraw(item.contribution.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    if (confirmAll) {
        ConfirmDialog(
            title = stringResource(R.string.contribute_withdraw_all),
            text = stringResource(R.string.contribute_withdraw_all_text),
            confirmLabel = stringResource(R.string.contribute_withdraw),
            onConfirm = {
                viewModel.withdrawAll()
                confirmAll = false
            },
            onDismiss = { confirmAll = false },
        )
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
private fun ContributionRow(item: ContributionItem, onWithdraw: () -> Unit) {
    val c = item.contribution
    val title = if (c.kind == ContributionKind.NOT_CARD) {
        stringResource(R.string.contribute_not_a_card)
    } else {
        listOfNotNull(item.cardName ?: c.cardId, item.localId, languageName(c.language)).joinToString(" · ")
    }
    Row(Modifier.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SettingsHint(
                dateFormat.format(c.createdAt) + " · " + stringResource(
                    when {
                        c.withdrawRequested -> R.string.contribute_state_withdrawing
                        c.uploadedAt == null -> R.string.contribute_state_queued
                        else -> R.string.contribute_state_shared
                    },
                ),
            )
        }
        if (!c.withdrawRequested) {
            TextAction(
                stringResource(R.string.contribute_withdraw),
                onClick = onWithdraw,
                contentDescription = stringResource(R.string.contribute_withdraw_one, title),
            )
        }
    }
}
