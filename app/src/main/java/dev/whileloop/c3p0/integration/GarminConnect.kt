package dev.whileloop.c3p0.integration

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import timber.log.Timber

const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"

fun Context.garminConnectIcon(): Drawable? =
    runCatching { packageManager.getApplicationIcon(GARMIN_CONNECT_PACKAGE) }
        .onFailure { Timber.w(it, "Garmin Connect icon is unavailable") }
        .getOrNull()

fun Context.openGarminConnect(): Boolean {
    val launchIntent = packageManager
        .getLaunchIntentForPackage(GARMIN_CONNECT_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launchIntent == null) {
        Timber.w("Garmin Connect is not installed or has no launch activity")
        return false
    }
    return runCatching {
        startActivity(launchIntent)
        true
    }.onFailure {
        Timber.e(it, "Unable to open Garmin Connect")
    }.getOrDefault(false)
}
