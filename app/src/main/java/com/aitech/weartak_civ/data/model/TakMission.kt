
package com.aitech.weartak_civ.data.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Holds the mission info returned by the TAK Server
// Includes basic mission details and the items shared in the mission
// Ignore any extra JSON fields that aren't included in the model
@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMission(

    // Name of the mission
    val name: String = "",

    // UID of whoever created the mission
    val creatorUid: String? = null,

    // When the mission was created
    val createTime: String? = null,

    // Tags or keywords linked to the mission
    val keywords: List<String> = emptyList(),

    // List of CoT item UIDs linked to this mission
    // Each entry can also include when it was added and who added it
    val uids: List<TakMissionUid> = emptyList(),

    // List of content shared in this mission
    // Includes details about the shared items
    val contents: List<TakMissionContent> = emptyList()
)


// Holds info about a CoT item linked to a mission
// Used to keep track of the items associated with the mission
// Extra fields from the server are ignored
@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionUid(

    // UID of the CoT item
    val data: String = "",

    // Timestamp linked to this item
    val timestamp: String? = null,

    // UID of whoever created this entry
    val creatorUid: String? = null
)


// Holds info about content shared in a mission
// Includes the content details and who added it
// Extra JSON fields are ignored
@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionContent(

    // Details about the shared content
    // Can be null if no content details are returned
    val data: TakMissionContentData? = null,

    // Timestamp linked to this content entry
    val timestamp: String? = null,

    // UID of whoever created this entry
    val creatorUid: String? = null
)


// Holds the details for an individual piece of mission content
// Includes the name, file type, size, and who submitted it
// These fields come from the mission data returned by the server
// Extra JSON fields are ignored
@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionContentData(

    // Name of the shared item
    val name: String? = null,

    // Type of content, such as an image or file
    val mimeType: String? = null,

    // When the content was submitted
    val submissionTime: String? = null,

    // Who submitted the content
    val submitter: String? = null,

    // UID linked to this content item
    val uid: String? = null,

    // UID of whoever created the content
    val creatorUid: String? = null,

    // Hash used to identify or check the content
    val hash: String? = null,

    // Size of the content in bytes
    val size: Long? = null,

    // Tags or keywords linked to this content
    val keywords: List<String> = emptyList()
)
