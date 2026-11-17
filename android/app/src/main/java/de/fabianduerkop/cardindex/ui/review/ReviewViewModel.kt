// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.fabianduerkop.cardindex.data.scan.ReviewItem
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * The review screen (#9). Skipped items stay in the queue (moved to its back) but leave this pass, so the pass
 * ends; [reviewSkippedAgain] starts a new pass over them. Drafts are kept per item while the screen lives, so
 * leaving a half-filled card via Skip does not lose the choices.
 */
class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val queue = ReviewQueueRepository(CardIndexDatabase.get(application), application.filesDir)
    private val contributions = application.appContainer.contributions

    private val skippedInPass = MutableStateFlow<Set<Long>>(emptySet())
    private val confirmedInPass = MutableStateFlow(0)
    private val drafts = MutableStateFlow<Map<Long, ReviewDraft>>(emptyMap())

    /** Ids of items being written; a double tap must not confirm the same item twice. */
    private val busy = MutableStateFlow<Set<Long>>(emptySet())

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
    fun setFinish(finish: Finish) = editDraft { it.copy(finish = finish) }
    fun setEdition(edition: Edition) = editDraft { it.copy(edition = edition) }
    fun setCondition(condition: Condition) = editDraft { it.copy(condition = condition) }
    fun changeQuantity(delta: Int) = editDraft { it.withQuantity(delta) }

    fun confirm() {
        val s = state.value
        val item = s.current ?: return
        val draft = s.draft
        if (!draft.canConfirm) return
        val id = item.entity.id
        busy.update { it + id }
        val confirmed = item.candidates.firstOrNull { it.rank == draft.rank }?.card
        viewModelScope.launch {
            // Read before confirming: the confirm deletes the crop.
            val crop = runCatching { withContext(Dispatchers.IO) { item.cropFile.readBytes() } }.getOrNull()
            runCatching {
                queue.confirm(id, draft.rank, draft.finish!!, draft.edition!!, draft.condition, draft.quantity)
            }
                .onSuccess {
                    confirmedInPass.update { it + 1 }
                    drafts.update { it - id }
                    if (confirmed != null && crop != null) {
                        runCatching { contributions.contribute(confirmed.id, confirmed.language, crop) }
                            .onFailure { Log.e(TAG, "queueing the contribution failed", it) }
                    }
                }
                .onFailure { Log.e(TAG, "confirming review item $id failed", it) }
            busy.update { it - id }
        }
    }

    fun skip() {
        val item = state.value.current ?: return
        val id = item.entity.id
        skippedInPass.update { it + id }
        viewModelScope.launch { runCatching { queue.skip(id) }.onFailure { Log.e(TAG, "skipping $id failed", it) } }
    }

    /** Not a card, or not worth keeping: removes the item and its crop. */
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

    companion object {
        private const val TAG = "ReviewViewModel"
    }
}
