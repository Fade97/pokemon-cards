// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.collections.SetDetail
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
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import java.time.Instant
import java.time.LocalDate
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

/** A set row on the collections screen: completion, the owned numbers for its slot strip, and its value. */
data class SetRowUi(
    val completion: SetCompletion,
    val ownedSlots: Map<String, EnergyType?>,
    val value: CollectionValue? = null,
)

data class CollectionsUiState(
    val loading: Boolean = true,
    val languages: List<String> = emptyList(),
    val selectedLanguages: Set<String> = emptySet(),
    val totalCopies: Int = 0,
    val sets: List<SetRowUi> = emptyList(),
    val collections: List<CollectionSummary> = emptyList(),
    /** Value of every copy in the main market, all languages (the header says so). */
    val value: CollectionValue? = null,
    val source: PriceSource = PriceSource.CARDMARKET,
    /** Newest market date among the cached prices of owned cards. */
    val priceDate: Instant? = null,
)

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
    history: PriceHistoryRepository,
    isPro: Flow<Boolean>,
) : ViewModel() {
    private val selected = MutableStateFlow(emptySet<String>())

    private val base = combine(
        repository.observeOwnedLanguages(),
        selected,
        repository.observeAllCopies(),
        selected.flatMapLatest { repository.observeSetCompletion(it.orAll()) },
        repository.observeCustomCollections(),
        ::CollectionsBase,
    )

    val state: StateFlow<CollectionsUiState> = combine(base, prices.observeAll(), primarySource) { b, priceMap, source ->
        val inFilter = b.copies.filter { b.chosen.isEmpty() || it.card.language in b.chosen }
        val bySet = inFilter.groupBy { it.set.id }
        CollectionsUiState(
            loading = false,
            languages = b.languages,
            selectedLanguages = b.chosen.intersect(b.languages.toSet()),
            totalCopies = b.copies.sumOf { it.copy.quantity },
            sets = b.completion.map { completion ->
                val copies = bySet[completion.setId].orEmpty()
                SetRowUi(
                    completion = completion,
                    ownedSlots = copies.associate { numberKey(it.card.localId) to it.card.energyType },
                    value = PriceSelection.total(copies.map { it.copy }, priceMap, source),
                )
            },
            collections = b.collections,
            value = PriceSelection.total(b.copies.map { it.copy }, priceMap, source),
            source = source,
            priceDate = b.copies.flatMap { priceMap[it.card.id].orEmpty() }
                .filter { it.source == source }.mapNotNull { it.priceDate }.maxOrNull(),
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

data class SetDetailUiState(
    val loading: Boolean = true,
    val detail: SetDetail? = null,
    val selectedLanguages: Set<String> = emptySet(),
    val mode: SetViewMode = SetViewMode.COMPACT,
    val filter: SlotFilter = SlotFilter.ALL,
    val photos: OwnPhotos = OwnPhotos(),
    /** Value of the owned copies in the chosen languages. */
    val value: CollectionValue? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SetDetailViewModel(
    repository: CollectionRepository,
    photos: Flow<OwnPhotos>,
    prices: PriceRepository,
    primarySource: Flow<PriceSource>,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val setId: String = checkNotNull(savedState[CollectionsRoutes.ARG_SET_ID])
    private val selected = MutableStateFlow(emptySet<String>())
    private val mode = MutableStateFlow(SetViewMode.COMPACT)
    private val filter = MutableStateFlow(SlotFilter.ALL)

    private val view = combine(selected, mode, filter, photos) { chosen, mode, filter, photos ->
        SetDetailUiState(selectedLanguages = chosen, mode = mode, filter = filter, photos = photos)
    }

    val state: StateFlow<SetDetailUiState> = combine(
        selected.flatMapLatest { repository.observeSetDetail(setId, it.orAll()) },
        view,
        prices.observeAll(),
        primarySource,
    ) { detail, view, priceMap, source ->
        val owned = detail?.copies.orEmpty()
            .filter { view.selectedLanguages.isEmpty() || it.card.language in view.selectedLanguages }
            .map { it.copy }
        view.copy(loading = false, detail = detail, value = PriceSelection.total(owned, priceMap, source))
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
}

data class CardDetailUiState(
    val loading: Boolean = true,
    val detail: CardDetail? = null,
    val collections: List<CollectionSummary> = emptyList(),
    val memberships: Map<Long, List<String>> = emptyMap(),
    val photos: OwnPhotos = OwnPhotos(),
    val prices: List<CachedPriceEntity> = emptyList(),
    val source: PriceSource = PriceSource.CARDMARKET,
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
        viewModelScope.launch { priceRepository.refresh(listOf(cardId)) }
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
) {
    val cardCount: Int get() = copies.sumOf { it.copy.quantity }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(
    private val repository: CollectionRepository,
    prices: PriceRepository,
    primarySource: Flow<PriceSource>,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** [CollectionsRoutes.ALL_CARDS] for All cards. */
    private val collectionId: Long = checkNotNull(savedState.get<String>(CollectionsRoutes.ARG_COLLECTION_ID)).toLong()
    private val sort = MutableStateFlow(CopySort.VALUE)

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
    ) { (collection, copies), sort, priceMap, market ->
        val quotes = copies.mapNotNull { item ->
            PriceSelection.copyQuote(priceMap[item.card.id].orEmpty(), market, item.copy)?.let { item.copy.id to it }
        }.toMap()
        CollectionDetailUiState(
            loading = false,
            collection = collection,
            missing = collectionId != CollectionsRoutes.ALL_CARDS && collection == null,
            copies = sortCopies(copies, sort, quotes),
            sort = sort,
            value = PriceSelection.total(copies.map { it.copy }, priceMap, market),
            quotes = quotes,
        )
    }.stateIn(this, CollectionDetailUiState())

    val isAllCards: Boolean get() = collectionId == CollectionsRoutes.ALL_CARDS

    fun setSort(value: CopySort) { sort.value = value }

    fun update(name: String, description: String?) {
        viewModelScope.launch { repository.updateCollection(collectionId, name, description) }
    }

    fun delete() {
        viewModelScope.launch { repository.deleteCollection(collectionId) }
    }
}

internal fun sortCopies(
    copies: List<CopyWithCard>,
    sort: CopySort,
    quotes: Map<Long, PriceQuote> = emptyMap(),
): List<CopyWithCard> = when (sort) {
    CopySort.VALUE -> copies.sortedByDescending { quotes[it.copy.id]?.amountMinor ?: -1 }
    CopySort.NEWEST -> copies.sortedWith(compareByDescending<CopyWithCard> { it.copy.createdAt }.thenByDescending { it.copy.id })
    CopySort.NUMBER -> copies.sortedWith(
        compareBy<CopyWithCard> { it.set.name }.thenBy(PrintOrder) { numberKey(it.card.localId) },
    )
}
