package com.newhaven.gate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BlePermissionPolicyTest {
    @Test
    public void android12AndNewerUseNearbyDevicePermissions() {
        assertArrayEquals(
                new String[] {
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT"
                },
                BlePermissionPolicy.requiredRuntimePermissions(31));
        assertArrayEquals(
                new String[] {
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT"
                },
                BlePermissionPolicy.requiredRuntimePermissions(34));
    }

    @Test
    public void android11AndOlderUseFineLocationForBleScanning() {
        assertArrayEquals(
                new String[] {"android.permission.ACCESS_FINE_LOCATION"},
                BlePermissionPolicy.requiredRuntimePermissions(26));
        assertArrayEquals(
                new String[] {"android.permission.ACCESS_FINE_LOCATION"},
                BlePermissionPolicy.requiredRuntimePermissions(30));
    }

    @Test
    public void deniedPermissionMessageMatchesPlatformRequirement() {
        assertEquals(
                "Nearby devices permission is required to scan and connect.",
                BlePermissionPolicy.deniedPermissionMessage(31));
        assertEquals(
                "Location permission is required for BLE scanning on Android 11 and older.",
                BlePermissionPolicy.deniedPermissionMessage(30));
    }
}
