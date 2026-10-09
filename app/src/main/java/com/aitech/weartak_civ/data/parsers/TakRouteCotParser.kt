
package com.aitech.weartak_civ.data.parsers

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object TakRouteCotParser {

    fun parse(rawXml: String, serverId: String): TakRoute? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(rawXml))

            var routeUid: String? = null
            var routeName: String? = null
            var missionName: String? = null
            var isRoute = false
            var inDetail = false
            val points = mutableListOf<TakRoutePoint>()

            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "event" -> {
                            isRoute = parser.getAttributeValue(null, "type") == "b-m-r"
                            if (isRoute) {
                                routeUid = parser.getAttributeValue(null, "uid")
                            }
                        }

                        "detail" -> {
                            if (isRoute) inDetail = true
                        }

                        "contact" -> {
                            if (isRoute && inDetail) {
                                routeName = parser.getAttributeValue(null, "callsign")
                            }
                        }

                        "dest" -> {
                            if (isRoute && inDetail) {
                                missionName = parser.getAttributeValue(null, "mission")
                            }
                        }

                        "link" -> {
                            if (isRoute && inDetail) {
                                val coordinateString =
                                    parser.getAttributeValue(null, "point")

                                val coordinates = coordinateString
                                    ?.split(",")
                                    ?.map { it.trim() }

                                if (coordinates != null && coordinates.size >= 2) {
                                    val lat = coordinates[0].toDoubleOrNull()
                                    val lon = coordinates[1].toDoubleOrNull()
                                    val alt = coordinates.getOrNull(2)?.toDoubleOrNull()

                                    if (lat != null && lon != null &&
                                        lat.isFinite() && lon.isFinite() &&
                                        lat in -90.0..90.0 &&
                                        lon in -180.0..180.0 &&
                                        (alt == null || alt.isFinite())
                                    ) {
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
                    if (parser.name == "detail") {
                        inDetail = false
                    }
                }

                event = parser.next()
            }

            val uid = routeUid?.takeIf { it.isNotBlank() }
                ?: return null

            if (!isRoute || points.isEmpty()) return null

            TakRoute(
                uid = uid,
                name = routeName?.takeIf { it.isNotBlank() } ?: uid,
                points = points,
                serverId = serverId,
                missionName = missionName

            )
        } catch (e: Exception) {
            null
        }
    }
}
