package com.example.core.common

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import java.io.File

data class DeviceHardwareSnapshot(
  val totalRamBytes: Long,
  val availableRamBytes: Long,
  val isLowMemory: Boolean,
  val totalStorageBytes: Long,
  val availableStorageBytes: Long,
  val supportedAbis: List<String>,
  val androidSdkInt: Int,
  val androidRelease: String,
  val isOnline: Boolean,
  val javaVmVersion: String,
)

class DeviceInfoProvider(private val context: Context) {

  fun snapshot(storageDir: File = context.filesDir): DeviceHardwareSnapshot {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memInfo)

    val stat = StatFs(storageDir.absolutePath)
    val totalStorage = stat.totalBytes
    val availStorage = stat.availableBytes

    return DeviceHardwareSnapshot(
      totalRamBytes = memInfo.totalMem,
      availableRamBytes = memInfo.availMem,
      isLowMemory = memInfo.lowMemory,
      totalStorageBytes = totalStorage,
      availableStorageBytes = availStorage,
      supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
      androidSdkInt = Build.VERSION.SDK_INT,
      androidRelease = Build.VERSION.RELEASE ?: "unknown",
      isOnline = isNetworkConnected(),
      javaVmVersion = System.getProperty("java.vm.version") ?: System.getProperty("java.version") ?: "unknown",
    )
  }

  fun isNetworkConnected(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }
}
