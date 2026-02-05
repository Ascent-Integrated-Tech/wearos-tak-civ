/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.events

/**
 * Carries a fully built CoT XML string plus the Ditto collection name.
 */
data class DittoDocumentEvent(
    val collection: String,    // "mapitem" or "alerts" (future: "tracks")
    val cotXml: String
)