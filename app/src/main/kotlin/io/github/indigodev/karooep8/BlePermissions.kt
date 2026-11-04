package io.github.indigodev.karooep8

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BlePermissions {

    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.toTypedArray()
    }

    fun hasScanPermissions(context: Context): Boolean {
        val hasLocation = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocation) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    fun hasConnectPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
