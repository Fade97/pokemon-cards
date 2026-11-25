// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.collections.Milestone
import de.fabianduerkop.cardindex.data.collections.Recap
import de.fabianduerkop.cardindex.data.collections.RecapPeriod
import de.fabianduerkop.cardindex.data.collections.Recaps
import de.fabianduerkop.cardindex.data.collections.SetDetail
import de.fabianduerkop.cardindex.data.collections.SetProgress
import de.fabianduerkop.cardindex.data.collections.SetSlot
import de.fabianduerkop.cardindex.data.collections.setProgress
import de.fabianduerkop.cardindex.data.collections.numberKey
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.SetCompletion
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.images.ScanImageStore
import de.fabianduerkop.cardindex.data.images.importPhoto
import de.fabianduerkop.cardindex.data.collections.PrintOrder
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.data.alerts.PriceAlert
import de.fabianduerkop.cardindex.data.alerts.PriceAlertRepository
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.HistoryRange
import de.fabianduerkop.cardindex.data.prices.HistoryRefresh
import de.fabianduerkop.cardindex.data.prices.PriceHistoryRepository
import de.fabianduerkop.cardindex.data.prices.PriceHistorySelection
import de.fabianduerkop.cardindex.data.prices.PricePoint
import de.fabianduerkop.cardindex.data.prices.currency
import de.fabianduerkop.cardindex.data.prices.forLanguage
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceRepository
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.data.prices.WeeklyPriceChange
import de.fabianduerkop.cardindex.data.prices.WeeklyTrend
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cards fanned on the featured set cover. */
private const val FEATURED_ART = 3

private fun <T> Flow<T>.stateIn(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Null selection in the UI means every language. */
private fun Set<String>.orAll(): Set<String>? = ifEmpty { null }

/** The copy whose printing the card detail prices: one in the shown language, else any; null if none is owned. */
internal fun CardDetail.pricedCopy(): OwnedCopyEntity? =
    copies.firstOrNull { it.card.language == card.language }?.copy ?: copies.firstOrNull()?.copy

/**
 * Range and refresh of a Pro price chart (#17), shared by the card detail and the collections screen: while Pro is on,
 * every change of the range or of [cardIds] fetches what the cache lacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class HistoryChart(
    scope: CoroutineScope,
    private val repository: PriceHistoryRepository,
    private val isPro: Flow<Boolean>,
    cardIds: Flow<Set<String>>,
) {
    val range = MutableStateFlow(HistoryRange.QUARTER)
    private val status = MutableStateFlow(HistoryStatus.LOADING)

    init {
        scope.launch {
            combine(isPro, range, cardIds.distinctUntilChanged(), ::Triple).collectLatest { (pro, range, ids) ->
                if (!pro) return@collectLatest
                if (ids.isEmpty()) {
                    status.value = HistoryStatus.READY
                    return@collectLatest
                }
                status.value = HistoryStatus.LOADING
                status.value = when (repository.refresh(ids, range.from(repository.today()))) {
                    is HistoryRefresh.Updated -> HistoryStatus.READY
                    HistoryRefresh.NotPro -> HistoryStatus.REFUSED
                    is HistoryRefresh.Failed -> HistoryStatus.FAILED
                }
            }
        }
    }

    /** Null without Pro; otherwise [points] over the chosen range, computed off the main thread. */
    fun state(points: (from: LocalDate) -> Flow<Pair<List<PricePoint>, String>>): Flow<HistoryUiState?> =
        isPro.distinctUntilChanged().flatMapLatest { pro ->
            if (!pro) {
                flowOf(null)
            } else {
                range.flatMapLatest { range ->
                    val from = range.from(repository.today())
                    combine(points(from).flowOn(Dispatchers.Default), status) { (values, currency), status ->
                        HistoryUiState(range, values, currency, status)
                    }
                }
            }
        }
}

/**
 * A set on the collections screen: completion, the owned slot numbers, its value, and a few owned cards for the
 * featured cover's artwork (newest first).
 */
data class SetRowUi(
    val completion: SetCompletion,
    /** Owned slots as [numberKey]s, the same keys the set detail's checklist uses. */
    val ownedSlots: Set<String>,
    val value: CollectionValue? = null,
    /** Oldest date among the prices [value] sums, shown with it so a set's value keeps its basis in view (#218). */
    val priceDate: Instant? = null,
    val series: String? = null,
    val art: List<CardEntity> = emptyList(),
    /** The market [value] comes from, named even when nothing is priced there ("No TCGplayer price", #221). */
    val source: PriceSource = PriceSource.CARDMARKET,
) {
    /**
     * Owned numbers counted like the set detail's header (`006` and `6` are one slot), so the row, the cover and the
     * set detail always agree: base numbers of the printed total, secret rares apart (#132).
     */
    val progress: SetProgress get() = setProgress(ownedSlots, completion.printedTotal)
}

