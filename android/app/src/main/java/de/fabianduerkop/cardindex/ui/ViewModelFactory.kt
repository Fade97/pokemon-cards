// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.fabianduerkop.cardindex.CardIndexApplication
import de.fabianduerkop.cardindex.data.AppContainer

/** A factory that hands a ViewModel the [AppContainer] and its navigation arguments. */
inline fun <reified VM : ViewModel> containerViewModelFactory(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create((this[APPLICATION_KEY] as CardIndexApplication).container, createSavedStateHandle()) }
}
