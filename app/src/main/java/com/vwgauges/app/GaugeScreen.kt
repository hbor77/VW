package com.vwgauges.app

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * The single screen shown inside Android Auto.
 *
 * The NavigationTemplate provides a Surface on which we draw the five gauges.
 * A minimal action strip is placed at the edge of the screen by the car host.
 */
class GaugeScreen(
    carContext: CarContext,
    private val obdManager: OBDManager
) : Screen(carContext) {

    private var renderer: GaugeRenderer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var renderJob: Job? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(holder: SurfaceContainer) {
            val surface = holder.surface ?: return
            renderer = GaugeRenderer(surface, holder.width, holder.height)
            startRendering()
        }

        override fun onSurfaceDestroyed(holder: SurfaceContainer) {
            renderJob?.cancel()
            renderer = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onStableAreaChanged(stableArea: Rect) {}
    }

    init {
        // Register surface callback with the car host
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        // Cancel the render scope when the screen is destroyed
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    private fun startRendering() {
        renderJob?.cancel()
        renderJob = scope.launch {
            obdManager.gaugeData.collectLatest { data ->
                renderer?.render(data)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Demo")
                    .setOnClickListener {
                        obdManager.startDemoMode()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
