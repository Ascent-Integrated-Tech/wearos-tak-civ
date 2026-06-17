/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 * Moving Ditto initialization to onCreate of DittoService as initializing Ditto takes a lot of resources and
 * causes ANR when the Application is started on boot. Also Ditto initialization should be done only if Ditto service
 * is started.
 * Use WearTakApplication to create a single, globally accessible instance that persists for the app's entire lifecycle.
 */

package com.aitech.weartak_civ

import android.app.Application
import android.content.Context
import android.provider.Settings.Secure
import android.util.Log
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.CrmRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.GunshotOnnxRepository
import com.aitech.weartak_civ.data.RawPpgRepository
import com.aitech.weartak_civ.data.UasOnnxRepository
import com.aitech.weartak_civ.data.secure.migration.TakServerMigration
import com.aitech.weartak_civ.data.secure.sitx.SitxEncryptedDataStore
import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsRepository
import com.aitech.weartak_civ.data.secure.takserver.TakServerEncryptedDataStore
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.android.DefaultAndroidDittoDependencies

class WearTakApplication : Application() {

    lateinit var repo: DataRepository; private set
    lateinit var bhRepo: BloodhoundRepository; private set
    lateinit var capRepo: CompanionAppRepository; private set
    lateinit var sitxRepo: SitxSecretsRepository; private set
    lateinit var takServerRepo: TakServerSecretsRepository; private set
    lateinit var audioRepo: AudioCaptureRepository; private set
    lateinit var rawPpgRepo: RawPpgRepository; private set
    lateinit var crmRepo: CrmRepository; private set

    lateinit var gunshotOnnxRepo: GunshotOnnxRepository; private set
    lateinit var uasOnnxRepo: UasOnnxRepository; private set

    companion object {
        val TAG = "WearTAK-Application"
    }

    override fun onCreate() {
        super.onCreate()
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)
        takServerRepo = TakServerSecretsRepository(TakServerEncryptedDataStore(applicationContext))
        repo = DataRepository(applicationContext)
        bhRepo = BloodhoundRepository(applicationContext)
        capRepo = CompanionAppRepository(applicationContext)
        audioRepo = AudioCaptureRepository(applicationContext)
        rawPpgRepo = RawPpgRepository()
        crmRepo = CrmRepository()
        gunshotOnnxRepo = GunshotOnnxRepository(applicationContext)
        uasOnnxRepo = UasOnnxRepository(applicationContext)
        sitxRepo = SitxSecretsRepository(SitxEncryptedDataStore(applicationContext))

        AppCtx.init(this)
        Log.i(TAG, "WearTakApplication Created")
        // Wipe debug mode to false on every boot! 
//        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
//            repo.storeEnableWearTakDevMode(false)
//        }
        // Wipe debug mode to false on every boot!
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repo.storeEnableWearTakDevMode(false)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            TakServerMigration(repo.legacyDataStore(), takServerRepo).runIfNeeded()
        }
        CepProvider.init(this)
        CepProvider.getOrCreateEngine(this).start()
    }

    object AppCtx {
        @Volatile private var _app: Application? = null
        val app: Application get() = _app ?: error("AppCtx not initialized")
        fun init(a: Application) { _app = a }
    }
}
