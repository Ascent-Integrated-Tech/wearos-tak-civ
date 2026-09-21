/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.model

data class TakChannel(
    val name: String,
    val direction: String,
    val active: Boolean,
    val bitPosition: Int,
    val updating: Boolean = false,
)

data class TakServerChannels(
    val serverId: String,
    val serverName: String,
    val status: TakChannelStatus,
    val channels: List<TakChannel>,
    val errorMessage: String? = null,
)

enum class TakChannelStatus {
    LOADING,
    READY,
    EMPTY,
    UNSUPPORTED,
    ERROR,
}
