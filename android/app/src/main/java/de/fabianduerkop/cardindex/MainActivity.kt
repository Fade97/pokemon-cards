// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.fabianduerkop.cardindex.ui.CardIndexApp
import de.fabianduerkop.cardindex.ui.theme.CardIndexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CardIndexTheme { CardIndexApp() } }
    }
}
