package com.vwgauges.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phone-side settings screen.
 *
 * - Lists paired Bluetooth devices so the user can select their ELM327 adapter.
 * - Shows current OBD connection status.
 * - Provides a Demo Mode button for testing without an OBD adapter.
 *
 * The selected device address is persisted in SharedPreferences and picked up
 * automatically by [CarService] when Android Auto connects.
 */
class SettingsActivity : AppCompatActivity() {

    private val obdManager get() = (application as VWGaugesApp).obdManager
    private val prefs      by lazy { getSharedPreferences("vw_gauges", Context.MODE_PRIVATE) }

    private lateinit var tvStatus:    TextView
    private lateinit var tvSavedDev:  TextView
    private lateinit var rvDevices:   RecyclerView
    private lateinit var btnRefresh:  Button
    private lateinit var btnDemo:     Button
    private lateinit var btnDisconnect: Button
    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        setupRecyclerView()
        observeStatus()
        checkPermissionsAndLoad()
    }

    private fun bindViews() {
        tvStatus      = findViewById(R.id.tvStatus)
        tvSavedDev    = findViewById(R.id.tvSavedDevice)
        rvDevices     = findViewById(R.id.rvDevices)
        btnRefresh    = findViewById(R.id.btnRefresh)
        btnDemo       = findViewById(R.id.btnDemo)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        btnRefresh.setOnClickListener    { checkPermissionsAndLoad() }
        btnDemo.setOnClickListener       { obdManager.startDemoMode(); toast("Demo mode started") }
        btnDisconnect.setOnClickListener { obdManager.disconnect();    toast("Disconnected") }

        val saved = prefs.getString("bt_device_address", null)
        tvSavedDev.text = if (saved != null) "Last device: $saved" else "No device saved yet"
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            prefs.edit().putString("bt_device_address", device.address).apply()
            tvSavedDev.text = "Saved: ${device.address}"
            lifecycleScope.launch { obdManager.connect(device.address) }
        }
        rvDevices.adapter         = deviceAdapter
        rvDevices.layoutManager   = LinearLayoutManager(this)
        rvDevices.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            obdManager.status.collectLatest { status ->
                tvStatus.text = "Status: ${status.name}"
                tvStatus.setTextColor(when (status) {
                    OBDManager.Status.CONNECTED   -> 0xFF00DD77.toInt()
                    OBDManager.Status.CONNECTING  -> 0xFFFFAA00.toInt()
                    OBDManager.Status.ERROR       -> 0xFFFF3344.toInt()
                    OBDManager.Status.DISCONNECTED -> 0xFF8899BB.toInt()
                })
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_BT)
                return
            }
        }
        loadPairedDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_BT) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadPairedDevices()
            } else {
                toast("Bluetooth permission required")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            toast("Please enable Bluetooth")
            return
        }
        val paired = adapter.bondedDevices.toList()
        deviceAdapter.setDevices(paired)
        if (paired.isEmpty()) toast("No paired Bluetooth devices found. Pair your ELM327 adapter first.")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val RC_BT = 100
    }
}

// ── Device list adapter ───────────────────────────────────────────────────────

class DeviceAdapter(
    private val onSelect: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<BluetoothDevice>()

    fun setDevices(list: List<BluetoothDevice>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = items[position]
        holder.text1.text = device.name ?: "Unknown"
        holder.text2.text = device.address
        holder.text1.setTextColor(0xFFFFFFFF.toInt())
        holder.text2.setTextColor(0xFF8899BB.toInt())
        holder.itemView.setBackgroundColor(0xFF111122.toInt())
        holder.itemView.setOnClickListener { onSelect(device) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
        val text2: TextView = view.findViewById(android.R.id.text2)
    }
}
