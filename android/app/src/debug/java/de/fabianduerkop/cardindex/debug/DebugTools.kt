// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.AppContainer
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.NumberReading
import de.fabianduerkop.cardindex.recognition.RefCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CollectionCopyCrossRef
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.EnergyType.FIRE
import de.fabianduerkop.cardindex.model.EnergyType.GRASS
import de.fabianduerkop.cardindex.model.EnergyType.LIGHTNING
import de.fabianduerkop.cardindex.model.EnergyType.PSYCHIC
import de.fabianduerkop.cardindex.model.EnergyType.WATER
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.Region
import java.time.Instant
import java.time.LocalDate

/**
 * Debug-only helpers. Language packs (#5) don't exist yet, so a fresh debug install has no catalog to show; the
 * sample data gives the collection screens real TCGdex ids and CDN images to work with. Never in release builds.
 */
object DebugTools {
    const val AVAILABLE = true

    private data class Sample(val localId: String, val en: String, val de: String?, val type: EnergyType)

    private val set151 = listOf(
        Sample("001", "Bulbasaur", "Bisasam", GRASS),
        Sample("002", "Ivysaur", "Bisaknosp", GRASS),
        Sample("003", "Venusaur ex", "Bisaflor-ex", GRASS),
        Sample("004", "Charmander", "Glumanda", FIRE),
        Sample("005", "Charmeleon", "Glutexo", FIRE),
        Sample("006", "Charizard ex", "Glurak-ex", FIRE),
        Sample("007", "Squirtle", "Schiggy", WATER),
        Sample("008", "Wartortle", "Schillok", WATER),
        Sample("009", "Blastoise ex", "Turtok-ex", WATER),
        Sample("025", "Pikachu", "Pikachu", LIGHTNING),
        Sample("151", "Mew ex", "Mew-ex", PSYCHIC),
    )

