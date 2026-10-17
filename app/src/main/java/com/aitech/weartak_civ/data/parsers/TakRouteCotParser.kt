
package com.aitech.weartak_civ.data.parsers

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import android.util.Log

// Reads incoming route CoT XML and turns it into a TakRoute object
// CoT messages use XML to store route details and coordinates
// Pulls out the route UID, name, mission, and ordered route points
// Returns null if the message is not a valid route
object TakRouteCotParser {

    // Takes the raw CoT XML and the ID of the server it came from
    // Goes through the XML to find route information and coordinates
    // Builds a TakRoute object if the route has a valid UID and points
    fun parse(rawXml: String, serverId: String): TakRoute? {
        return try {

            // Create an XML parser to read the CoT message
            val parser = XmlPullParserFactory.newInstance().newPullParser()

            // Give the parser the raw XML as input
            parser.setInput(StringReader(rawXml))

            // UID used to identify the route
            var routeUid: String? = null

            // Name of the route shown in TAK
            var routeName: String? = null

            // Mission name if the route is linked to a Data Sync mission
            var missionName: String? = null

            // Callsign of the person who created or sent the route
            var sender: String? = null

            // Tracks whether the CoT event is actually a route
            var isRoute = false

            // Tracks whether the parser is inside the detail section of the XML
            var inDetail = false

            // Stores the route points in the order they appear in the XML
            val points = mutableListOf<TakRoutePoint>()

            // Start reading from the first XML event
            var event = parser.eventType

            // Keep reading until the end of the XML document
            while (event != XmlPullParser.END_DOCUMENT) {

                // Check when a new XML tag starts
                if (event == XmlPullParser.START_TAG) {

                    // Handle different tags based on their names
                    when (parser.name) {

                        "remarks" -> {
                            if (isRoute && inDetail) {
                                val source = parser.getAttributeValue(null, "source")
                                if (!source.isNullOrBlank()) {
                                    sender = source
                                }
                            }
                        }

                        "event" -> {

                            // Check whether the CoT event type is a route
                            // b-m-r is the TAK event type used for routes
                            isRoute = parser.getAttributeValue(null, "type") == "b-m-r"

                            // Only get the UID if the message is a route
                            if (isRoute) {
                                routeUid = parser.getAttributeValue(null, "uid")
                            }
                        }

                        "detail" -> {

                            // Mark the start of the detail section for a route
                            // Route names, mission info, and points are read from this section
                            if (isRoute) inDetail = true
                        }

                        "contact" -> {

                            // Read the route name from the contact callsign
                            // Only look for this field inside a route detail section
                            if (isRoute && inDetail) {
                                routeName = parser.getAttributeValue(null, "callsign")
                            }
                        }

                        "dest" -> {

                            // Check for a mission name linked to the route
                            // The mission attribute is optional
                            if (isRoute && inDetail) {
                                missionName = parser.getAttributeValue(null, "mission")
                            }
                        }

                        "link" -> {

                            // Each link can contain a point along the route
                            // Only read links inside a route detail section
                            if (isRoute && inDetail) {

                                // Get the point attribute containing the coordinates
                                // Coordinates are expected in latitude,longitude,altitude order
                                val coordinateString =
                                    parser.getAttributeValue(null, "point")

                                // Split the coordinate string at each comma
                                // Remove extra spaces from each value
                                val coordinates = coordinateString
                                    ?.split(",")
                                    ?.map { it.trim() }

                                // Make sure the point has at least latitude and longitude
                                if (coordinates != null && coordinates.size >= 2) {

                                    // Read latitude from the first value
                                    val lat = coordinates[0].toDoubleOrNull()

                                    // Read longitude from the second value
                                    val lon = coordinates[1].toDoubleOrNull()

                                    // Read altitude from the third value if available
                                    val alt = coordinates.getOrNull(2)?.toDoubleOrNull()

                                    // Check that latitude and longitude are valid numbers
                                    // Latitude must be between -90 and 90
                                    // Longitude must be between -180 and 180
                                    // Altitude must be a valid number if provided
                                    if (lat != null && lon != null &&
                                        lat.isFinite() && lon.isFinite() &&
                                        lat in -90.0..90.0 &&
                                        lon in -180.0..180.0 &&
                                        (alt == null || alt.isFinite())
                                    ) {

                                        // Create a route point using the valid coordinates
                                        // Include the point callsign as its name if available
                                        // Add the point to the list in the order it was found
                                        points.add(
                                            TakRoutePoint(
                                                latitude = lat,
                                                longitude = lon,
                                                altitude = alt,
                                                name = parser.getAttributeValue(
                                                    null, "callsign"
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                } else if (event == XmlPullParser.END_TAG) {

                    // Check when the detail section ends
                    // Stop reading detail fields after leaving this section
                    if (parser.name == "detail") {
                        inDetail = false
                    }
                }

                // Move to the next XML event
                // Continue until the entire document has been checked
                event = parser.next()
            }

            // Make sure the route has a UID
            // Return null if the UID is missing or blank
            val uid = routeUid?.takeIf { it.isNotBlank() }
                ?: return null

            // Make sure the message is a route and contains at least one valid point
            // Skip messages that do not meet these requirements
            if (!isRoute || points.isEmpty()) return null

            // Build the final TakRoute object using the parsed information
            // Use the UID as the route name if no name was provided
            // Keep the source server ID and optional mission name with the route
            TakRoute(
                uid = uid,
                name = routeName?.takeIf { it.isNotBlank() } ?: uid,
                points = points,
                serverId = serverId,
                missionName = missionName,
                sender = sender
            )

        } catch (e: Exception) {
            Log.w("RouteCoT", "Unable to parse route CoT")
            null
        }
    }
}
