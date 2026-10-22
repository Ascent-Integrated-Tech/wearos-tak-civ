
package com.aitech.weartak_civ.data.model

// Holds route info received from TAK CoT data
// A route is made up of points that form a path on the map
// Keeps track of which TAK Server and mission the route belongs to
data class TakRoute(

    // Unique ID of the route
    // Used to identify the route when it gets updated or removed
    val uid: String,

    // Name of the route
    // Can be used as a label when displaying the route
    val name: String,

    // List of points that make up the route
    // Points are stored in order so the path can be drawn correctly
    val points: List<TakRoutePoint>,

    // ID of the TAK Server the route came from
    // Keeps routes from different servers separate
    val serverId: String,

    // Name of the mission the route belongs to
    // Null if the route is not linked to a mission
    val missionName: String? = null,

    val sender: String? = null,

    val eventTime: Long? = null

)


// Holds the location info for one point along a route
// Each point has coordinates that tell the map where it belongs
// Connecting the points in order creates the full route path
data class TakRoutePoint(

    // Latitude of the route point
    // Gives the north or south position
    val latitude: Double,

    // Longitude of the route point
    // Gives the east or west position
    val longitude: Double,

    // Altitude of the point if available
    // Null if no altitude was provided
    val altitude: Double? = null,

    // Optional name or label for the point
    val name: String? = null
)
