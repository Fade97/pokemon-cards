// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.packs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PickerChoiceTest {
    private fun row(code: String, available: Boolean) =
        LanguageRow(code, wanted = false, installedVersion = null, storage = 0, broken = false, progress = null,
            downloadSize = if (available) 1L else null, available = available)

    private val state = LanguagesUiState(rows = listOf(row("en", true), row("de", true), row("nl", false)))

    @Test
    fun defaultsToTheDeviceLanguageWhenItHasAPack() {
        assertEquals(setOf("de"), state.pickerChoice(null, "de"))
    }

    @Test
    fun fallsBackToEnglishWhenTheDeviceLanguageHasNoPack() {
        assertEquals(setOf("en"), state.pickerChoice(null, "nl"))
        assertEquals(setOf("en"), state.pickerChoice(null, "sv"))
    }

    @Test
    fun picksNothingWhileTheCatalogIsLoading() {
        assertEquals(emptySet<String>(), LanguagesUiState().pickerChoice(null, "de"))
    }

    @Test
    fun keepsTheUsersChoiceButDropsLanguagesWithoutAPack() {
        assertEquals(setOf("de"), state.pickerChoice(setOf("de", "nl"), "en"))
        assertEquals(emptySet<String>(), state.pickerChoice(emptySet(), "en"))
    }

    @Test
    fun mapsTheTraditionalChineseLocalesToTheZhTwPack() {
        fun of(tag: String) = packLanguageOf(Locale.forLanguageTag(tag))
        assertEquals("zh-tw", of("zh-TW"))
        assertEquals("zh-tw", of("zh-HK"))
        assertEquals("zh-tw", of("zh-Hant"))
        assertEquals("zh-tw", of("zh-Hant-CN"))
        assertEquals("zh-cn", of("zh-CN"))
        assertEquals("zh-cn", of("zh-Hans-HK"))
        assertEquals("ko", of("ko-KR"))
        assertEquals("de", of("de-AT"))
        val zhTw = LanguagesUiState(rows = listOf(row("en", true), row("zh-tw", true)))
        assertEquals(setOf("zh-tw"), zhTw.pickerChoice(null, of("zh-TW")))
    }
}
