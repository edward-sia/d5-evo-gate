package au.com.queenie.d5evogate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private sealed interface GattOperation {
        data class WriteCommand(val command: String) : GattOperation
        data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : GattOperation
    }

    private lateinit var connectionValue: TextView
    private lateinit var controllerValue: TextView
    private lateinit var authValue: TextView
    private lateinit var infoValue: TextView
    private lateinit var messageValue: TextView
    private lateinit var pinInput: EditText
    private lateinit var connectButton: Button
    private lateinit var authenticateButton: Button
    private lateinit var triggerButton: Button
    private lateinit var refreshButton: Button
    private lateinit var disconnectButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var controllerStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var infoCharacteristic: BluetoothGattCharacteristic? = null
    private var authStatusCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationQueue = ArrayDeque<GattOperation>()
    private var operationInFlight = false
    private var scanInProgress = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants.values.all { it }
            if (granted) {
                startScanAndConnect()
            } else {
                setMessage("Bluetooth permission was denied.")
            }
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            setConnection("Scan failed")
            setMessage("BLE scan failed with code $errorCode.")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    runOnUiThread {
                        setConnection("Discovering services")
                        setMessage("Connected to ${gatt.device.name ?: gatt.device.address}.")
                        updateButtonState()
                    }
                    gatt.discoverServices()
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    gatt.close()
                    bluetoothGatt = null
                    clearResolvedCharacteristics()
                    operationQueue.clear()
                    operationInFlight = false
                    runOnUiThread {
                        setConnection("Disconnected")
                        setMessage("Phone disconnected from the gate controller.")
                        resetStatusViews()
                        updateButtonState()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    setConnection("Service discovery failed")
                    setMessage("BLE service discovery failed with code $status.")
                }
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                runOnUiThread {
                    setConnection("Wrong device")
                    setMessage("Connected device does not expose the D5-EVO BLE service.")
                }
                gatt.disconnect()
                return
            }

            commandCharacteristic = service.getCharacteristic(COMMAND_CHAR_UUID)
            controllerStatusCharacteristic = service.getCharacteristic(CONTROLLER_STATUS_CHAR_UUID)
            infoCharacteristic = service.getCharacteristic(INFO_CHAR_UUID)
            authStatusCharacteristic = service.getCharacteristic(AUTH_STATUS_CHAR_UUID)

            runOnUiThread {
                setConnection("Connected")
                setMessage("Gate controller ready.")
                updateButtonState()
            }

            queueStatusRefresh()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            operationInFlight = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value?.toString(StandardCharsets.UTF_8).orEmpty()
                runOnUiThread { applyCharacteristicValue(characteristic.uuid, value) }
            } else {
                runOnUiThread {
                    setMessage("Read failed for ${characteristic.uuid} with code $status.")
                }
            }
            runNextOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            operationInFlight = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    setMessage("Sent ${characteristic.value?.toString(StandardCharsets.UTF_8).orEmpty()}.")
                }
                queueStatusRefresh()
            } else {
                runOnUiThread {
                    setMessage("Write failed with code $status.")
                }
            }
            runNextOperation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionValue = findViewById(R.id.connectionValue)
        controllerValue = findViewById(R.id.controllerValue)
        authValue = findViewById(R.id.authValue)
        infoValue = findViewById(R.id.infoValue)
        messageValue = findViewById(R.id.messageValue)
        pinInput = findViewById(R.id.pinInput)
        connectButton = findViewById(R.id.connectButton)
        authenticateButton = findViewById(R.id.authenticateButton)
        triggerButton = findViewById(R.id.triggerButton)
        refreshButton = findViewById(R.id.refreshButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        pinInput.setText(preferences.getString(PREF_PIN, ""))

        connectButton.setOnClickListener {
            if (hasAllPermissions()) {
                startScanAndConnect()
            } else {
                permissionLauncher.launch(requiredPermissions())
            }
        }

        authenticateButton.setOnClickListener {
            rememberPin()
            enqueueCommand(buildAuthCommand())
        }

        triggerButton.setOnClickListener {
            rememberPin()
            val pin = pinInput.text.toString().trim()
            if (pin.isNotEmpty()) {
                enqueueCommand(buildAuthCommand())
            }
            enqueueCommand("TRIGGER")
        }

        refreshButton.setOnClickListener { queueStatusRefresh() }
        disconnectButton.setOnClickListener { disconnectGatt() }

        resetStatusViews()
        setConnection("Idle")
        setMessage("Press Connect to find the gate controller.")
        updateButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        disconnectGatt()
    }

    private fun resetStatusViews() {
        controllerValue.text = getString(R.string.status_unknown)
        authValue.text = getString(R.string.status_unknown)
        infoValue.text = getString(R.string.status_unknown)
    }

    private fun clearResolvedCharacteristics() {
        commandCharacteristic = null
        controllerStatusCharacteristic = null
        infoCharacteristic = null
        authStatusCharacteristic = null
    }

    private fun rememberPin() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_PIN, pinInput.text.toString().trim())
            .apply()
    }

    private fun buildAuthCommand(): String {
        val pin = pinInput.text.toString().trim()
        return if (pin.isEmpty()) "AUTH" else "AUTH $pin"
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun setConnection(value: String) {
        connectionValue.text = value
    }

    private fun setMessage(value: String) {
        messageValue.text = value
    }

    private fun updateButtonState() {
        val connected = bluetoothGatt != null && commandCharacteristic != null
        connectButton.isEnabled = !scanInProgress && !connected
        authenticateButton.isEnabled = connected
        triggerButton.isEnabled = connected
        refreshButton.isEnabled = connected
        disconnectButton.isEnabled = scanInProgress || connected
    }

    @SuppressLint("MissingPermission")
    private fun startScanAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setMessage("This phone does not expose a Bluetooth adapter.")
            return
        }

        if (!adapter.isEnabled) {
            setMessage("Enable Bluetooth on the phone first.")
            return
        }

        if (scanInProgress) {
            setMessage("Already scanning for the gate controller.")
            return
        }

        bluetoothScanner = adapter.bluetoothLeScanner
        if (bluetoothScanner == null) {
            setMessage("BLE scanning is not available on this phone.")
            return
        }

        disconnectGatt()
        clearResolvedCharacteristics()
        operationQueue.clear()
        operationInFlight = false

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothScanner?.startScan(filters, settings, scanCallback)
        scanInProgress = true
        setConnection("Scanning")
        setMessage("Looking for D5-EVO-Gate over BLE.")
        updateButtonState()

        mainHandler.postDelayed({
            if (scanInProgress) {
                stopScan()
                setConnection("Not found")
                setMessage("No matching gate controller was found nearby.")
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanInProgress) {
            return
        }

        bluetoothScanner?.stopScan(scanCallback)
        scanInProgress = false
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        setConnection("Connecting")
        setMessage("Connecting to ${device.name ?: device.address}.")
        bluetoothGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        clearResolvedCharacteristics()
        operationQueue.clear()
        operationInFlight = false
        updateButtonState()
    }

    private fun queueStatusRefresh() {
        authStatusCharacteristic?.let { operationQueue.add(GattOperation.ReadCharacteristic(it)) }
        controllerStatusCharacteristic?.let {
            operationQueue.add(GattOperation.ReadCharacteristic(it))
        }
        infoCharacteristic?.let { operationQueue.add(GattOperation.ReadCharacteristic(it)) }
        runNextOperation()
    }

    private fun enqueueCommand(command: String) {
        if (commandCharacteristic == null) {
            setMessage("Connect to the gate controller first.")
            return
        }
        operationQueue.add(GattOperation.WriteCommand(command))
        runNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun runNextOperation() {
        if (operationInFlight) {
            return
        }

        val gatt = bluetoothGatt ?: return
        val operation = operationQueue.removeFirstOrNull() ?: return

        when (operation) {
            is GattOperation.WriteCommand -> {
                val characteristic = commandCharacteristic
                if (characteristic == null) {
                    setMessage("Command characteristic is unavailable.")
                    return
                }
                characteristic.value = operation.command.toByteArray(StandardCharsets.UTF_8)
                operationInFlight = true
                if (!gatt.writeCharacteristic(characteristic)) {
                    operationInFlight = false
                    setMessage("Failed to start command write.")
                    runNextOperation()
                }
            }

            is GattOperation.ReadCharacteristic -> {
                operationInFlight = true
                if (!gatt.readCharacteristic(operation.characteristic)) {
                    operationInFlight = false
                    setMessage("Failed to start status read.")
                    runNextOperation()
                }
            }
        }
    }

    private fun applyCharacteristicValue(uuid: UUID, value: String) {
        when (uuid) {
            CONTROLLER_STATUS_CHAR_UUID -> controllerValue.text = value
            AUTH_STATUS_CHAR_UUID -> authValue.text = value
            INFO_CHAR_UUID -> infoValue.text = value
        }
    }

    companion object {
        private const val PREFS_NAME = "d5_evo_ble_gate"
        private const val PREF_PIN = "auth_pin"
        private const val SCAN_TIMEOUT_MS = 10_000L

        private val SERVICE_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1")
        private val COMMAND_CHAR_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2")
        private val CONTROLLER_STATUS_CHAR_UUID =
            UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3")
        private val INFO_CHAR_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5")
        private val AUTH_STATUS_CHAR_UUID =
            UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6")
    }
}