data class CollectionsUiState(
    val loading: Boolean = true,
    val languages: List<String> = emptyList(),
    val selectedLanguages: Set<String> = emptySet(),
    val totalCopies: Int = 0,
    /** Every set in the language filter, by region and name; the featured one is among them. */
    val sets: List<SetRowUi> = emptyList(),
    /** The set on the foil cover (#156), see [featuredSet]. */
    val featured: SetRowUi? = null,
    /** Whether [featured] is the user's pinned set rather than the most valuable one. */
    val featuredPinned: Boolean = false,
    val collections: List<CollectionSummary> = emptyList(),
    /** Value of every copy in the main market, all languages (the header says so). */
    val value: CollectionValue? = null,
    val source: PriceSource = PriceSource.CARDMARKET,
    /** Newest market date among the cached prices of owned cards. */
    val priceDate: Instant? = null,
    /** Each custom collection's cover by collection id (#172); empty binders have none. */
    val covers: Map<Long, BinderCover> = emptyMap(),
    /** The All cards row's cover: the most valuable card overall. */
    val allCardsCover: BinderCover? = null,
    /** Own photos, so a cover shows the user's photo where they prefer it (ADR 0009). */
    val photos: OwnPhotos = OwnPhotos(),
)

/** The sets below the foil cover: every set except the featured one, in their order. */
val CollectionsUiState.otherSets: List<SetRowUi>
    get() = sets.filter { it.completion.setId != featured?.completion?.setId }

/**
 * The set on the Collections foil cover (#156): the one the user pinned while it is in the list, else the most
 * valuable set. With no prices (or a tie) the earlier set in the list wins, so the cover never jumps at random.
 */
internal fun featuredSet(sets: List<SetRowUi>, pinnedId: String?): SetRowUi? =
    sets.firstOrNull { it.completion.setId == pinnedId } ?: sets.maxByOrNull { it.value?.amountMinor ?: 0L }

/**
 * The three views of the Collections home (#164), switched below the value header so binders and the wishlist don't
 * sink below every set. In switch order.
 */
enum class CollectionsView {
    SETS,
    BINDERS,
    WISHLIST,
    ;

    companion object {
        /** The view stored under [name] (AppSettings.collectionsView); sets when nothing or an unknown name is stored. */
        fun fromStored(name: String?): CollectionsView = entries.firstOrNull { it.name == name } ?: SETS
    }
}

/** A recap the Collections home offers (#175); [new] until opened. */
data class RecapEntry(val period: RecapPeriod, val new: Boolean)

