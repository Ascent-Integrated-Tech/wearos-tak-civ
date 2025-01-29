package com.aitech.weartak_civ.service;

import static android.content.Context.POWER_SERVICE;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.aitech.weartak_civ.presentation.MainActivity;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class AlarmReceiver extends BroadcastReceiver {
    //----------------------------------
    // Wakelock Variables
    //----------------------------------
    private PowerManager mPowerManager = null;
    private PowerManager.WakeLock mWakeLock = null;
    private LocationListener mLocationListener = new MyLocationListener();
    private Context mContext;

    @Override
    public void onReceive(Context context, Intent intent) {
        mContext = context.getApplicationContext();
        Log.d("WearTAK-AlarmReceiver", "received context onReceive");


// START THE WAKE LOCK TO WAKE UP THE DEVICE
        mPowerManager = (PowerManager) context.getApplicationContext().getSystemService(POWER_SERVICE);

        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        mWakeLock = null;

        mWakeLock = mPowerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.PARTIAL_WAKE_LOCK, "id:wakeupscreen");

        mWakeLock.acquire();


// START GPS Code
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setPowerRequirement(Criteria.POWER_LOW);
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);

        LocationManager locationManager = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        String provider = locationManager.getBestProvider(criteria, true);
        if (ActivityCompat.checkSelfPermission(MainActivity.mainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(MainActivity.mainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        locationManager.requestLocationUpdates(provider, 0L, 0F, mLocationListener);




// AFTER 2000 milliseconds remove the wakelock and remove the location listener.  If you do not need to remove other processes you can shut down the wake lock after a certain interval using a parameter in the acquire method.
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                locationManager.removeUpdates(mLocationListener);

                if(mWakeLock != null) {
                    if(mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }
                mWakeLock = null;
            }
        }, 2000);

// RESTART THE TIMER (ALARM)

        LocationService.instance.startGlobalTimer();
    }


    // GPS Location listener
    private class MyLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(@NonNull Location location) {
            double latitude=location.getLatitude();
            double longitude=location.getLongitude();

            addDataToDB(String.valueOf(latitude), String.valueOf(longitude));
            Log.d("WearTAK-AlarmReceiver", "onLocationChanged: Lat: " + latitude + " Long: " + longitude);
        }

        @Override
        public void onLocationChanged(@NonNull List<Location> locations) {
            LocationListener.super.onLocationChanged(locations);
        }

        @Override
        public void onFlushComplete(int requestCode) {
            LocationListener.super.onFlushComplete(requestCode);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            LocationListener.super.onStatusChanged(provider, status, extras);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            LocationListener.super.onProviderEnabled(provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            LocationListener.super.onProviderDisabled(provider);
        }

        private void addDataToDB(String entryLatitude, String entryLongitude) {
            Long date=System.currentTimeMillis();
            SimpleDateFormat dateFormat =new SimpleDateFormat("MM-dd-yyyy HH:mm:ss", Locale.getDefault());
            String entryDate = dateFormat.format(date);

            BatteryManager batteryManager = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
            Integer batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            String sBatteryStatus = String.valueOf(batteryStatus);

            ContentValues values = new ContentValues();
            Log.d("WearTAK-AlarmReceiver", entryDate + ": (" + entryLatitude + ", " + entryLongitude + ")");

            /*
            // Create DB
            SQLiteDatabase db = mDBLocHelper.getWritableDatabase();

            // Log data to DB       values.put(LocationDatabaseContract.LocationInfoEntry.COLUMN_LOCATION_DATE, entryDate);
            values.put(LocationDatabaseContract.LocationInfoEntry.COLUMN_BATTERY_STATUS, sBatteryStatus);
            values.put(LocationDatabaseContract.LocationInfoEntry.COLUMN_LOCATION_LAT, entryLatitude);
            values.put(LocationDatabaseContract.LocationInfoEntry.COLUMN_LOCATION_LONG, entryLongitude);

            long newRowId = db.insert(LocationDatabaseContract.LocationInfoEntry.TABLE_NAME, null, values);

             */

        }
    }
}
