package com.aitech.weartak_civ.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.aitech.weartak_civ.R;

import java.util.concurrent.atomic.AtomicBoolean;

public class FallDetectionService extends Service implements SensorEventListener {

    public enum FallDetectionMethod {
        VOTE_METHOD,
        THRESHOLD_FLOW,
        QUATERNION,
        HYBRID
    }

    private FallDetectionMethod selectedMethod = FallDetectionMethod.HYBRID;

    private float[] prevGravity = new float[3];
    public static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private float[] currentGyro = new float[3];
    private float[] currentOrientation = new float[3];

    private SensorManager sensorManager;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning.set(true);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        Sensor accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_NORMAL);

        NotificationChannel channel = new NotificationChannel(
                "fall_channel_id",
                "Fall Detection",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, "fall_channel_id")
                .setContentTitle("Fall Detection Active")
                .setContentText("Monitoring movement for fall events...")
                .setSmallIcon(R.drawable.splash_icon)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        sensorManager.unregisterListener(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                float[] accel = event.values.clone();
                float[] newGravity = accel.clone();
                boolean fallDetected = false;

                switch (selectedMethod) {
                    case VOTE_METHOD:
                        fallDetected = detectFall5Vote(accel, currentGyro, currentOrientation);
                        break;
                    case THRESHOLD_FLOW:
                        fallDetected = detectFallThresholdFlow(accel, currentGyro, currentOrientation);
                        break;
                    case QUATERNION:
                        fallDetected = detectFallQuaternion(prevGravity, newGravity);
                        break;
                    case HYBRID:
                        fallDetected = detectFallHybrid(accel, currentGyro, currentOrientation, prevGravity, newGravity);
                        break;
                }

                if (fallDetected) {
                    Log.d("FallDetection", "FALL DETECTED via " + selectedMethod);
                }

                prevGravity = newGravity;
                break;

            case Sensor.TYPE_GYROSCOPE:
                currentGyro = event.values.clone();
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                // if you're using a Madgwick filter, update orientation here
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean detectFall5Vote(float[] accel, float[] gyro, float[] orientation) {
        double agvesr = Math.sqrt(Math.pow(accel[0] + gyro[0], 2) + Math.pow(accel[1] + gyro[1], 2) + Math.pow(accel[2] + gyro[2], 2));
        double alim = Math.sqrt(Math.pow(accel[0] - gyro[0], 2) + Math.pow(accel[1] - gyro[1], 2) + Math.pow(accel[2] - gyro[2], 2));
        double angleAlpha = Math.abs(Math.atan2(orientation[1], Math.sqrt(orientation[0]*orientation[0] + orientation[2]*orientation[2])));
        double gyroReDi = Math.sqrt(gyro[0]*gyro[0] + gyro[1]*gyro[1] + gyro[2]*gyro[2]);
        boolean agPeak = Math.sqrt(accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2]) > 20 && gyroReDi > 10;
        int votes = 0;
        if (agvesr > 25) votes++;
        if (alim > 15) votes++;
        if (angleAlpha > Math.toRadians(60)) votes++;
        if (gyroReDi > 10) votes++;
        if (agPeak) votes++;
        return votes >= 3;
    }

    private boolean detectFallThresholdFlow(float[] accel, float[] gyro, float[] orientation) {
        double accNorm = Math.sqrt(accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2]);
        boolean orientationChange = orientation[1] > Math.toRadians(60);
        return accNorm > 20 && orientationChange;
    }

    private boolean detectFallQuaternion(float[] prevG, float[] newG) {
        double dot = prevG[0]*newG[0] + prevG[1]*newG[1] + prevG[2]*newG[2];
        double magA = Math.sqrt(prevG[0]*prevG[0] + prevG[1]*prevG[1] + prevG[2]*prevG[2]);
        double magB = Math.sqrt(newG[0]*newG[0] + newG[1]*newG[1] + newG[2]*newG[2]);
        double angle = Math.acos(Math.min(1, Math.max(-1, dot / (magA * magB))));
        return Math.toDegrees(angle) > 60;
    }

    private boolean detectFallHybrid(float[] accel, float[] gyro, float[] orientation, float[] prevG, float[] newG) {
        double agvesr = Math.sqrt(Math.pow(accel[0] + gyro[0], 2) + Math.pow(accel[1] + gyro[1], 2) + Math.pow(accel[2] + gyro[2], 2));
        boolean orientationChanged = detectFallQuaternion(prevG, newG);
        boolean thresholdFall = detectFallThresholdFlow(accel, gyro, orientation);
        int votes = 0;
        if (agvesr > 25) votes++;
        if (orientationChanged) votes++;
        if (thresholdFall) votes++;
        return votes >= 2;
    }
}
