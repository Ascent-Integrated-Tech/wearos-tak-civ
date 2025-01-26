package com.aitech.weartak_civ.presentation

import android.app.Activity.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.NwConnEvent.Companion.CELLULAR
import org.greenrobot.eventbus.EventBus


class NetworkManager : ConnectivityManager.NetworkCallback()  {

    private var mainActivity : MainActivity? = null
    private var connectivityManager : ConnectivityManager? = null
    var isNetworkConnected = false
    var isWifi = false
    var isCellular = false


    fun init() {
        Log.d(TAG, "init called")
        mainActivity = MainActivity.getActivity()
        connectivityManager = mainActivity?.applicationContext?.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager?.registerDefaultNetworkCallback(this)
        checkNetworkConnected()
    }

    fun deInit() {
        if (null != connectivityManager) {
            connectivityManager?.unregisterNetworkCallback(this)
        }
    }

    fun checkNetworkConnected() {
        val activeNetwork = connectivityManager?.activeNetwork
        val networkCapabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        //Log.d(TAG, "checkNetworkConnected called w/ activeNetwork (${activeNetwork?.toString()}); networkCapabilities (${networkCapabilities?.toString()})")
        if ((null != activeNetwork) && (null != networkCapabilities)) {
            isNetworkConnected = true
            isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            isNetworkConnected = false
            isWifi = false
            isCellular = false
        }
        Log.d(TAG, "isNetworkConnected = $isNetworkConnected; isWifi = $isWifi; isCellular = $isCellular")
        EventBus.getDefault().post(NwConnEvent(isNetworkConnected, if (isWifi == true) { WIFI } else {CELLULAR}))
        // TODO: Just for testing. Please remove
        //Toast.makeText(mainActivity?.applicationContext, "nwConn = $isNetworkConnected; wifi = $isWifi; cell = $isCellular", Toast.LENGTH_LONG).show()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        Log.d(TAG, "onCapabilitiesChanged called")
        checkNetworkConnected()
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "onAvailable called")
        checkNetworkConnected()
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(TAG, "onLost called")
        // TODO: Check below
        checkNetworkConnected()
        /*
        isNetworkConnected = false
        isWifi = false
        isCellular = false
         */
    }

    override fun onUnavailable() {
        super.onUnavailable()
        Log.d(TAG, "onUnavailable called")
        checkNetworkConnected()
    }

    companion object {
        private val TAG = "WearTAK-" + NetworkManager::class.java.simpleName
        private val NETWORK_MANAGER = NetworkManager()
        public fun getInstance() : NetworkManager {
            return NETWORK_MANAGER
        }
    }
}