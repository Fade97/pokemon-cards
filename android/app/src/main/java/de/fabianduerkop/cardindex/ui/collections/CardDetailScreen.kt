// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.CardDetail
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.ui.components.CARD_ASPECT
import java.io.File
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.containerViewModelFactory

/** Card detail (mockup "Card detail"): the number first, then the price, then the user's copies. */
@Composable
fun CardDetailScreen(
    onBack: () -> Unit,
    viewModel: CardDetailViewModel = viewModel(
        factory = containerViewModelFactory { container, saved ->
            CardDetailViewModel(
                container.collections,
                container.ownPhotos,
                container.scans,
                container.prices,
                container.primaryPriceSource,
                container.priceHistory,
                container.entitlements.isPro,
                saved,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val detail = state.detail
    var editing by remember { mutableStateOf<OwnedCopyEntity?>(null) }
    val context = LocalContext.current
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uuid = pickingFor
        if (uri != null && uuid != null) {
            viewModel.setPhoto(context.contentResolver, uri, uuid) {
                Toast.makeText(context, R.string.copy_photo_failed, Toast.LENGTH_LONG).show()
            }
        }
        pickingFor = null
    }

    DetailScaffold(title = detail?.set?.name ?: detail?.card?.name.orEmpty(), onBack = onBack) { padding ->
        when {
            state.loading -> Unit
            detail == null -> PlaceholderText(
                stringResource(R.string.collections_card_unknown),
                Modifier.padding(padding).padding(ScreenPadding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item { CardHeader(detail, state.photos) }
                item {
                    // Price the printing the user owns in this language, or the plain print if they own none.
                    val owned = detail.pricedCopy()
                    SectionLabel(stringResource(R.string.price_title), Modifier.padding(top = 8.dp))
                    PriceBox(
                        prices = state.prices,
                        primary = state.source,
                        finish = owned?.finish ?: Finish.NORMAL,
                        edition = owned?.edition ?: Edition.UNLIMITED,
                        cardLanguage = detail.card.language,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
                history?.let { chart ->
                    item {
                        SectionLabel(stringResource(R.string.history_title), Modifier.padding(top = 16.dp))
                        PriceHistoryBlock(
                            chart,
                            onRange = viewModel::setHistoryRange,
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
                item {
                    SectionLabel(
                        stringResource(R.string.collections_your_copies, detail.copies.sumOf { it.copy.quantity }),
                        Modifier.padding(top = 16.dp),
                    )
                }
                items(detail.copies, key = { it.copy.id }) { item ->
                    CopyRow(
                        item = item,
                        memberships = state.memberships[item.copy.id].orEmpty(),
                        showCard = false,
                        onClick = { editing = item.copy },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { editing = newCopyOf(detail.card) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
                    ) { Text(stringResource(R.string.collections_add_copy)) }
                }
            }
        }
    }

    val copy = editing
    if (copy != null && detail != null) {
        val initialCollections by viewModel.collectionIdsOf(copy.id).collectAsStateWithLifecycle(emptySet())
        CopySheet(
            title = cardTitle(detail),
            copy = copy,
            prints = detail.prints,
            collections = state.collections,
            initialCollections = initialCollections,
            onDismiss = { editing = null },
            onSave = { saved, collections ->
                viewModel.save(saved, collections)
                editing = null
            },
            onDelete = if (copy.id == 0L) null else {
                {
                    viewModel.delete(copy.id)
                    editing = null
                }
            },
            extraContent = { draft ->
                // Photos belong to saved copies only, so a cancelled new copy leaves no file behind.
                if (draft.id != 0L) {
                    OwnPhotoField(
                        photo = state.photos.of(draft.uuid),
                        onPick = {
                            pickingFor = draft.uuid
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemove = { viewModel.removePhoto(draft.uuid) },
                    )
                }
            },
        )
    }
}

@Composable
private fun cardTitle(detail: CardDetail): String {
    val set = detail.set
    val number = padCollectorNumber(detail.card.localId, set?.printedTotal)
    return listOfNotNull("$number ${detail.card.name}", set?.name).joinToString(", ")
}

@Composable
private fun CardHeader(detail: CardDetail, photos: OwnPhotos) {
    // The newest photo of a copy in the shown language, else of any copy of the card.
    val copies = detail.copies.sortedByDescending { it.card.language == detail.card.language }.map { it.copy.uuid }
    Row(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        CardImage(
            card = detail.card,
            localId = detail.card.localId,
            printedTotal = detail.set?.printedTotal,
            modifier = Modifier.width(118.dp),
            ownPhoto = photos.firstOf(copies),
            preferOwnPhoto = photos.preferOwn,
            quality = ImageQuality.HIGH,
        )
        Column(Modifier.padding(start = 14.dp)) {
            CollectorNumber(
                localId = detail.card.localId,
                printedTotal = detail.set?.printedTotal,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                detail.card.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(
                    R.string.collections_print,
                    detail.set?.name.orEmpty(),
                    languageName(detail.card.language),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The own-photo row of the copy sheet: thumbnail, pick or replace, remove. */
@Composable
private fun OwnPhotoField(photo: File?, onPick: () -> Unit, onRemove: () -> Unit) {
    Text(stringResource(R.string.copy_photo), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = stringResource(R.string.copy_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(56.dp).aspectRatio(CARD_ASPECT).clip(MaterialTheme.shapes.small),
            )
        }
        TextButton(onClick = onPick) {
            Text(stringResource(if (photo == null) R.string.copy_photo_add else R.string.copy_photo_replace))
        }
        if (photo != null) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.copy_photo_remove)) }
        }
    }
    Text(
        stringResource(R.string.copy_photo_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One copy in a list: "2× Holo, LP" with its collections and purchase date, and the purchase price on the right.
 * With [showCard] the row leads with the collector number and card name, for lists that mix cards.
 */
@Composable
internal fun CopyRow(
    item: CopyWithCard,
    memberships: List<String>,
    showCard: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val copy = item.copy
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ScreenPadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showCard) {
                CollectorNumber(
                    localId = item.card.localId,
                    printedTotal = item.set.printedTotal,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (showCard) item.card.name else stringResource(R.string.collections_copy_line, copy.quantity, copyDescription(copy)),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    if (showCard) {
                        add(item.set.name)
                        add(stringResource(R.string.collections_copy_line, copy.quantity, copyDescription(copy)))
                    } else {
                        add(memberships.joinToString(", ").ifEmpty { stringResource(R.string.collections_only_all_cards) })
                    }
                    add(languageName(item.card.language))
                    copy.purchaseDate?.let { add(stringResource(R.string.collections_bought_on, formatDate(it))) }
                }
                Text(
                    details.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                val paid = copy.purchasePriceCents
                val currency = copy.purchaseCurrency
                if (paid != null && currency != null) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        Text(Money.format(paid, currency), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.collections_paid_each),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
