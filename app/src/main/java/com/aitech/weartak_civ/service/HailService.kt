package com.aitech.weartak_civ.service

import android.content.Context
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.MainActivity
import com.rain.hail.sdk.HailDetail
import com.rain.hail.sdk.licensing.ILicenseServiceCallbacks
import com.rain.hail.sdk.licensing.IPairingCodeCallbacks
import com.rain.hail.sdk.licensing.LicensingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

object HailService {
    val TAG = "WearTAK-HailService"
    // State surface for UI
    @Volatile var licenseChecked: Boolean = false
        private set
    @Volatile var licensed: Boolean = false
        private set
    @Volatile var licenseSoftExpired: Boolean = false
        private set
    @Volatile var licenseHardExpired: Boolean = false
        private set
    @Volatile var lastPairingCode: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var licenseState: HailLicenseStates? = null
        private set
    private fun repo() = MainActivity.dataRepository

    /**
     * Initialize licensing.
     *
     * appId: package application context com.aitech.weartak_civ
     * context: WearOS app context
     * deviceUid: a stable per-device identifier you choose
     *
     *  e.g. --> initialize(
     *          appId="com.rain.hail",
     *          ctx=MainActivity.applicationContext,
     *          deviceUid="WEAROS-abeb9c116e41b389"
     *          )
     */
    fun initialize(appId: String, context: Context, deviceUid: String) {
        LicensingManager.initialize(appId, context, deviceUid)  // per SDK
        Log.i(TAG, "initialize(appId=$appId, ctx=${context.applicationContext.javaClass.simpleName}, deviceUid=$deviceUid)")
        CoroutineScope(Dispatchers.IO).launch {
            licenseState = repo().getHailLicenseState.first() as HailLicenseStates?
        }
        /*
        if (LicensingManager.isLicenseChecked()) {

            if (LicensingManager.isLicensed()) {

                // Check for soft expiration warning (still licensed but show renewal notice)
                if (LicensingManager.isLicenseSoftExpired()) {
                    Log.i(TAG, "Soft Expired") // Grace period - app still works
                }

                // License is good, proceed normally
                Log.i(TAG, "App Run fine")

            } else {
                // Check if token is valid but expired
                if (LicensingManager.isTokenValid()) {
                    if (LicensingManager.isLicenseExpired()) {
                        Log.i(TAG, "Licensed Hard Expired")
                    } else {
                        Log.i(TAG, "Other Issue. Show License Dialog")
                    }
                } else {
                    Log.i(TAG, "Invalid Token. Show License Dialog")

                }
            }
        } else {
            Log.i(TAG, "License is Not Checked")
            LicensingManager.checkLicense(object : ILicenseServiceCallbacks {
                override fun onLicenseValid() {
                    licenseChecked = true
                    licensed           = LicensingManager.isLicensed()
                    licenseSoftExpired = LicensingManager.isLicenseSoftExpired()
                    licenseHardExpired = LicensingManager.isLicenseExpired()
                    Log.i(TAG, "License VALID (licensed=$licensed, soft=$licenseSoftExpired, hard=$licenseHardExpired)")
                    repo().hailServiceAuthorized.value = true
                    Log.i(TAG, "isLicenseChecked() = ${LicensingManager.isLicenseChecked()}")
                }
                override fun onLicenseInvalid() {
                    lastError = "License invalid (soft=${LicensingManager.isLicenseSoftExpired()}, hard=${LicensingManager.isLicenseExpired()})"
                    Log.w(TAG, lastError!!)
                    Log.i(TAG, "isLicenseChecked() = ${LicensingManager.isLicenseChecked()}")
                }
            })
        }
        */

        checkLicense {
            if (LicensingManager.isLicensed()) {
                return@checkLicense
            } else {
                Log.i(TAG, "License is not valid ... checking for token validity.")

                if (!LicensingManager.isTokenValid()) {
                    Log.i(TAG, "Token is not valid or missing ... fetching pairing code.")
                    fetchPairingCode {
                        if (lastError == null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                Log.i(TAG, "Received no error fetching code ... waiting for user to press the button.")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Token is valid, but License is NOT. License is Invalid.")
                }
            }
        }
    }

    /**
     * Represents the possible states of the Hail license.
     *
     * - VALID: License is active and fully valid.
     * - CHECKING: License is being checked on the first time you are pairing with Rain cloud. Will go to VALID or INVALID afterwards.
     * - SOFT_EXPIRED: License is still valid, but will expire soon; UI should indicate this.
     * - EXPIRED: License is no longer valid; stop adding Hail detail to CoT messages in the dispatcher.
     * - INVALID: Default or catch-all state (e.g., on first app launch before pairing, or after certain errors);
     *   requires obtaining a pairing code or other recovery action.
     */
    enum class HailLicenseStates {
        VALID,
        CHECKING,
        SOFT_EXPIRED,
        EXPIRED,
        INVALID
    }

    data class HailLicenseInfo(
        var licenseState: HailLicenseStates? = null,
        var pairingCode: String? = null,
        var expirationDate: String? = null,
        var softExpirationDate: String? = null,
        var accountNumber: String? = null,
        var licenseId: String? = null,
        var deviceId: String? = null
    )

    fun isHailLicenseValid(): Boolean {
        if (LicensingManager.isLicenseChecked()) {
            if (LicensingManager.isLicensed()) {

                // License is good, proceed normally
                // CHANGE THE STATE
                updateHailLicenseState(HailLicenseStates.VALID)
                return true

            } else {
                // Check if token is valid but expired
                if (LicensingManager.isTokenValid()) {
                    if (LicensingManager.isLicenseExpired()) {
                        Log.i(TAG, "Licensed Hard Expired")
                        updateHailLicenseState(HailLicenseStates.EXPIRED)
                    } else {
                        Log.i(TAG, "Other Issue. Show License Dialog")
                        updateHailLicenseState(HailLicenseStates.INVALID)
                    }
                } else {
                    Log.i(TAG, "Invalid Token. Show License Dialog")
                    updateHailLicenseState(HailLicenseStates.INVALID)
                }
                return false
            }
        } else {
            return false
        }
        return false
    }

    /**
     * Trigger an async license check and update state for UI.  [oai_citation:5‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun checkLicense(onChanged: (() -> Unit)? = null) {
        updateHailLicenseState(HailLicenseStates.CHECKING)
        licenseChecked = false
        licensed = false
        licenseSoftExpired = false
        licenseHardExpired = false
        lastError = null
        Log.i(TAG, "Checking License ... ")

        LicensingManager.checkLicense(object : ILicenseServiceCallbacks {
            override fun onLicenseValid() {
                licenseChecked = true
                licensed           = LicensingManager.isLicensed()
                licenseSoftExpired = LicensingManager.isLicenseSoftExpired()
                licenseHardExpired = LicensingManager.isLicenseExpired()
                Log.i(TAG, "License VALID (licensed=$licensed, soft=$licenseSoftExpired, hard=$licenseHardExpired)")
                updateHailLicenseState(HailLicenseStates.VALID)
                onChanged?.invoke()
            }
            override fun onLicenseInvalid() {
                lastError = "License invalid (soft=${LicensingManager.isLicenseSoftExpired()}, hard=${LicensingManager.isLicenseExpired()})"
                Log.w(TAG, lastError!!)
                updateHailLicenseState(HailLicenseStates.INVALID)
                onChanged?.invoke()
            }
        })
    }

    fun updateHailLicenseState(state: HailLicenseStates) {
        CoroutineScope(Dispatchers.IO).launch {
            if (state.toString() == repo().getHailLicenseState.first()) {
                return@launch
            }
            repo().storeHailLicenseState(state)
        }
    }

    /**
     * Fetch pairing code (device registration).  [oai_citation:7‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun fetchPairingCode(onChanged: (() -> Unit)? = null) {
        lastError = null
        lastPairingCode = null
        LicensingManager.fetchPairingCode(object : IPairingCodeCallbacks {
            override fun onPairingCodeSuccess(pairingCode: String) {
                lastPairingCode = pairingCode
                Log.i(TAG, "Successfully retrieved a Pairing code: $pairingCode ... storing it")
                CoroutineScope(Dispatchers.IO).launch { repo().storePairingCode(pairingCode) }
                onChanged?.invoke()
            }
            override fun onPairingCodeFailure(error: String) {
                lastError = "Failed to fetch a pairing code: $error"
                Log.w(TAG, lastError!!)
                onChanged?.invoke()
            }
        })
    }

    private var registrationJob: Job? = null

    /**
     * Entitlements helpers (feature flags / limits).  [oai_citation:8‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun hasEntitlementBoolean(key: String): Boolean {
        return LicensingManager.getEntitlementBoolean(key)
    }

    fun getEntitlementString(key: String): String? {
        return LicensingManager.getEntitlement(key)
    }

    /**
     * Encode a HAIL payload to a CoT <detail> body string.
     * Returns Result<String> so callers can surface license errors cleanly.  [oai_citation:9‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     *
     * IMPORTANT: Pass a **user-stable** combatId you generate/store once per user.  [oai_citation:10‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun encodeHailDetail(
        combatId: Int,
        callsign: String,
        builder: ( HailDetail) -> Unit
    ): Result<String> {
        return try {
            val hail = HailDetail(combatId, callsign)
            builder(hail)
            val encoded: String = hail.toCotDetailBody()  // throws HailEncodingException on license issues
            Result.success(encoded)
//            Result.failure(IllegalStateException("Hook up HailDetail once AAR imports are confirmed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // pseudo types: replace with your real Hail SDK classes/constants
// val HAIL_DETAIL = "hail"  // e.g., HailDetail.HAIL_DETAIL
// val HAIL_DETAIL_ENCODE_VERSION = 1  // e.g., HailDetail.HAIL_DETAIL_ENCODE_VERSION

    fun createHailCotDetail(
        hailDetail: Any,                      // replace with actual type
        detailTagName: String,                // e.g., HailDetail.HAIL_DETAIL
        encodeVersion: Int,                   // e.g., HailDetail.HAIL_DETAIL_ENCODE_VERSION
        encode: (Any) -> String               // e.g., { it as HailDetail -> it.toCotDetailBody() }
    ): Utils.CotUtils.CotDetail? {
        return try {
            val hailEncoded = encode(hailDetail)          // may throw HailEncodingException
            Utils.CotUtils.CotDetail(detailTagName)
                .setAttribute("version", encodeVersion.toString())
                .setInnerText(hailEncoded)
        } catch (e: Exception) {
            // If you need to distinguish license vs general error, catch your SDK’s specific exception first
            Log.e("HAIL", "Error creating Hail CotDetail: ${e.message}")
            null
        }
    }

    /**
     * Optional helpers to expose license metadata (dates, ids).  [oai_citation:12‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun getLicenseInfo(): Map<String, Any?> {
        return mapOf(
             "expiration" to LicensingManager.expirationDate,
             "softExpiration" to LicensingManager.softExpirationDate,
             "accountNumber" to LicensingManager.accountNumber,
             "licenseId" to LicensingManager.licenseId,
             "deviceId" to LicensingManager.deviceId
        )
    }



}