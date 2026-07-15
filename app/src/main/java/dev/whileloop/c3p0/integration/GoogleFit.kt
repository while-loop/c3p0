package dev.whileloop.c3p0.integration

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import timber.log.Timber

const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"

fun Context.googleFitIcon(): Drawable? =
    runCatching { packageManager.getApplicationIcon(GOOGLE_FIT_PACKAGE) }
        .onFailure { Timber.w(it, "Google Fit icon is unavailable") }
        .getOrNull()

fun Context.openGoogleFit(): Boolean {
    val launchIntent = packageManager
        .getLaunchIntentForPackage(GOOGLE_FIT_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launchIntent == null) {
        Timber.w("Google Fit is not installed or has no launch activity")
        return false
    }
    return runCatching {
        startActivity(launchIntent)
        true
    }.onFailure {
        Timber.e(it, "Unable to open Google Fit")
    }.getOrDefault(false)
}
