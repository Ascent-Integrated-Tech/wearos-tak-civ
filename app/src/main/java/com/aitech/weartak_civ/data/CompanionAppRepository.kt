/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "companion_app")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class CompanionAppRepository(private val context: Context) {
    private val TAG = "WearTAK-CompanAppRepo"

    private val _bleConnected = MutableStateFlow<Boolean>(false)
    val bleConnected: StateFlow<Boolean> = _bleConnected

    companion object {

    }
}
