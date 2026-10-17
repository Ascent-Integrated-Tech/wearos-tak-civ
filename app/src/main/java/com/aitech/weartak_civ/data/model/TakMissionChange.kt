
package com.aitech.weartak_civ.data.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Holds info about a change made to a Data Sync mission
// Changes can include content being added, updated, or removed
// Used to track what has changed in a mission
// Ignore any extra fields returned by the TAK Server
@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionChange(

    // Type of change made to the mission
    // Describes what happened to the content
    val type: String = "",

    // UID of the content affected by the change
    // Can be null if no content UID is provided
    val contentUid: String? = null,

    // Name of the mission where the change happened
    val missionName: String = "",

    // When the change happened
    val timestamp: String? = null,

    // UID of whoever made the change
    val creatorUid: String? = null
)
