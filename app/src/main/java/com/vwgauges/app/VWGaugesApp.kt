package com.vwgauges.app

import android.app.Application

/**
 * Application class — holds the single [OBDManager] instance so it is shared
 * between [SettingsActivity] (phone UI) and [CarService] (Android Auto).
 */
class VWGaugesApp : Application() {
    val obdManager = OBDManager()
}
