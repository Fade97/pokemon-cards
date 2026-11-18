// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.ui.graphics.Color
import de.fabianduerkop.cardindex.model.EnergyType

/**
 * Energy fills for owned slots. Used only for data, never for chrome (ADR 0010). From the mockup tokens, tuned so
 * slot numbers reach 4.5:1: white on the dark fills, ink on lightning, colorless and trainer. Metal and dragon are
 * not in the mockups and follow the same rule.
 */
object EnergyColors {
    val Grass = Color(0xFF40813C)
    val Fire = Color(0xFFCD4321)
    val Water = Color(0xFF3274C6)
    val Lightning = Color(0xFFE2AE1C)
    val Psychic = Color(0xFF9555BF)
    val Fighting = Color(0xFFA66239)
    val Darkness = Color(0xFF3B4055)
    val Metal = Color(0xFF687585)
    val Dragon = Color(0xFF8A6D1E)
    val Colorless = Color(0xFFA8A196)
    val Trainer = Color(0xFF8C96A6)

    fun fill(type: EnergyType): Color = when (type) {
        EnergyType.GRASS -> Grass
        EnergyType.FIRE -> Fire
        EnergyType.WATER -> Water
        EnergyType.LIGHTNING -> Lightning
        EnergyType.PSYCHIC -> Psychic
        EnergyType.FIGHTING -> Fighting
        EnergyType.DARKNESS -> Darkness
        EnergyType.METAL -> Metal
        EnergyType.DRAGON -> Dragon
        EnergyType.COLORLESS -> Colorless
        EnergyType.TRAINER -> Trainer
    }

    /** Text colour on [fill]. */
    fun onFill(type: EnergyType): Color = when (type) {
        EnergyType.LIGHTNING, EnergyType.COLORLESS, EnergyType.TRAINER -> PearlLight.Ink
        else -> Color.White
    }
}
