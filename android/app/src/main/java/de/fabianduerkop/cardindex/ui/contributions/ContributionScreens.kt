// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.contributions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.contributions.ContributionConsent
import de.fabianduerkop.cardindex.data.db.ContributionItem
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.packs.languageName
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
 * The consent screen (issue #14, ADR 0008): what is shared, under which license, and what withdrawing can and can't
 * undo. Agreeing turns contributing on; the version shown is the one sent with every upload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributionConsentScreen(onBack: () -> Unit, viewModel: ContributionsViewModel = contributionsViewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.contribute_consent_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.contribute_consent_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.contribute_consent_version, ContributionConsent.VERSION),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.accept()
                    onBack()
                }) { Text(stringResource(R.string.contribute_agree)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.contribute_decline)) }
            }
        }
    }
}

/** "My contributions": every upload with its state; withdraw one or all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributionsScreen(onBack: () -> Unit, viewModel: ContributionsViewModel = contributionsViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contribute_mine)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.contribute_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Text(
                stringResource(R.string.contribute_withdraw_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.isEmpty()) {
                Text(stringResource(R.string.contribute_none), Modifier.padding(vertical = 16.dp))
            } else {
                OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.contribute_withdraw_all))
                }
                LazyColumn {
                    items(items, key = { it.contribution.id }) { item ->
                        ContributionRow(item, onWithdraw = { viewModel.withdraw(item.contribution.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.contribute_withdraw_all)) },
            text = { Text(stringResource(R.string.contribute_withdraw_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.withdrawAll()
                    confirmAll = false
                }) { Text(stringResource(R.string.contribute_withdraw)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
private fun ContributionRow(item: ContributionItem, onWithdraw: () -> Unit) {
    val c = item.contribution
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(item.cardName ?: c.cardId, item.localId, languageName(c.language)).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                dateFormat.format(c.createdAt) + " · " + stringResource(
                    when {
                        c.withdrawRequested -> R.string.contribute_state_withdrawing
                        c.remoteId == null -> R.string.contribute_state_queued
                        else -> R.string.contribute_state_shared
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!c.withdrawRequested) TextButton(onClick = onWithdraw) { Text(stringResource(R.string.contribute_withdraw)) }
    }
}
