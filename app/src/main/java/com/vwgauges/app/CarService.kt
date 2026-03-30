package com.vwgauges.app

import android.content.Context
import androidx.car.app.CarAppService
import androidx.car.app.validation.HostValidator
import androidx.car.app.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Entry point for the Android Auto integration.
 *
 * When the user connects their phone to a car with Android Auto this service
 * is bound by the car host. It shares the [OBDManager] from [VWGaugesApp] so
 * that a connection started from the phone settings activity is reused here.
 *
 * If no connection is active yet, the service tries to reconnect automatically
 * using the last Bluetooth address saved in SharedPreferences.
 */
class CarService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        // Allow all hosts during development.
        // For production release, replace with a hash-based validator.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        val app = application as VWGaugesApp
        autoConnectIfNeeded(app)
        return MainSession(app.obdManager)
    }

    private fun autoConnectIfNeeded(app: VWGaugesApp) {
        if (app.obdManager.status.value == OBDManager.Status.CONNECTED) return

        val prefs   = getSharedPreferences("vw_gauges", Context.MODE_PRIVATE)
        val address = prefs.getString("bt_device_address", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            app.obdManager.connect(address)
        }
    }
}
