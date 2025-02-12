package com.aitech.weartak_civ.presentation

import android.app.Activity.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.NwConnEvent.Companion.CELLULAR
import org.greenrobot.eventbus.EventBus
import androidx.lifecycle.MutableLiveData


class NetworkManager : ConnectivityManager.NetworkCallback()  {

    private var mainActivity : MainActivity? = null
    private var connectivityManager : ConnectivityManager? = null
    private val handler = Handler(Looper.getMainLooper())
//    var isNetworkConnected : Boolean = false
//    private var isWifi = false
//    private var isCellular = false


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

    fun isNetworkConnected() : Boolean {
        return isNetworkConnected
    }

    fun isWifiConnected(): Boolean {
        return isWifi
    }

    fun checkNetworkConnected() {
        val activeNetwork = connectivityManager?.activeNetwork
        val networkCapabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        var networkInfoChanged = false
        Log.d(TAG, "checkNetworkConnected called w/ activeNetwork (${activeNetwork?.toString()}); networkCapabilities (${networkCapabilities?.toString()})")
        if ((null != activeNetwork) && (null != networkCapabilities)) {
            if (isNetworkConnected == false) {
                isNetworkConnected = true
                networkInfoChanged = true
                isNetworkConnectedLiveData.postValue(isNetworkConnected)
            }

            val currIsWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWifi != currIsWifi) {
                isWifi = currIsWifi
                networkInfoChanged = true
                isWifiConnectedLiveData.postValue(isWifi)
            }
            val currIsCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (isCellular != currIsCellular) {
                isCellular = currIsCellular
                networkInfoChanged = true
                isCellularConnectedLiveData.postValue(isCellular)
            }
        } else {
            if (isNetworkConnected == true) {
                isNetworkConnected = false
                networkInfoChanged = true
                isNetworkConnectedLiveData.postValue(isNetworkConnected)
            }
            if (isWifi == true) {
                isWifi = false
                networkInfoChanged = true
                isWifiConnectedLiveData.postValue(isWifi)
            }
            if (isCellular == true) {
                isCellular = false
                networkInfoChanged = true
                isCellularConnectedLiveData.postValue(isCellular)
            }
        }
        Log.d(TAG, "isNetworkConnected = $isNetworkConnected; isWifi = $isWifi; isCellular = $isCellular")
        if (networkInfoChanged) {
            Log.d(TAG, "Change detected in network connection information.Sending NwConnEvent")
            EventBus.getDefault().post(NwConnEvent(isNetworkConnected, if (isWifi == true) { WIFI } else { CELLULAR }))
            // Just for testing. Please remove line below.
            //Toast.makeText(mainActivity?.applicationContext, "nwConn = $isNetworkConnected; wifi = $isWifi; cell = $isCellular", Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "No change detected in network connection information.So NOT sending NwConnEvent")
        }
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
        // Immediately checking network connectivity when previously only on cellular via checkNetworkConnected sometimes returns network (especially cellular) as still being connected.
        // Hence we call checkNetworkConnected function twice with delay of 2s and 10s. Never seen 2s fail to give the correct information but just in case, we do a repeat after 8 seconds.
        handler.postDelayed({ Log.d(TAG, "Calling checkNetworkConnected from onLost with delay $INITIAL_ON_LOST_DELAY_MS"); checkNetworkConnected() }, INITIAL_ON_LOST_DELAY_MS)
        handler.postDelayed({ Log.d(TAG, "Calling checkNetworkConnected from onLost with delay $RECHECK_ON_LOST_DELAY_MS"); checkNetworkConnected() }, RECHECK_ON_LOST_DELAY_MS)
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
        private var INITIAL_ON_LOST_DELAY_MS = 2000L
        private var RECHECK_ON_LOST_DELAY_MS = 10000L
        var isNetworkConnected : Boolean = false
        val isNetworkConnectedLiveData = MutableLiveData<Boolean>(false)
        var isWifi : Boolean = false
        val isWifiConnectedLiveData = MutableLiveData<Boolean>(false)
        var isCellular : Boolean = false
        val isCellularConnectedLiveData = MutableLiveData<Boolean>(false)


    }
}