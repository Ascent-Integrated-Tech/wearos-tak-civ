package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex

object TakServerEndpointIdentity {
    val mutationMutex = Mutex()

    fun key(address: String, port: Int): String {
        val normalizedAddress = address.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .removeSuffix(".")
            .lowercase()
        val formattedAddress = if (normalizedAddress.contains(":")) {
            "[$normalizedAddress]"
        } else {
            normalizedAddress
        }
        return "$formattedAddress:$port"
    }

    fun key(item: DataRepository.TakServerItem): String = key(item.address, item.port)
}
