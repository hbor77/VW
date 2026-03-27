package com.vwgauges.app

import android.content.Intent
import androidx.car.app.Session

/**
 * Car App session. Creates the [GaugeScreen] and passes the shared [OBDManager].
 * Auto-connects to the last saved Bluetooth device when Android Auto launches.
 */
class MainSession(private val obdManager: OBDManager) : Session() {

    override fun onCreateScreen(intent: Intent): GaugeScreen {
        return GaugeScreen(carContext, obdManager)
    }
}
