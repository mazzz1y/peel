package wtf.mazy.peel.util

import android.content.Context
import android.content.pm.PackageManager

fun Context.isAutomotiveHost(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
