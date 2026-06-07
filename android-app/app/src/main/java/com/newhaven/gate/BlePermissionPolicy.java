package com.newhaven.gate;

import android.Manifest;

final class BlePermissionPolicy {
    private static final int ANDROID_12_API = 31;

    private BlePermissionPolicy() {
    }

    static String[] requiredRuntimePermissions(int sdkInt) {
        if (sdkInt >= ANDROID_12_API) {
            return new String[] {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static String deniedPermissionMessage(int sdkInt) {
        if (sdkInt >= ANDROID_12_API) {
            return "Nearby devices permission is required to scan and connect.";
        }
        return "Location permission is required for BLE scanning on Android 11 and older.";
    }
}
