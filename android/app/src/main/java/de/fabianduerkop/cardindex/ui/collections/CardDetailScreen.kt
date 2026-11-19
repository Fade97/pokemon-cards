// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.CARD_ASPECT
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.CardViewer
import de.fabianduerkop.cardindex.ui.components.CardViewerImage
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.SectionHeader
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.io.File

/**
 * Card detail (Pearl study "Card details", #99): the art as the hero, the collector number first under it with the
 * Wishlist after the name, then the price, Pro history and alerts, and the user's copies. "Add a copy", the one solid
 * button, sits in a bottom bar that stays in thumb reach however far the page scrolls (#166). Tapping the art, or a
 * copy's photo in the copy sheet, opens the full-screen [CardViewer]. Opened from Collections and from Search.
 */
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
                container.priceAlerts,
                saved,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val wanted by viewModel.wanted.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionRequest()
    val detail = state.detail
    var editing by remember { mutableStateOf<OwnedCopyEntity?>(null) }
    val context = LocalContext.current
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The viewer's page while it is open (#166); kept across rotation.
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    val images = remember(detail, state.photos) { detail?.let { detailImages(it, state.photos) } }
    val pages = if (detail != null && images != null) viewerImages(detail, images) else null
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uuid = pickingFor
        if (uri != null && uuid != null) {
            viewModel.setPhoto(context.contentResolver, uri, uuid) {
                Toast.makeText(context, R.string.copy_photo_failed, Toast.LENGTH_LONG).show()
            }
        }
        pickingFor = null
    }

    DetailScaffold(
        title = detail?.set?.name ?: detail?.card?.name.orEmpty(),
        onBack = onBack,
        bottomBar = { if (detail != null) AddCopyBar(onAdd = { editing = newCopyOf(detail.card) }) },
    ) { padding ->
        when {
            state.loading -> Unit
            detail == null -> PlaceholderText(
                stringResource(R.string.collections_card_unknown),
                Modifier.padding(padding).padding(ScreenPadding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    CardHeader(
                        detail = detail,
                        photos = state.photos,
                        heroDescription = pages?.getOrNull(images?.heroIndex ?: 0)?.description,
                        onView = { viewing = images?.heroIndex ?: 0 },
                        wanted = wanted,
                        onWanted = { viewModel.setWanted(detail.card, it) },
                    )
                }
                item {
                    // Price the printing the user owns in this language, or the plain print if they own none.
                    val owned = detail.pricedCopy()
                    SectionHeader(stringResource(R.string.price_title), Modifier.padding(horizontal = ScreenPadding))
                    PriceBox(
                        prices = state.prices,
                        primary = state.source,
                        finish = owned?.finish ?: Finish.NORMAL,
                        edition = owned?.edition ?: Edition.UNLIMITED,
                        cardLanguage = detail.card.language,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        load = state.priceLoad,
                        onRetry = viewModel::retryPrices,
                    )
                }
                history?.let { chart ->
                    item {
                        SectionHeader(stringResource(R.string.history_title), Modifier.padding(horizontal = ScreenPadding))
                        PriceHistoryBlock(
                            chart,
                            onRange = viewModel::setHistoryRange,
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
                alerts?.let { list ->
                    item {
                        val owned = detail.pricedCopy()
                        val (main, secondary) = PriceSelection.pair(
                            state.prices,
                            state.source,
                            owned?.finish ?: Finish.NORMAL,
                            owned?.edition ?: Edition.UNLIMITED,
                            detail.card.language,
                        )
                        SectionHeader(stringResource(R.string.alerts_title), Modifier.padding(horizontal = ScreenPadding))
                        PriceAlertsBlock(
                            alerts = list,
                            quotes = listOfNotNull(main, secondary),
                            onCreate = { quote, direction, threshold ->
                                viewModel.createAlert(quote, direction, threshold, onFirst = askNotifications)
                            },
                            onDelete = viewModel::deleteAlert,
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
                item {
                    SectionHeader(
                        stringResource(R.string.collections_your_copies, detail.copies.sumOf { it.copy.quantity }),
                        Modifier.padding(horizontal = ScreenPadding),
                    )
                    if (detail.copies.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = ScreenPadding), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                items(detail.copies, key = { it.copy.id }) { item ->
                    CopyRow(
                        item = item,
                        memberships = state.memberships[item.copy.id].orEmpty(),
                        showCard = false,
                        onClick = { editing = item.copy },
                    )
                }
                item { Spacer(Modifier.height(Spacing.lg)) }
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
                        onView = { images?.indexOfPhoto(draft.uuid)?.let { viewing = it } },
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

    val page = viewing
    if (page != null && !pages.isNullOrEmpty()) {
        CardViewer(images = pages, initialPage = page.coerceIn(pages.indices), onDismiss = { viewing = null })
    }
}

/** The viewer's pages with their names and what TalkBack says for each (#166). */
@Composable
private fun viewerImages(detail: CardDetail, images: DetailImages): List<CardViewerImage> {
    val title = cardTitle(detail)
    return images.images.map { image ->
        when (image) {
            is DetailImage.Official -> CardViewerImage(
                model = image.model,
                label = stringResource(R.string.viewer_official),
                description = stringResource(R.string.viewer_official_description, title),
            )
            is DetailImage.OwnPhoto -> {
                val copy = listOf(
                    stringResource(R.string.collections_copy_line, image.copy.copy.quantity, copyDescription(image.copy.copy)),
                    languageName(image.copy.card.language),
                ).joinToString(", ")
                CardViewerImage(
                    model = image.model,
                    label = stringResource(R.string.viewer_own_photo, copy),
                    description = stringResource(R.string.viewer_own_photo_description, title, copy),
                    key = "photo:" + image.copy.copy.uuid,
                )
            }
        }
    }
}

/**
 * "Add a copy" in a bar under the list (#166), so it is in thumb reach on a tall phone without scrolling past the
 * prices and copies. It stays the page's one solid button (ADR 0014).
 */
@Composable
private fun AddCopyBar(onAdd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PrimaryButton(
                stringResource(R.string.collections_add_copy),
                onClick = onAdd,
                icon = R.drawable.ic_add,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = Spacing.sm),
            )
        }
    }
}

/**
 * Wishlist (#134) for the shown print, in the identity block under the name (#166): a quieter button, since wanting a
 * card adds no copy. When it is on the wishlist, a line says so above the button that takes it off.
 */
@Composable
private fun WishlistToggle(wanted: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (wanted) {
            Text(
                stringResource(R.string.wishlist_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        SecondaryButton(
            stringResource(if (wanted) R.string.wishlist_remove else R.string.wishlist_add),
            onClick = { onChange(!wanted) },
            icon = if (wanted) R.drawable.ic_remove else R.drawable.ic_add,
        )
    }
}

@Composable
private fun cardTitle(detail: CardDetail): String {
    val set = detail.set
    val number = padCollectorNumber(detail.card.localId, set?.printedTotal)
    return listOfNotNull("$number ${detail.card.name}", set?.name).joinToString(", ")
}

/**
 * The art as the hero (#166): full width inside the screen gutter, on the page itself rather than a grey band, and
 * capped so the collector number still shows under it on a phone and the card stays in view in landscape. When there
 * is an image to show ([heroDescription], what TalkBack says for it), tapping it opens the viewer. Then the collector
 * number as the strongest type (ADR 0010/0014), the name, set and language, and the Wishlist. Without art the
 * numbered placeholder stands in.
 */
@Composable
private fun CardHeader(
    detail: CardDetail,
    photos: OwnPhotos,
    heroDescription: String?,
    onView: () -> Unit,
    wanted: Boolean,
    onWanted: (Boolean) -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val artWidth = min(HeroMaxWidth, windowHeight * HeroMaxHeightFraction * CARD_ASPECT)
    val shape = MaterialTheme.shapes.small
    val openLabel = stringResource(R.string.viewer_open)
    Column {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(top = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            CardImage(
                card = detail.card,
                localId = detail.card.localId,
                printedTotal = detail.set?.printedTotal,
                modifier = Modifier
                    .widthIn(max = artWidth)
                    .fillMaxWidth()
                    .then(
                        if (heroDescription != null) {
                            Modifier
                                .focusRing(shape)
                                .clip(shape)
                                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onView)
                                .clearAndSetSemantics { contentDescription = heroDescription }
                        } else {
                            Modifier
                        },
                    ),
                // The newest photo of a copy in the shown language, else of any copy of the card.
                ownPhoto = photos.firstOf(heroCopyOrder(detail).map { it.copy.uuid }),
                preferOwnPhoto = photos.preferOwn,
                quality = ImageQuality.HIGH,
            )
        }
        Column(Modifier.padding(horizontal = ScreenPadding).padding(top = Spacing.xl)) {
            CollectorNumber(
                localId = detail.card.localId,
                printedTotal = detail.set?.printedTotal,
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                detail.card.name,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
            )
            Text(
                stringResource(
                    R.string.collections_print,
                    detail.set?.name.orEmpty(),
                    languageName(detail.card.language),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            WishlistToggle(wanted, onChange = onWanted, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm))
        }
    }
}

/** The hero never gets wider than this (tablets, foldables) … */
private val HeroMaxWidth = 400.dp

/** … nor taller than this share of the window, so the collector number shows under it without scrolling. */
private const val HeroMaxHeightFraction = 0.6f

/** The own-photo row of the copy sheet: thumbnail (tap to view it full screen, #166), pick or replace, remove. */
@Composable
private fun OwnPhotoField(photo: File?, onView: () -> Unit, onPick: () -> Unit, onRemove: () -> Unit) {
    Text(stringResource(R.string.copy_photo), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = stringResource(R.string.copy_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(56.dp)
                    .aspectRatio(CARD_ASPECT)
                    .focusRing(MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = stringResource(R.string.viewer_open), role = Role.Button, onClick = onView),
            )
        }
        TextAction(stringResource(if (photo == null) R.string.copy_photo_add else R.string.copy_photo_replace), onClick = onPick)
        if (photo != null) {
            TextAction(stringResource(R.string.copy_photo_remove), onClick = onRemove)
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
 * With [showCard] the row leads with the collector number and card name, for lists that mix cards. [trailing] replaces
 * the purchase price; it is told whether the row is stacked, so it can align to the start.
 *
 * On a narrow row ([isNarrowRow], e.g. 2x font size, #121) the number, the details and the price go under each other
 * at full width instead of side by side, where the details column shrank to one or two words per line.
 */
@Composable
internal fun CopyRow(
    item: CopyWithCard,
    memberships: List<String>,
    showCard: Boolean,
    onClick: () -> Unit,
    trailing: @Composable ((stacked: Boolean) -> Unit)? = null,
) {
    val copy = item.copy
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .focusRing(MaterialTheme.shapes.small, gap = (-2).dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val stacked = isNarrowRow(maxWidth, fontScale)
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCard && !stacked) {
                    CollectorNumber(
                        localId = item.card.localId,
                        printedTotal = item.set.printedTotal,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = Spacing.md),
                    )
                }
                Column(Modifier.weight(1f)) {
                    if (showCard && stacked) {
                        CollectorNumber(
                            localId = item.card.localId,
                            printedTotal = item.set.printedTotal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = if (showCard) item.card.name else stringResource(R.string.collections_copy_line, copy.quantity, copyDescription(copy)),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
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
                    // No line limit: at 1.3x font size two lines cut off the purchase date (#101).
                    Text(
                        details.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (stacked) {
                        Box(Modifier.padding(top = Spacing.xs)) { CopyRowPrice(copy, trailing, stacked = true) }
                    }
                }
                if (!stacked) CopyRowPrice(copy, trailing, stacked = false)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The trailing price of a [CopyRow]: the caller's [trailing], else what the copy was bought for. */
@Composable
private fun CopyRowPrice(copy: OwnedCopyEntity, trailing: @Composable ((stacked: Boolean) -> Unit)?, stacked: Boolean) {
    if (trailing != null) {
        trailing(stacked)
        return
    }
    val paid = copy.purchasePriceCents ?: return
    val currency = copy.purchaseCurrency ?: return
    if (stacked) {
        Text(
            stringResource(R.string.collections_paid_each_inline, Money.format(paid, currency)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.sm)) {
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

/**
 * Whether a list row is too narrow for a side column: its width at 1x font size ([width] / [fontScale]) is under
 * 300 dp. 411 dp at 1.3x (316) keeps the side column; 360 dp at 1.3x and anything at 2x stack.
 */
internal fun isNarrowRow(width: Dp, fontScale: Float): Boolean = width / fontScale < 300.dp
