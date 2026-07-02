/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 */

package com.aitech.weartak_civ.events

/**
 * Posted when an alert is cleared (e.g. exit condition met or user cancelled).
 * UI (e.g. UserWarnActivity) can finish when it was showing this alert.
 */
data class AlertClearedEvent(val uid: String)
