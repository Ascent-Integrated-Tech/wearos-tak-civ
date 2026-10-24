package com.aitech.weartak_civ.service

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal suspend fun runDataSyncSession(
    enabled: Flow<Boolean>,
    connectionReady: () -> Boolean,
    load: suspend () -> Unit
) = coroutineScope {
    val polling = launch {
        enabled.distinctUntilChanged().collectLatest {
            if (it && connectionReady()) load()
        }
    }
    try {
        while (connectionReady()) delay(1_000)
    } finally {
        polling.cancelAndJoin()
    }
}
