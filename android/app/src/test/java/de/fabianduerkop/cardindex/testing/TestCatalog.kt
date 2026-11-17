// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Region
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

fun inMemoryDatabase(): CardIndexDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun card(id: String, setId: String, localId: String, language: String, type: EnergyType? = EnergyType.FIRE) =
    CardEntity(id, setId, localId, "Card $localId", language, null, type, imageUrl = null)

/** 151 (international, 165) with EN and DE prints, and SV2a (JP, 165). */
suspend fun CardIndexDatabase.seedCatalog() {
    catalogDao().upsertSets(
        listOf(
            CardSetEntity("sv03.5", "151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL),
            CardSetEntity("SV2a", "ポケモンカード151", "SV", "ja", 165, Region.JP),
        ),
    )
    catalogDao().upsertCards(
        listOf(
            card("sv03.5-001", "sv03.5", "001", "en", EnergyType.GRASS),
            card("sv03.5-006", "sv03.5", "006", "en"),
            card("sv03.5-006", "sv03.5", "006", "de"),
            card("sv03.5-025", "sv03.5", "025", "de", EnergyType.LIGHTNING),
            card("sv03.5-180", "sv03.5", "180", "en"),
            card("SV2a-006", "SV2a", "006", "ja"),
        ),
    )
}

/** A clock the test moves by hand, so updatedAt values are exact. */
class TestClock(var now: Instant = Instant.parse("2026-11-17T10:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}
