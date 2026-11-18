// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.scan.ConfirmedReview
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate
import de.fabianduerkop.cardindex.data.scan.ReviewItem
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One pass through the queue: the item in front, the user's draft for it and the pass's progress. */
data class ReviewUiState(
    val loading: Boolean = true,
    val current: ReviewItem? = null,
    val draft: ReviewDraft = ReviewDraft(),
    /** 1-based position of [current] in this pass, and the pass's length. */
    val position: Int = 0,
    val total: Int = 0,
    val confirmed: Int = 0,
    val skipped: Int = 0,
    /** Items waiting in the queue, skipped ones included: the Scan screen's badge shows the same number. */
    val waiting: Int = 0,
)

/** The last confirmation, which Undo can take back: the card it added and the user's choices for it. */
data class ReviewUndo(
    val confirmed: ConfirmedReview,
    val candidate: ReviewCandidate,
    val draft: ReviewDraft,
    /** The crop queued for the open dataset, withdrawn again on Undo. */
    val contributionId: Long?,
)

/**
 * The review screen (#9). Skipped items stay in the queue (moved to its back) but leave this pass, so the pass
 * ends; [reviewSkippedAgain] starts a new pass over them. Drafts are kept per item while the screen lives, so
 * leaving a half-filled card via Skip does not lose the choices.
 *
 * The last confirmation can be undone (#98): the copy it added is removed, the item returns to the queue where it
 * was, with the draft the user had chosen, and the pass's count goes back. Only the last confirmation is kept, and
 * Undo forgets it at once, so pressing twice can never remove a second copy.
 */
class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val queue = ReviewQueueRepository(CardIndexDatabase.get(application), application.filesDir)
    private val contributions = application.appContainer.contributions

    private val skippedInPass = MutableStateFlow<Set<Long>>(emptySet())
    private val confirmedInPass = MutableStateFlow(0)
    private val drafts = MutableStateFlow<Map<Long, ReviewDraft>>(emptyMap())

    /** Ids of items being written; a double tap must not confirm the same item twice. */
    private val busy = MutableStateFlow<Set<Long>>(emptySet())

    private val _undo = MutableStateFlow<ReviewUndo?>(null)
    val undo: StateFlow<ReviewUndo?> = _undo.asStateFlow()


    val state: StateFlow<ReviewUiState> = combine(
        queue.observeItems(), skippedInPass, confirmedInPass, drafts, busy,
    ) { items, skipped, confirmed, drafts, busy ->
        val open = items.filter { it.entity.id !in skipped && it.entity.id !in busy }
        val current = open.firstOrNull()
        val done = confirmed + skipped.size
        ReviewUiState(
            loading = false,
            current = current,
            draft = current?.let { drafts[it.entity.id] ?: ReviewDraft.forItem(it.entity.checkVariant) } ?: ReviewDraft(),
            position = done + 1,
            total = done + open.size,
            confirmed = confirmed,
            skipped = skipped.size,
            waiting = items.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    private fun editDraft(change: (ReviewDraft) -> ReviewDraft) {
        val s = state.value
        val item = s.current ?: return
        drafts.update { it + (item.entity.id to change(s.draft)) }
    }

    fun selectCandidate(rank: Int) = editDraft { it.copy(rank = rank) }

    /**
     * The card the user found in search for item [itemId] (#129): it becomes the item's chosen candidate, and the
     * finish, edition and condition picked so far stay.
     */
    fun useCard(itemId: Long, cardId: String, language: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { queue.pickCard(itemId, cardId, language) } }
                .onSuccess { picked ->
                    if (picked == null) return@onSuccess // confirmed or removed meanwhile
                    drafts.update { all ->
                        val draft = all[itemId] ?: ReviewDraft.forItem(picked.checkVariant)
                        all + (itemId to draft.copy(rank = picked.rank))
                    }
                }
                .onFailure { Log.e(TAG, "picking $cardId for review item $itemId failed", it) }
        }
    }
    fun setFinish(finish: Finish) = editDraft { it.withFinish(finish) }
    fun setEdition(edition: Edition) = editDraft { it.withEdition(edition) }
    fun setCondition(condition: Condition) = editDraft { it.withCondition(condition) }
    fun changeQuantity(delta: Int) = editDraft { it.withQuantity(delta) }

    fun confirm() {
        val s = state.value
        val item = s.current ?: return
        val draft = s.draft
        if (!draft.canConfirm) return
        val id = item.entity.id
        busy.update { it + id }
        val candidate = item.candidates.firstOrNull { it.rank == draft.rank }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    queue.confirm(id, draft.rank, draft.finish!!, draft.edition!!, draft.condition, draft.quantity)
                }
            }
                .onSuccess { confirmed ->
                    confirmedInPass.update { it + 1 }
                    drafts.update { it - id }
                    val crop = confirmed.cropJpeg
                    val contribution = if (candidate != null && crop != null) {
                        runCatching { contributions.contribute(candidate.card.id, candidate.card.language, crop) }
                            .onFailure { Log.e(TAG, "queueing the contribution failed", it) }
                            .getOrNull()
                    } else {
                        null
                    }
                    _undo.value = candidate?.let { ReviewUndo(confirmed, it, draft, contribution) }
                }
                .onFailure { Log.e(TAG, "confirming review item $id failed", it) }
            busy.update { it - id }
        }
    }

    /** Takes back the last confirmation; does nothing when there is none (or it was already taken back). */
    fun undoConfirm() {
        val undo = _undo.getAndUpdate { null } ?: return

        val id = undo.confirmed.item.id
        viewModelScope.launch {
            // An undone confirmation may have been the wrong card: its crop must not reach the dataset with that label.
            undo.contributionId?.let { runCatching { contributions.withdraw(it) } }
            runCatching { withContext(Dispatchers.IO) { queue.undoConfirm(undo.confirmed) } }
                .onSuccess { restored ->
                    if (restored) {
                        drafts.update { it + (id to undo.draft) }
                        skippedInPass.update { it - id }
                        confirmedInPass.update { maxOf(0, it - 1) }
                    } else {
                        Log.w(TAG, "undo of review item $id: its copy is already gone")
                    }
                }
                .onFailure { Log.e(TAG, "undoing review item $id failed", it) }
        }
    }

    fun skip() {
        val item = state.value.current ?: return
        val id = item.entity.id
        skippedInPass.update { it + id }
        viewModelScope.launch { runCatching { queue.skip(id) }.onFailure { Log.e(TAG, "skipping $id failed", it) } }
    }

    /** Not a card, or a scan not worth keeping: removes the item and its crop. */
    fun discard() {
        val item = state.value.current ?: return
        val id = item.entity.id
        busy.update { it + id }
        viewModelScope.launch {
            runCatching { queue.discard(id) }.onFailure { Log.e(TAG, "discarding $id failed", it) }
            drafts.update { it - id }
            busy.update { it - id }
        }
    }

    /** Starts a new pass over the items skipped in this one. */
    fun reviewSkippedAgain() {
        skippedInPass.value = emptySet()
        confirmedInPass.value = 0
    }

    /** Debug builds: fills the queue with sample scans, so the screen can be checked without a camera. */
    fun addSampleItems() {
        if (!DebugTools.AVAILABLE) return
        viewModelScope.launch { runCatching { DebugTools.addSampleReviewItems(queue) }.onFailure { Log.e(TAG, "sample items failed", it) } }
    }

    companion object {
        private const val TAG = "ReviewViewModel"
    }
}
