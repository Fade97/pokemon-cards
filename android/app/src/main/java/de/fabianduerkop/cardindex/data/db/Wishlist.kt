// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * A card the user wants (#134): one print (card and language) on the wishlist. It is not a copy, so it adds nothing
 * to owned counts, set completion or the collection's value. Once the user owns a copy of that print, the entry
 * counts as found ([WantedWithCard.owned]) instead of being deleted, so undoing a scan puts it back on the list.
 * A user row like copies: uuid and updatedAt for export and sync.
 */
@Entity(
    tableName = "wanted_cards",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["id", "language"],
            childColumns = ["cardId", "cardLanguage"],
        ),
    ],
    indices = [Index("uuid", unique = true), Index("cardId", "cardLanguage", unique = true)],
)
data class WantedCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val cardId: String,
    val cardLanguage: String,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
)

/** A wishlist entry with its card and set, and how many copies of that print the user owns now. */
data class WantedWithCard(
    @Embedded val wanted: WantedCardEntity,
    @Embedded(prefix = "card_") val card: CardEntity,
    @Embedded(prefix = "set_") val set: CardSetEntity,
    val owned: Int,
) {
    /** The user has a copy of it now: it stays listed until removed, but no longer counts as wanted. */
    val found: Boolean get() = owned > 0
}

@Dao
interface WishlistDao {
    /** IGNORE: a print is on the wishlist at most once (unique index), adding it again changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(wanted: WantedCardEntity): Long

    @Query("DELETE FROM wanted_cards WHERE cardId = :cardId AND cardLanguage = :language")
    suspend fun delete(cardId: String, language: String): Int

    @Query("SELECT * FROM wanted_cards WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): WantedCardEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM wanted_cards WHERE cardId = :cardId AND cardLanguage = :language)")
    suspend fun isWanted(cardId: String, language: String): Boolean

    /** Every entry, oldest first, for the backup export. */
    @Query("SELECT * FROM wanted_cards ORDER BY createdAt, id")
    suspend fun all(): List<WantedCardEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM wanted_cards WHERE cardId = :cardId AND cardLanguage = :language)")
    fun observeIsWanted(cardId: String, language: String): Flow<Boolean>

    /** The wishlist: still wanted entries first, then found ones; newest first within each. */
    @Query(
        """
        SELECT w.*,
            c.id AS card_id, c.setId AS card_setId, c.localId AS card_localId, c.name AS card_name,
            c.language AS card_language, c.rarity AS card_rarity, c.energyType AS card_energyType,
            c.imageUrl AS card_imageUrl, c.retired AS card_retired, c.numberKey AS card_numberKey,
            c.printings AS card_printings,
            s.id AS set_id, s.name AS set_name, s.series AS set_series, s.language AS set_language,
            s.printedTotal AS set_printedTotal, s.region AS set_region, s.retired AS set_retired,
            (SELECT COALESCE(SUM(o.quantity), 0) FROM owned_copies o
                WHERE o.cardId = w.cardId AND o.cardLanguage = w.cardLanguage) AS owned
        FROM wanted_cards w
        JOIN cards c ON c.id = w.cardId AND c.language = w.cardLanguage
        JOIN card_sets s ON s.id = c.setId
        ORDER BY owned > 0, w.createdAt DESC, w.id DESC
        """,
    )
    fun observeWishlist(): Flow<List<WantedWithCard>>

    /** Entries the user owns no copy of yet: the wishlist row's count. */
    @Query(
        """
        SELECT COUNT(*) FROM wanted_cards w
        WHERE NOT EXISTS (SELECT 1 FROM owned_copies o WHERE o.cardId = w.cardId AND o.cardLanguage = w.cardLanguage)
        """,
    )
    fun observeOpenCount(): Flow<Int>
}
