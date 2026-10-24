package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.aitech.weartak_civ.ui.formatter.UnitPrefs
import com.aitech.weartak_civ.ui.formatter.UnitPrefsState

@Composable
fun currentUnitPrefs(): UnitPrefs = UnitPrefsState.prefs.collectAsState().value
