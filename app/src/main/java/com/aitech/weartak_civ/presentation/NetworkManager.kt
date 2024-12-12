package com.aitech.weartak_civ.presentation

import android.app.Activity.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData


class NetworkManager : ConnectivityManager.NetworkCallback()  {

    private var mainActivity : MainActivity? = null
    private var connectivityManager : ConnectivityManager? = null
    private var isNetworkConnected = false
    private var isWifi = false
    private var isCellular = false


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
        // TODO: Just for testing. Please remove
        Toast.makeText(mainActivity?.applicationContext, "nwConn = $isNetworkConnected; wifi = $isWifi; cell = $isCellular", Toast.LENGTH_LONG).show()
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
        checkNetworkConnected()
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