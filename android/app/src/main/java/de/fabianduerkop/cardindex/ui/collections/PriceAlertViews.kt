// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.Spacing
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.data.alerts.PriceAlert
import de.fabianduerkop.cardindex.data.alerts.PriceAlertNotifications
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.model.Money
import java.time.ZoneId

/**
 * Pro price alerts on the card screen (#76): this card's alerts with delete, and a button to add one for a price the
 * price box shows. [quotes] are those prices, main market first; without any there is nothing to watch.
 */
@Composable
internal fun PriceAlertsBlock(
    alerts: List<PriceAlert>,
    quotes: List<PriceQuote>,
    onCreate: (PriceQuote, AlertDirection, Long) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        if (alerts.isEmpty()) {
            Text(
                stringResource(R.string.alerts_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NotificationsOffHint(visible = alerts.isNotEmpty())
        alerts.forEach { PriceAlertRow(it, title = null, onDelete = { onDelete(it.uuid) }) }
        SecondaryButton(
            stringResource(R.string.alerts_add),
            onClick = { adding = true },
            enabled = quotes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        )
        if (quotes.isEmpty()) {
            Text(
                stringResource(R.string.alerts_no_price),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (adding && quotes.isNotEmpty()) {
        PriceAlertDialog(
            quotes = quotes,
            onDismiss = { adding = false },
            onConfirm = { quote, direction, threshold ->
                onCreate(quote, direction, threshold)
                adding = false
            },
        )
    }
}

/**
 * Asks for `POST_NOTIFICATIONS` (Android 13+) when it isn't granted yet; called when the first alert is created (#76).
 * Without it the alert still works and shows in the lists, only the notification doesn't appear.
 */
@Composable
internal fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PriceAlertNotifications.declared(context) &&
            !PriceAlertNotifications.permitted(context)
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** A warning while the app may not post notifications, so alerts would stay silent; checked again on resume. */
@Composable
internal fun NotificationsOffHint(visible: Boolean) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        enabled = PriceAlertNotifications.permitted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    if (visible && !enabled) {
        Text(
            stringResource(R.string.alerts_notifications_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "Rises to €15.00", the market and printing, whether it already notified, and delete. [title] leads, e.g. the card. */
@Composable
internal fun PriceAlertRow(alert: PriceAlert, title: String?, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (alert.direction == AlertDirection.ABOVE) R.string.alerts_rule_above else R.string.alerts_rule_below,
                    Money.format(alert.thresholdMinor, alert.currency),
                ),
                style = if (title == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            )
            val details = listOfNotNull(
                stringResource(alert.source.label()),
                stringResource(alert.variant.label()),
                alert.triggeredAt?.let {
                    val date = formatDate(it.atZone(ZoneId.systemDefault()).toLocalDate())
                    stringResource(if (alert.armed) R.string.alerts_last_notified else R.string.alerts_notified, date)
                },
            )
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.focusRing(CircleShape)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.alerts_delete))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceAlertDialog(
    quotes: List<PriceQuote>,
    onDismiss: () -> Unit,
    onConfirm: (PriceQuote, AlertDirection, Long) -> Unit,
) {
    var market by rememberSaveable { mutableIntStateOf(0) }
    var direction by rememberSaveable { mutableStateOf(AlertDirection.ABOVE) }
    val quote = quotes[market.coerceIn(quotes.indices)]
    var amount by rememberSaveable { mutableStateOf(Money.formatPlain(quotes.first().amountMinor)) }
    val threshold = Money.parseMinor(amount)?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alerts_add)) },
        text = {
            Column {
                if (quotes.size > 1) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        quotes.forEachIndexed { index, it ->
                            SegmentedButton(
                                selected = market == index,
                                onClick = {
                                    market = index
                                    amount = Money.formatPlain(it.amountMinor)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, quotes.size),
                                icon = {},
                            ) { Text(stringResource(it.source.shortLabel()), maxLines = 1) }
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.alerts_current,
                        Money.format(quote.amountMinor, quote.currency),
                        stringResource(quote.variant.label()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    AlertDirection.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = direction == option,
                            onClick = { direction = option },
                            shape = SegmentedButtonDefaults.itemShape(index, AlertDirection.entries.size),
                            icon = {},
                        ) {
                            Text(
                                stringResource(
                                    if (option == AlertDirection.ABOVE) R.string.alerts_direction_above else R.string.alerts_direction_below,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.alerts_threshold)) },
                    suffix = { Text(quote.currency) },
                    isError = threshold == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.alerts_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { threshold?.let { onConfirm(quote, direction, it) } }, enabled = threshold != null) {
                Text(stringResource(R.string.alerts_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