private data class CollectionsBase(
    val languages: List<String>,
    val chosen: Set<String>,
    val copies: List<CopyWithCard>,
    val completion: List<SetCompletion>,
    val collections: List<CollectionSummary>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModel(
    private val repository: CollectionRepository,
    prices: PriceRepository,
    primarySource: Flow<PriceSource>,
    private val history: PriceHistoryRepository,
    private val isPro: Flow<Boolean>,
    pinnedSet: Flow<String?> = flowOf(null),
    storedView: String? = null,
    private val saveView: (String) -> Unit = {},
    openedRecaps: Set<String> = emptySet(),
    private val markRecapOpened: (String) -> Unit = {},
    private val today: () -> LocalDate = LocalDate::now,
    photos: Flow<OwnPhotos> = flowOf(OwnPhotos()),
) : ViewModel() {
    private val selected = MutableStateFlow(emptySet<String>())
    private val chosenView = MutableStateFlow(CollectionsView.fromStored(storedView))

    /** The view below the value header (#164); starts on the one shown last, see [showView]. */
    val view: StateFlow<CollectionsView> = chosenView

    /** Switches the home to [view] and remembers it for the next start. */
    fun showView(view: CollectionsView) {
        chosenView.value = view
        saveView(view.name)
    }

    private val base = combine(
        repository.observeOwnedLanguages(),
        selected,
        repository.observeAllCopies(),
        selected.flatMapLatest { repository.observeSetCompletion(it.orAll()) },
        repository.observeCustomCollections(),
        ::CollectionsBase,
    )

    private val binders = combine(repository.observeCollectionCopyIds(), photos, ::Pair)

    val state: StateFlow<CollectionsUiState> = combine(
        base,
        prices.observeAll(),
        primarySource,
        pinnedSet,
        binders,
    ) { b, priceMap, source, pinnedId, (members, photos) ->
        val inFilter = b.copies.filter { b.chosen.isEmpty() || it.card.language in b.chosen }
        val bySet = inFilter.groupBy { it.set.id }
        val sets = b.completion.map { completion ->
            val copies = bySet[completion.setId].orEmpty()
            SetRowUi(
                completion = completion,
                ownedSlots = copies.mapTo(HashSet()) { numberKey(it.card.localId) },
                value = PriceSelection.total(copies.map { it.copy }, priceMap, source),
                priceDate = PriceSelection.totalDate(copies.map { it.copy }, priceMap, source),
                series = copies.firstOrNull()?.set?.series?.takeIf { it.isNotBlank() },
                art = copies.map { it.card }.distinctBy { it.id }.sortedBy { it.imageUrl == null }.take(FEATURED_ART),
                source = source,
            )
        }
        val featured = featuredSet(sets, pinnedId)
        // Binder covers (#172) from every copy, whatever the language filter: a binder looks the same in every view.
        val quotes = b.copies.mapNotNull { item ->
            PriceSelection.copyQuote(priceMap[item.card.id].orEmpty(), source, item.copy)?.let { item.copy.id to it }
        }.toMap()
        val covers = b.collections.mapNotNull { collection ->
            val ids = members[collection.id] ?: return@mapNotNull null
            binderCover(b.copies.filter { it.copy.id in ids }, collection.coverCopyUuid, quotes, source.currency)
                ?.let { collection.id to it }
        }.toMap()
        CollectionsUiState(
            loading = false,
            languages = b.languages,
            selectedLanguages = b.chosen.intersect(b.languages.toSet()),
            totalCopies = b.copies.sumOf { it.copy.quantity },
            sets = sets,
            featured = featured,
            featuredPinned = featured != null && featured.completion.setId == pinnedId,
            collections = b.collections,
            value = PriceSelection.total(b.copies.map { it.copy }, priceMap, source),
            source = source,
            priceDate = PriceSelection.totalDate(b.copies.map { it.copy }, priceMap, source),
            covers = covers,
            allCardsCover = binderCover(b.copies, null, quotes, source.currency),
            photos = photos,
        )
    }.stateIn(this, CollectionsUiState())

    private val portfolioChart = HistoryChart(
        viewModelScope,
        history,
        isPro,
        repository.observeAllCopies().map { copies -> copies.map { it.card.id }.toSet() },
    )

    /** Pro (#17): every copy, as in the value header, valued with each day's prices. Null without Pro. */
    val portfolio: StateFlow<HistoryUiState?> = portfolioChart.state { from ->
        combine(repository.observeAllCopies(), history.observeAll(from), primarySource) { copies, rows, source ->
            PriceHistorySelection.portfolio(copies.map { it.copy }, rows, source, from)
                .map { PricePoint(it.date, it.valueMinor) } to source.currency
        }
    }.stateIn(this, null)

    fun setPortfolioRange(range: HistoryRange) { portfolioChart.range.value = range }

    /**
     * Each set's value change over the last 7 days, by set id, for the same copies and market as the set row's value
     * (the language filter applies). Pro (#173) gets the amounts from the history, which the portfolio chart's refresh
     * above fetches (every range is longer than a week); free users (#186) only the direction, from the week-ago prices
     * in the cached price files. Sets without a week of prices are missing.
     */
    val weeklyChanges: StateFlow<Map<String, WeeklyTrend>> = isPro.distinctUntilChanged().flatMapLatest { pro ->
        if (!pro) {
            combine(base, prices.observeAll(), primarySource) { b, priceMap, source ->
                val today = history.today()
                b.copies.filter { b.chosen.isEmpty() || it.card.language in b.chosen }
                    .groupBy { it.set.id }
                    .mapNotNull { (setId, copies) ->
                        WeeklyPriceChange.directionOfCopies(copies.map { it.copy }, priceMap, source, today)?.let { setId to it }
                    }
                    .toMap()
            }.flowOn(Dispatchers.Default)
        } else {
            // The history window is fixed when Pro turns on; "today" is read on every update, so a screen left open
            // past midnight (UTC) still judges staleness by the real date.
            combine(
                base,
                prices.observeAll(),
                history.observeAll(WeeklyPriceChange.from(history.today())),
                primarySource,
            ) { b, priceMap, rows, source ->
                val today = history.today()
                b.copies.filter { b.chosen.isEmpty() || it.card.language in b.chosen }
                    .groupBy { it.set.id }
                    .mapNotNull { (setId, copies) ->
                        WeeklyPriceChange.ofCopies(copies.map { it.copy }, priceMap, rows, source, today)?.let { setId to it }
                    }
                    .toMap()
            }.flowOn(Dispatchers.Default)
        }
    }.stateIn(this, emptyMap())

    private val opened = MutableStateFlow(openedRecaps)

    /**
     * Recaps ready to look at (#175): last month all through this month, last year in January, each only when
     * something was added in it. No notification; [RecapEntry.new] until it was opened once.
     */
    val recaps: StateFlow<List<RecapEntry>> = combine(repository.observeAllCopies(), opened) { copies, seen ->
        val zone = ZoneId.systemDefault()
        RecapPeriod.ready(today())
            .filter { period -> copies.any { period.contains(it.copy.createdAt, zone) } }
            .map { RecapEntry(it, new = it.key !in seen) }
    }.flowOn(Dispatchers.Default).stateIn(this, emptyList())

    private val openRecap = MutableStateFlow<RecapPeriod?>(null)

    /**
     * The recap on screen, from the collection as it is now and, with Pro, the cached price history (ADR 0012);
     * computed only while one is open.
     */
    val recap: StateFlow<Recap?> = openRecap.flatMapLatest { period ->
        if (period == null) {
            flowOf(null)
        } else {
            val rows = isPro.distinctUntilChanged().flatMapLatest { pro -> if (pro) history.observeAll(period.start) else flowOf(null) }
            combine(repository.observeAllCopies(), prices.observeAll(), primarySource, rows) { copies, priceMap, source, historyRows ->
                Recaps.build(period, copies, priceMap, source, historyRows)
            }.flowOn(Dispatchers.Default)
        }
    }.stateIn(this, null)

    /** Opens the recap of [period]; with Pro it also loads the price history back to its start. */
    fun showRecap(period: RecapPeriod) {
        openRecap.value = period
        markRecapOpened(period.key)
        opened.value = opened.value + period.key
        viewModelScope.launch {
            if (!isPro.first()) return@launch
            val ids = repository.observeAllCopies().first().mapTo(HashSet()) { it.copy.cardId }
            if (ids.isNotEmpty()) runCatching { history.refresh(ids, period.start) }
        }
    }

    fun closeRecap() { openRecap.value = null }

    /** Wishlist entries (#134) the user has no copy of yet, for the wishlist row. */
    val wishlistCount: StateFlow<Int> = repository.observeWishlistCount().stateIn(this, 0)

    init {
        // Keep the cached prices of owned cards fresh; PriceRepository skips cards fetched in the last 12 hours.
        viewModelScope.launch {
            repository.observeAllCopies()
                .map { copies -> copies.map { it.card.id }.toSet() }
                .distinctUntilChanged()
                .collectLatest { prices.refresh(it) }
        }
    }

    fun toggleLanguage(language: String?) {
        selected.value = when {
            language == null -> emptySet()
            language in selected.value -> selected.value - language
            else -> selected.value + language
        }
    }

    fun createCollection(name: String, description: String?) {
        viewModelScope.launch { repository.createCollection(name, description) }
    }
}

enum class SetViewMode { COMPACT, CARDS }

enum class SlotFilter { ALL, OWNED, MISSING }

/** The slots the grid shows for [filter]; secret rares count like any other slot (#132). */
internal fun List<SetSlot>.filteredBy(filter: SlotFilter): List<SetSlot> = when (filter) {
    SlotFilter.ALL -> this
    SlotFilter.OWNED -> filter { it.owned }
    SlotFilter.MISSING -> filterNot { it.owned }
}

/**
 * Label of the language chip on the set detail filter row (#165): the chosen codes in the set's order, e.g. "DE, EN",
 * or null for no choice, which the screen shows as "All languages".
 */
internal fun languageChipLabel(languages: List<String>, selected: Set<String>): String? =
    languages.filter { it in selected }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() }