    suspend fun addSampleData(container: AppContainer) {
        val db = container.database
        val catalog = db.catalogDao()
        db.withTransaction {
            catalog.upsertSets(
                listOf(
                    CardSetEntity("sv03.5", "151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL),
                    CardSetEntity("sv08", "Surging Sparks", "Scarlet & Violet", "en", 191, Region.INTERNATIONAL),
                    CardSetEntity("SV2a", "ポケモンカード151", "SV", "ja", 165, Region.JP),
                ),
            )
            catalog.upsertCards(
                set151.flatMap { s ->
                    listOfNotNull(
                        card("sv03.5", s.localId, s.en, "en", s.type),
                        s.de?.let { card("sv03.5", s.localId, it, "de", s.type) },
                    )
                } + listOf(
                    card("sv08", "057", "Pikachu ex", "en", LIGHTNING),
                    card("sv08", "057", "Pikachu-ex", "de", LIGHTNING),
                    // JP cards often have no TCGdex image; these show the number placeholder.
                    CardEntity("SV2a-006", "SV2a", "006", "リザードンex", "ja", null, FIRE, imageUrl = null),
                    CardEntity("SV2a-025", "SV2a", "025", "ピカチュウ", "ja", null, LIGHTNING, imageUrl = null),
                ),
            )
            val copies = db.copyDao()
            var t = Instant.now().minusSeconds(3600)
            suspend fun own(
                cardId: String,
                language: String,
                quantity: Int = 1,
                finish: Finish = Finish.NORMAL,
                condition: Condition? = Condition.NEAR_MINT,
                grader: Grader? = null,
                grade: String? = null,
                paidCents: Long? = null,
                boughtOn: LocalDate? = null,
            ): Long {
                t = t.plusSeconds(60)
                return copies.insert(
                    OwnedCopyEntity(
                        cardId = cardId,
                        cardLanguage = language,
                        quantity = quantity,
                        finish = finish,
                        edition = Edition.UNLIMITED,
                        condition = if (grader != null) null else condition,
                        grader = grader,
                        grade = grade,
                        purchasePriceCents = paidCents,
                        purchaseCurrency = paidCents?.let { "EUR" },
                        purchaseDate = boughtOn,
                        createdAt = t,
                    ),
                )
            }
            own("sv03.5-001", "de", finish = Finish.REVERSE_HOLO)
            own("sv03.5-003", "en", finish = Finish.HOLO, condition = Condition.LIGHTLY_PLAYED)
            own("sv03.5-004", "de", quantity = 3)
            val charizard = own(
                "sv03.5-006", "de", finish = Finish.HOLO, paidCents = 3200, boughtOn = LocalDate.of(2026, 3, 12),
            )
            own("sv03.5-006", "en", finish = Finish.HOLO, grader = Grader.PSA, grade = "9", paidCents = 8500)
            own("sv03.5-009", "en", finish = Finish.HOLO)
            val pikachu = own("sv03.5-025", "en", finish = Finish.REVERSE_HOLO)
            own("sv03.5-151", "en", finish = Finish.HOLO, paidCents = 1500)
            own("sv08-057", "en", finish = Finish.HOLO, condition = Condition.MODERATELY_PLAYED)
            own("SV2a-025", "ja")

            // Adding the sample twice reuses its binder, so the list never shows two with the same name.
            val binder = db.collectionDao().allCustom().firstOrNull { it.name == "Trade binder" }?.id
                ?: db.collectionDao().insert(
                    CollectionEntity(name = "Trade binder", kind = CollectionKind.CUSTOM, updatedAt = Instant.now()),
                )
            db.collectionDao().addCopy(CollectionCopyCrossRef(binder, charizard))
            db.collectionDao().addCopy(CollectionCopyCrossRef(binder, pikachu))

            // Sample prices dated long ago, so a reachable price API replaces them on the next refresh.
            val old = Instant.parse("2026-01-01T00:00:00Z")
            fun price(cardId: String, source: PriceSource, variant: PriceVariant, cents: Long) = CachedPriceEntity(
                cardId = cardId,
                source = source,
                variant = variant,
                currency = if (source == PriceSource.CARDMARKET) "EUR" else "USD",
                trend = cents,
                low = null,
                avg = null,
                priceDate = old,
                fetchedAt = old,
                productId = null,
            )
            catalog.upsertPrices(
                listOf(
                    price("sv03.5-001", PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, 35),
                    price("sv03.5-003", PriceSource.CARDMARKET, PriceVariant.NORMAL, 420),
                    price("sv03.5-004", PriceSource.CARDMARKET, PriceVariant.NORMAL, 25),
                    price("sv03.5-006", PriceSource.CARDMARKET, PriceVariant.NORMAL, 3890),
                    price("sv03.5-006", PriceSource.TCGPLAYER, PriceVariant.HOLO, 4410),
                    price("sv03.5-009", PriceSource.CARDMARKET, PriceVariant.NORMAL, 590),
                    price("sv03.5-025", PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, 110),
                    price("sv03.5-151", PriceSource.CARDMARKET, PriceVariant.NORMAL, 1760),
                    price("sv08-057", PriceSource.CARDMARKET, PriceVariant.NORMAL, 590),
                ),
            )
        }
    }

    /**
     * Three unsure scans in the review queue, one per reason the queue shows, so the review screen can be checked
     * without a camera. The crops are plain placeholder pictures, not photos.
     */
    suspend fun addSampleReviewItems(queue: ReviewQueueRepository) = withContext(Dispatchers.IO) {
        fun ref(localId: String, lang: String, name: String) = RefCard(
            key = "$lang/sv03.5-$localId", cardId = "sv03.5-$localId", lang = lang, setId = "sv03.5", setName = "151",
            localId = localId, official = 165, name = name,
            imageUrl = "https://assets.tcgdex.net/$lang/sv/sv03.5/$localId", unseen = false,
        )
        val crop = placeholderCrop()
        queue.enqueue(
            crop,
            listOf(Match(0, ref("006", "de", "Glurak-ex"), 0.74f), Match(1, ref("006", "en", "Charizard ex"), 0.73f)),
            NumberReading("006", "165"),
            ReviewReason.SIMILAR_CARDS,
            checkVariant = false,
        )
        queue.enqueue(
            crop,
            listOf(
                Match(0, ref("025", "en", "Pikachu"), 0.61f),
                Match(1, ref("004", "en", "Charmander"), 0.52f),
                Match(2, ref("007", "en", "Squirtle"), 0.49f),
            ),
            null,
            ReviewReason.NUMBER_NOT_READ,
            checkVariant = false,
        )
        queue.enqueue(
            crop,
            listOf(Match(0, ref("151", "en", "Mew ex"), 0.69f)),
            NumberReading("151", "165"),
            ReviewReason.CHECK_VARIANT,
            checkVariant = true,
        )
        Unit
    }

    /** A grey card-shaped JPEG with a lighter frame. */
    private fun placeholderCrop(): ByteArray {
        val bitmap = createBitmap(252, 352)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF8A969B.toInt())
        val inner = Paint().apply { color = 0xFFC9D2D6.toInt() }
        canvas.drawRect(18f, 40f, 234f, 190f, inner)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun card(setId: String, localId: String, name: String, language: String, type: EnergyType) = CardEntity(
        id = "$setId-$localId",
        setId = setId,
        localId = localId,
        name = name,
        language = language,
        rarity = null,
        energyType = type,
        imageUrl = "https://assets.tcgdex.net/$language/sv/$setId/$localId",
    )
}
