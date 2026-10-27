package com.aitech.weartak_civ.service;

import android.content.ContentResolver;
import android.provider.Settings;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

@Implements(Settings.Global.class)
public class FailingGlobalSettings extends ShadowSettings.ShadowGlobal {
    public static boolean rejectWireless;

    @Implementation
    public static boolean putString(ContentResolver resolver, String name, String value) {
        if (rejectWireless && name.equals("adb_wifi_enabled")) {
            throw new SecurityException("Firmware denied write");
        }
        return ShadowSettings.ShadowGlobal.putString(resolver, name, value);
    }
}