/**
 * When the set detail screen explains its slots on its own (#165): on the first [FIRST_VISITS] visits of any set, so
 * the shapes are learned once and the grid then starts higher. Later the info button shows the legend.
 */
internal object SetLegend {
    const val FIRST_VISITS = 3

    fun showsOnVisit(pastVisits: Int): Boolean = pastVisits < FIRST_VISITS

    /** One visit after [pastVisits] earlier ones: whether the legend shows, and [record] counted only when it does. */
    fun visit(pastVisits: Int, record: () -> Unit): Boolean = showsOnVisit(pastVisits).also { if (it) record() }
}

data class SetDetailUiState(
    val loading: Boolean = true,
    val detail: SetDetail? = null,
    val selectedLanguages: Set<String> = emptySet(),
    val mode: SetViewMode = SetViewMode.COMPACT,
    val filter: SlotFilter = SlotFilter.ALL,
    val photos: OwnPhotos = OwnPhotos(),
    /** Value of the owned copies in the chosen languages. */
    val value: CollectionValue? = null,
    /** Newest price date behind [value], shown with it (#176). */
    val priceDate: Instant? = null,
    /** The market [value] comes from; named when the copies have no price there ("No TCGplayer price", #221). */
    val source: PriceSource = PriceSource.CARDMARKET,
    /** Whether the user pinned this set to the Collections foil cover (#156). */
    val pinned: Boolean = false,
    /** Whether the slot legend shows under the filter row: on the first visits, then by the info button (#165). */
    val legendShown: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SetDetailViewModel(
    repository: CollectionRepository,
    photos: Flow<OwnPhotos>,
    prices: PriceRepository,
    primarySource: Flow<PriceSource>,
    savedState: SavedStateHandle,
    pinnedSet: Flow<String?> = flowOf(null),
    private val pinSet: (String?) -> Unit = {},
    pastLegendVisits: Int = SetLegend.FIRST_VISITS,
    recordLegendVisit: () -> Unit = {},
    private val celebrate: suspend (setId: String, language: String) -> Milestone? = { _, _ -> null },
    lastVisit: Long? = null,
    recordVisit: (atMillis: Long) -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val setId: String = checkNotNull(savedState[CollectionsRoutes.ARG_SET_ID])
    // Starts in the languages Collections was filtered to, so the set's count and value match the row tapped (#218).
    private val selected = MutableStateFlow(
        savedState.get<String>(CollectionsRoutes.ARG_LANGUAGES)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
    )

    init {
        // Only the languages this set has: a language chosen on Collections that the set lacks would be an
        // invisible filter, which the language chip can't show or clear, and owned slots would read as missing.
        if (selected.value.isNotEmpty()) {
            viewModelScope.launch {
                val available = repository.observeSetDetail(setId, null).first()?.languages?.toSet() ?: return@launch
                selected.update { chosen -> chosen intersect available }
            }
        }
    }
    private val mode = MutableStateFlow(SetViewMode.COMPACT)
    private val filter = MutableStateFlow(SlotFilter.ALL)

    // One visit per screen, not per rotation: the view model outlives configuration changes (#165).
    private val legend = MutableStateFlow(SetLegend.visit(pastLegendVisits, recordLegendVisit))

    private val view = combine(selected, mode, filter, photos, pinnedSet) { chosen, mode, filter, photos, pinnedId ->
        SetDetailUiState(selectedLanguages = chosen, mode = mode, filter = filter, photos = photos, pinned = pinnedId == setId)
    }

    val state: StateFlow<SetDetailUiState> = combine(
        selected.flatMapLatest { repository.observeSetDetail(setId, it.orAll()) },
        view,
        prices.observeAll(),
        primarySource,
        legend,
    ) { detail, view, priceMap, source, legendShown ->
        val ownedCopies = detail?.copies.orEmpty()
            .filter { view.selectedLanguages.isEmpty() || it.card.language in view.selectedLanguages }
        val owned = ownedCopies.map { it.copy }
        view.copy(
            loading = false,
            detail = detail,
            value = PriceSelection.total(owned, priceMap, source),
            priceDate = PriceSelection.totalDate(owned, priceMap, source),
            source = source,
            legendShown = legendShown,
        )
    }.stateIn(this, SetDetailUiState())

    fun toggleLanguage(language: String?) {
        selected.value = when {
            language == null -> emptySet()
            language in selected.value -> selected.value - language
            else -> selected.value + language
        }
    }

    fun setMode(value: SetViewMode) { mode.value = value }
    fun setFilter(value: SlotFilter) { filter.value = value }
    fun toggleLegend() { legend.value = !legend.value }

    /** Pins this set to the Collections foil cover, or unpins it so the most valuable set returns (#156). */
    fun togglePinned() = pinSet(if (state.value.pinned) null else setId)

    private val _milestone = MutableStateFlow<Milestone?>(null)

    /**
     * A milestone of this set reached since it was last celebrated (#169), e.g. through Review or a hand-added copy;
     * the screen shows it once, then [dismissMilestone].
     */
    val milestone: StateFlow<Milestone?> = _milestone

    fun dismissMilestone() { _milestone.value = null }

    private val _newSlots = MutableStateFlow(emptySet<String>())

    /** Numbers ([numberKey]) filled since the last visit (#169): each pulses once. None on the first visit. */
    val newSlots: StateFlow<Set<String>> = _newSlots

    init {
        recordVisit(now())
        viewModelScope.launch {
            val detail = state.first { !it.loading }.detail ?: return@launch
            if (lastVisit != null) {
                _newSlots.value = detail.copies.filter { it.copy.createdAt.toEpochMilli() > lastVisit }
                    .mapTo(HashSet()) { numberKey(it.card.localId) }
            }
            _milestone.value = withContext(Dispatchers.IO) {
                detail.copies.map { it.copy.cardLanguage }.distinct()
                    .mapNotNull { runCatching { celebrate(setId, it) }.getOrNull() }
                    .maxByOrNull { it.level }
            }
        }
    }

    /**
     * Numbers ([numberKey]) of this set's prints still on the wishlist (#134); the set's share images and checklist
     * mark them (#170), so a friend sees which missing cards matter most. Only prints in the languages shown: an English
     * want never marks a slot of a German-only image or list (#250).
     */
    val wantedNumbers: StateFlow<Set<String>> = combine(repository.observeWishlist(), selected) { wanted, languages ->
        wanted.filter { !it.found && it.set.id == setId && (languages.isEmpty() || it.card.language in languages) }
            .mapTo(HashSet()) { numberKey(it.card.localId) }
    }.stateIn(this, emptySet())
}

data class CardDetailUiState(
    val loading: Boolean = true,
    val detail: CardDetail? = null,
    val collections: List<CollectionSummary> = emptyList(),
    val memberships: Map<Long, List<String>> = emptyMap(),
    val photos: OwnPhotos = OwnPhotos(),
    val prices: List<CachedPriceEntity> = emptyList(),
    val source: PriceSource = PriceSource.CARDMARKET,
    /** This screen's own price refresh, so a failure without a saved price can say so and offer a retry (#133). */
    val priceLoad: PriceLoad = PriceLoad.LOADING,
)

class CardDetailViewModel(
    private val repository: CollectionRepository,
    photos: Flow<OwnPhotos>,
    private val scans: ScanImageStore,
    private val priceRepository: PriceRepository,
    primarySource: Flow<PriceSource>,
    historyRepository: PriceHistoryRepository,
    isPro: Flow<Boolean>,
    private val alertRepository: PriceAlertRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val cardId: String = checkNotNull(savedState[CollectionsRoutes.ARG_CARD_ID])
    val language: String = checkNotNull(savedState[CollectionsRoutes.ARG_LANGUAGE])

    private val priceLoad = MutableStateFlow(PriceLoad.LOADING)

    val state: StateFlow<CardDetailUiState> = combine(
        repository.observeCardDetail(cardId, language),
        repository.observeCustomCollections(),
        repository.observeMemberships(),
        photos,
    ) { detail, collections, memberships, photos ->
        CardDetailUiState(
            loading = false,
            detail = detail,
            collections = collections,
            memberships = memberships,
            photos = photos,
        )
    }.combine(priceRepository.observe(cardId)) { state, prices -> state.copy(prices = prices) }
        .combine(primarySource) { state, source -> state.copy(source = source) }
        .combine(priceLoad) { state, load -> state.copy(priceLoad = load) }
        .stateIn(this, CardDetailUiState())

    private val chart = HistoryChart(viewModelScope, historyRepository, isPro, flowOf(setOf(cardId)))

    /** Pro (#17): the daily price of the printing the price box shows. Null without Pro. */
    val history: StateFlow<HistoryUiState?> = chart.state { from ->
        combine(historyRepository.observeCard(cardId, from), state) { rows, state ->
            val copy = state.detail?.pricedCopy()
            val source = state.source.forLanguage(language)
            PriceHistorySelection.series(rows, source, copy?.finish ?: Finish.NORMAL, copy?.edition ?: Edition.UNLIMITED)
                .filter { !it.date.isBefore(from) } to source.currency
        }
    }.stateIn(this, null)

    fun setHistoryRange(range: HistoryRange) { chart.range.value = range }

    /** Whether the shown print is on the wishlist (#134). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val wanted: StateFlow<Boolean> = state
        .map { s -> s.detail?.card?.let { it.id to it.language } }
        .distinctUntilChanged()
        .flatMapLatest { key -> key?.let { repository.observeWanted(it.first, it.second) } ?: flowOf(false) }
        .stateIn(this, false)

    /** Puts the shown print on the wishlist or takes it off; no copy is added or removed. */
    fun setWanted(card: CardEntity, want: Boolean) {
        viewModelScope.launch {
            if (want) repository.addToWishlist(card.id, card.language) else repository.removeFromWishlist(card.id, card.language)
        }
    }

    /** Pro (#76): this card's price alerts. Null without Pro. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val alerts: StateFlow<List<PriceAlert>?> = isPro.distinctUntilChanged().flatMapLatest { pro ->
        if (pro) alertRepository.observeCard(cardId) else flowOf(null)
    }.stateIn(this, null)

    /** Adds an alert on [quote]'s market and printing; [onFirst] runs when it is the user's first alert. */
    fun createAlert(quote: PriceQuote, direction: AlertDirection, thresholdMinor: Long, onFirst: () -> Unit) {
        viewModelScope.launch {
            val first = alertRepository.create(
                cardId, language, quote.source, quote.variant, direction, thresholdMinor, quote.currency,
            )
            if (first) onFirst()
        }
    }

    fun deleteAlert(uuid: String) {
        viewModelScope.launch { alertRepository.delete(uuid) }
    }

    init {
        loadPrices()
    }

    /** Try again after a failed refresh (#133). */
    fun retryPrices() {
        if (priceLoad.value != PriceLoad.LOADING) loadPrices()
    }

    private fun loadPrices() {
        priceLoad.value = PriceLoad.LOADING
        viewModelScope.launch { priceLoad.value = priceRepository.refresh(listOf(cardId)).toLoad() }
    }

    /** Stores a picked photo as the copy's own image; [onError] runs when it can't be read as an image. */
    fun setPhoto(resolver: ContentResolver, uri: Uri, copyUuid: String, onError: () -> Unit) {
        viewModelScope.launch {
            runCatching { scans.importPhoto(resolver, uri, copyUuid) }.onFailure { onError() }
        }
    }

    fun removePhoto(copyUuid: String) {
        viewModelScope.launch { scans.delete(copyUuid) }
    }

    fun collectionIdsOf(copyId: Long): Flow<Set<Long>> =
        if (copyId == 0L) flowOf(emptySet()) else repository.observeCollectionIdsOf(copyId)

    fun save(copy: OwnedCopyEntity, collectionIds: Set<Long>) {
        viewModelScope.launch { repository.saveCopy(copy, collectionIds) }
    }

    fun delete(copyId: Long) {
        viewModelScope.launch { repository.deleteCopy(copyId) }
    }

    fun createCollection(name: String, description: String?) {
        viewModelScope.launch { repository.createCollection(name, description) }
    }
}

enum class CopySort { VALUE, NUMBER, NEWEST }

data class CollectionDetailUiState(
    val loading: Boolean = true,
    /** Null for All cards. */
    val collection: CollectionEntity? = null,
    val missing: Boolean = false,
    val copies: List<CopyWithCard> = emptyList(),
    val sort: CopySort = CopySort.VALUE,
    val value: CollectionValue? = null,
    /** Current price of each copy (by copy id) in the main market, per card. */
    val quotes: Map<Long, PriceQuote> = emptyMap(),
    /** A custom collection's cover (#172), picked or automatic; null for All cards and empty binders. */
    val cover: BinderCover? = null,
    /** What the automatic cover shows, for the picker's Automatic choice (#172). */
    val automaticCover: CopyWithCard? = null,
    /** The cards the cover picker offers (#172), one per print, most valuable first. */
    val coverChoices: List<CopyWithCard> = emptyList(),
    val photos: OwnPhotos = OwnPhotos(),
) {
    val cardCount: Int get() = copies.sumOf { it.copy.quantity }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(
    private val repository: CollectionRepository,
    prices: PriceRepository,
    primarySource: Flow<PriceSource>,
    savedState: SavedStateHandle,
    history: PriceHistoryRepository? = null,
    isPro: Flow<Boolean> = flowOf(false),
    private val chooseScanTarget: suspend (collectionId: Long?) -> Unit = {},
    photos: Flow<OwnPhotos> = flowOf(OwnPhotos()),
) : ViewModel() {
    /** [CollectionsRoutes.ALL_CARDS] for All cards. */
    private val collectionId: Long = checkNotNull(savedState.get<String>(CollectionsRoutes.ARG_COLLECTION_ID)).toLong()
    private val sort = MutableStateFlow(CopySort.VALUE)

    /** Names of the other binders, so a rename can't make two look the same (#221). */
    val otherBinderNames: StateFlow<Set<String>> = repository.observeCustomCollections()
        .map { list -> list.filter { it.id != collectionId }.mapTo(HashSet()) { binderNameKey(it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val source: Flow<Pair<CollectionEntity?, List<CopyWithCard>>> =
        if (collectionId == CollectionsRoutes.ALL_CARDS) {
            repository.observeAllCopies().map { null to it }
        } else {
            repository.observeCollection(collectionId).flatMapLatest { collection ->
                if (collection == null) flowOf(null to emptyList())
                else repository.observeCopiesInCollection(collectionId).map { collection to it }
            }
        }

    val state: StateFlow<CollectionDetailUiState> = combine(
        source,
        sort,
        prices.observeAll(),
        primarySource,
        photos,
    ) { (collection, copies), sort, priceMap, market, photos ->
        val quotes = copies.mapNotNull { item ->
            PriceSelection.copyQuote(priceMap[item.card.id].orEmpty(), market, item.copy)?.let { item.copy.id to it }
        }.toMap()
        val value = PriceSelection.total(copies.map { it.copy }, priceMap, market)
        CollectionDetailUiState(
            loading = false,
            collection = collection,
            missing = collectionId != CollectionsRoutes.ALL_CARDS && collection == null,
            copies = sortCopies(copies, sort, quotes, value.currency),
            sort = sort,
            value = value,
            quotes = quotes,
            cover = collection?.let { binderCover(copies, it.coverCopyUuid, quotes, value.currency) },
            automaticCover = collection?.let { binderCover(copies, null, quotes, value.currency)?.copy },
            coverChoices = if (collection == null) emptyList() else coverChoices(copies, collection.coverCopyUuid, quotes, value.currency),
            photos = photos,
        )
    }.stateIn(this, CollectionDetailUiState())

    /**
     * Makes this collection the scan target and then [openScan]s (#174), so the first scans of an empty binder land
     * in it. All cards keeps the current target: every scan goes there anyway.
     */
    fun scanHere(openScan: () -> Unit) {
        viewModelScope.launch {
            state.value.collection?.let { chooseScanTarget(it.id) }
            openScan()
        }
    }

    /**
     * The weekly change of each copy's price as its row shows it (per card, that market and printing), by copy id: Pro
     * (#173) with the amounts from the history, free users (#186) only the direction, from the week-ago prices in the
     * cached price files. Copies without a week of prices are missing. "Today" is read on every update, so a screen
     * left open past midnight (UTC) still judges staleness by the real date.
     */
    val weeklyChanges: StateFlow<Map<Long, WeeklyTrend>> =
        isPro.distinctUntilChanged().flatMapLatest { pro ->
            when {
                !pro -> combine(state, prices.observeAll()) { s, priceMap ->
                    val today = history?.today() ?: LocalDate.now(ZoneOffset.UTC)
                    s.copies.mapNotNull { item ->
                        val quote = s.quotes[item.copy.id] ?: return@mapNotNull null
                        WeeklyPriceChange.directionOfQuote(priceMap[item.card.id].orEmpty(), quote, today)
                            ?.let { item.copy.id to it }
                    }.toMap()
                }.flowOn(Dispatchers.Default)
                history == null -> flowOf(emptyMap())
                else -> {
                    combine(state, history.observeAll(WeeklyPriceChange.from(history.today()))) { s, rows ->
                        val today = history.today()
                        s.copies.mapNotNull { item ->
                            val quote = s.quotes[item.copy.id] ?: return@mapNotNull null
                            WeeklyPriceChange.ofQuote(rows[item.card.id].orEmpty(), quote, today)?.let { item.copy.id to it }
                        }.toMap()
                    }.flowOn(Dispatchers.Default)
                }
            }
        }.stateIn(this, emptyMap())

    init {
        // Usually cached by the Collections screen's portfolio chart already; this fills what it lacks (a card added
        // since). Months the cache has are not asked again.
        if (history != null) {
            viewModelScope.launch {
                val cardIds = source.map { (_, copies) -> copies.map { it.card.id }.toSet() }.distinctUntilChanged()
                combine(isPro, cardIds, ::Pair).collectLatest { (pro, ids) ->
                    if (pro && ids.isNotEmpty()) history.refresh(ids, WeeklyPriceChange.from(history.today()))
                }
            }
        }
    }

    val isAllCards: Boolean get() = collectionId == CollectionsRoutes.ALL_CARDS

    fun setSort(value: CopySort) { sort.value = value }

    /** Saves the edit sheet: name, description and the cover, a copy uuid or null for the automatic one (#172). */
    fun update(name: String, description: String?, coverCopyUuid: String?) {
        viewModelScope.launch { repository.updateCollection(collectionId, name, description, coverCopyUuid) }
    }

    fun delete() {
        viewModelScope.launch { repository.deleteCollection(collectionId) }
    }
}

/**
 * [CopySort.VALUE] never compares amounts in different currencies: copies priced in [totalCurrency] (the currency of
 * the list's total) come first, then the other priced copies grouped by currency, then the copies without a price.
 * Within a group the highest price comes first. Without [totalCurrency], the priced copies are grouped by currency only.
 */
internal fun sortCopies(
    copies: List<CopyWithCard>,
    sort: CopySort,
    quotes: Map<Long, PriceQuote> = emptyMap(),
    totalCurrency: String? = null,
): List<CopyWithCard> = when (sort) {
    CopySort.VALUE -> copies.sortedWith(
        compareBy<CopyWithCard> {
            when (quotes[it.copy.id]?.currency) {
                null -> 2
                totalCurrency -> 0
                else -> 1
            }
        }.thenBy { quotes[it.copy.id]?.currency }
            .thenByDescending { quotes[it.copy.id]?.amountMinor },
    )
    CopySort.NEWEST -> copies.sortedWith(compareByDescending<CopyWithCard> { it.copy.createdAt }.thenByDescending { it.copy.id })
    CopySort.NUMBER -> copies.sortedWith(
        compareBy<CopyWithCard> { it.set.name }.thenBy(PrintOrder) { numberKey(it.card.localId) },
    )
}
