package com.newhaven.gate

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
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private sealed interface GattOperation {
        data class WriteCommand(val command: String) : GattOperation
        data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : GattOperation
    }

    private lateinit var connectionValue: TextView
    private lateinit var controllerValue: TextView
    private lateinit var authValue: TextView
    private lateinit var primaryTitleValue: TextView
    private lateinit var infoValue: TextView
    private lateinit var messageValue: TextView
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var disconnectButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var controllerStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var authChallengeCharacteristic: BluetoothGattCharacteristic? = null
    private var infoCharacteristic: BluetoothGattCharacteristic? = null
    private var authStatusCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationQueue = ArrayDeque<GattOperation>()
    private var operationInFlight = false
    private var scanInProgress = false
    private var connectionState = "Idle"
    private var controllerStatus = "unknown"
    private var authStatus = "unknown"
    private var deviceInfo = "unknown"
    private var authChallenge = ""
    private var pendingAuthPin: String? = null
    private var pendingTriggerAfterAuth = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val deniedPermissions = missingPermissionsAfterGrant(grants)
            if (deniedPermissions.isEmpty()) {
                startScanAndConnect()
            } else {
                setConnection("Permission needed")
                setMessage(BlePermissionPolicy.deniedPermissionMessage(Build.VERSION.SDK_INT))
                updateButtonState()
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    if (!hasConnectPermission()) {
                        runOnUiThread {
                            handleBlePermissionMissing()
                        }
                        return
                    }
                    val deviceLabel = deviceLabel(gatt.device)
                    runOnUiThread {
                        setConnection("Discovering services")
                        setMessage("Connected to $deviceLabel.")
                        updateButtonState()
                    }
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        runOnUiThread {
                            handleBlePermissionMissing()
                        }
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    closeGatt(gatt)
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

            if (!hasConnectPermission()) {
                handleBlePermissionMissing()
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                runOnUiThread {
                    setConnection("Wrong device")
                    setMessage("Connected device does not expose the D5-EVO BLE service.")
                }
                disconnectGatt(gatt)
                return
            }

            commandCharacteristic = service.getCharacteristic(COMMAND_CHAR_UUID)
            controllerStatusCharacteristic = service.getCharacteristic(CONTROLLER_STATUS_CHAR_UUID)
            authChallengeCharacteristic = service.getCharacteristic(AUTH_CHALLENGE_CHAR_UUID)
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
                runOnUiThread {
                    applyCharacteristicValue(characteristic.uuid, value)

                    if (characteristic.uuid == AUTH_CHALLENGE_CHAR_UUID) {
                        handleAuthChallengeValue(value)
                    } else if (characteristic.uuid == AUTH_STATUS_CHAR_UUID) {
                        handleAuthStatusValue(value)
                    }
                }
            } else {
                runOnUiThread {
                    clearPendingAuthRequest()
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
                    setMessage("Command sent.")
                }
                queueStatusRefresh()
            } else {
                runOnUiThread {
                    clearPendingAuthRequest()
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
        primaryTitleValue = findViewById(R.id.primaryTitleValue)
        infoValue = findViewById(R.id.infoValue)
        messageValue = findViewById(R.id.messageValue)
        connectButton = findViewById(R.id.connectButton)
        refreshButton = findViewById(R.id.refreshButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        connectButton.setOnClickListener {
            handlePrimaryAction()
        }

        refreshButton.setOnClickListener { queueStatusRefresh() }
        disconnectButton.setOnClickListener { disconnectGatt() }

        resetStatusViews()
        setConnection("Idle")
        setMessage("Tap Connect when you're ready.")
        updateButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        disconnectGatt()
    }

    override fun onStop() {
        super.onStop()
        disconnectGatt()
    }

    private fun triggerGate() {
        if (authStatus == "disabled" || authStatus == "authorized") {
            enqueueCommand("PED")
            return
        }

        if (BuildConfig.GATE_AUTH_PIN.isBlank()) {
            setMessage("This build does not include a local unlock phrase.")
            return
        }

        requestAuthentication(pin = BuildConfig.GATE_AUTH_PIN, triggerAfterAuth = true)
    }

    private fun requestAuthentication(pin: String, triggerAfterAuth: Boolean) {
        pendingAuthPin = pin
        pendingTriggerAfterAuth = triggerAfterAuth
        requestAuthChallengeRead()
    }

    private fun requestAuthChallengeRead() {
        val challengeCharacteristic = authChallengeCharacteristic
        if (challengeCharacteristic == null) {
            clearPendingAuthRequest()
            setMessage("Auth challenge characteristic is unavailable.")
            return
        }

        operationQueue.add(GattOperation.ReadCharacteristic(challengeCharacteristic))
        runNextOperation()
    }

    private fun handleAuthChallengeValue(value: String) {
        val pin = pendingAuthPin ?: return

        if (value == "disabled") {
            clearPendingAuthRequest()
            setMessage("Authentication is disabled in the firmware.")
            return
        }

        val authCommand = buildAuthResponseCommand(pin, value)
        if (authCommand == null) {
            clearPendingAuthRequest()
            setMessage("Auth challenge was invalid. Refresh and try again.")
            return
        }

        pendingAuthPin = null
        enqueueCommand(authCommand)
        setMessage("Signing auth challenge locally.")
    }

    private fun handleAuthStatusValue(value: String) {
        when (value) {
            "authorized" -> {
                setMessage("Authentication accepted.")
                if (pendingTriggerAfterAuth) {
                    pendingTriggerAfterAuth = false
                    enqueueCommand("PED")
                }
            }

            "denied" -> {
                clearPendingAuthRequest()
                setMessage("Authentication failed. Wait a moment, then try again.")
            }

            "required" -> {
                if (!pendingTriggerAfterAuth) {
                    setMessage("Authentication is required before triggering.")
                }
            }

            "disabled" -> clearPendingAuthRequest()
        }
    }

    private fun buildAuthResponseCommand(pin: String, challenge: String): String? {
        val normalizedChallenge = challenge.trim().uppercase(Locale.US)
        if (normalizedChallenge.length != 32) {
            return null
        }

        val pinBytes = pin.toByteArray(StandardCharsets.UTF_8)
        val challengeBytes = normalizedChallenge.toByteArray(StandardCharsets.UTF_8)
        var digest = sha256(AUTH_LABEL + pinBytes + challengeBytes)

        repeat(AUTH_HASH_ROUNDS - 1) {
            digest = sha256(digest + pinBytes + challengeBytes)
        }

        val response = digest.joinToString(separator = "") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
        return "AUTHRESP $response"
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun clearPendingAuthRequest() {
        pendingAuthPin = null
        pendingTriggerAfterAuth = false
    }

    private fun resetStatusViews() {
        controllerStatus = "unknown"
        authStatus = "unknown"
        deviceInfo = "unknown"
        applyStatusPresentation(connectionValue, statusPresentationForConnection(connectionState))
        applyStatusPresentation(controllerValue, statusPresentationForController(controllerStatus))
        applyStatusPresentation(authValue, statusPresentationForAuth(authStatus))
        updatePrimaryCard()
    }

    private fun clearResolvedCharacteristics() {
        commandCharacteristic = null
        controllerStatusCharacteristic = null
        authChallengeCharacteristic = null
        infoCharacteristic = null
        authStatusCharacteristic = null
        authChallenge = ""
        clearPendingAuthRequest()
    }

    private fun requiredPermissions(): Array<String> =
        BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            hasPermission(it)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): Array<String> =
        requiredPermissions().filterNot(::hasPermission).toTypedArray()

    private fun missingPermissionsAfterGrant(grants: Map<String, Boolean>): Array<String> =
        requiredPermissions()
            .filter { permission -> grants[permission] != true && !hasPermission(permission) }
            .toTypedArray()

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun requestMissingPermissions() {
        val permissions = missingPermissions()
        if (permissions.isEmpty()) {
            return
        }
        permissionLauncher.launch(permissions)
    }

    private fun handleBlePermissionMissing() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                handleBlePermissionMissing()
            }
            return
        }

        scanInProgress = false
        bluetoothScanner = null
        bluetoothGatt = null
        clearResolvedCharacteristics()
        operationQueue.clear()
        operationInFlight = false
        setConnection("Permission needed")
        setMessage(BlePermissionPolicy.deniedPermissionMessage(Build.VERSION.SDK_INT))
        updateButtonState()
    }

    private fun setConnection(value: String) {
        connectionState = value
        applyStatusPresentation(connectionValue, statusPresentationForConnection(value))
        updatePrimaryCard()
        updateButtonState()
    }

    private fun setMessage(value: String) {
        messageValue.text = value
        messageValue.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateButtonState() {
        val connected = isConnected()
        val hasConnectPermission = hasConnectPermission()
        val canStartScan = !scanInProgress && !connected
        connectButton.isEnabled =
            if (connected) {
                hasConnectPermission && controllerStatus.lowercase(Locale.US) != "pulsing"
            } else {
                canStartScan
            }
        connectButton.text =
            if (connected) {
                getString(R.string.action_trigger)
            } else {
                getString(R.string.action_connect)
            }
        connectButton.backgroundTintList =
            ColorStateList.valueOf(
                ContextCompat.getColor(this, primaryActionTintRes()),
            )

        refreshButton.visibility = if (connected) View.VISIBLE else View.GONE
        refreshButton.isEnabled = connected && hasConnectPermission

        val canStop = scanInProgress || connected
        disconnectButton.visibility = if (canStop) View.VISIBLE else View.GONE
        disconnectButton.isEnabled = canStop
        disconnectButton.text =
            if (scanInProgress) {
                getString(R.string.action_cancel)
            } else {
                getString(R.string.action_disconnect)
            }

        updatePrimaryCard()
    }

    private fun handlePrimaryAction() {
        if (isConnected()) {
            triggerGate()
        } else if (hasAllPermissions()) {
            startScanAndConnect()
        } else {
            requestMissingPermissions()
        }
    }

    private fun updatePrimaryCard() {
        primaryTitleValue.text = primaryTitle()
        infoValue.text = primaryDetail()
    }

    private fun primaryTitle(): String =
        if (!isConnected()) {
            when (connectionState.lowercase(Locale.US)) {
                "scanning" -> "Looking nearby"
                "connecting" -> "Connecting"
                "discovering services" -> "Finishing setup"
                "permission needed" -> "Permission needed"
                "not found" -> "Not found"
                else -> "Pedestrian access"
            }
        } else {
            when (controllerStatus.lowercase(Locale.US)) {
                "pulsing" -> "Opening partway"
                "cooldown" -> "Please wait"
                "locked", "bad-command" -> "Check the lock"
                else -> "Ready"
            }
        }

    private fun primaryDetail(): String {
        if (!isConnected()) {
            return when (connectionState.lowercase(Locale.US)) {
                "scanning" -> "Searching over Bluetooth."
                "connecting" -> "Joining your gate controller."
                "discovering services" -> "Almost ready."
                "permission needed" -> BlePermissionPolicy.deniedPermissionMessage(Build.VERSION.SDK_INT)
                "not found" -> "Move closer and try again."
                else -> "Nearby Bluetooth control for your gate."
            }
        }

        return when {
            deviceInfo.isNotBlank() && deviceInfo.lowercase(Locale.US) != "unknown" -> deviceInfo
            authStatus == "required" -> "Unlock is required before triggering."
            authStatus == "authorized" -> "Ready to open for a pedestrian."
            else -> "Connected and ready when you are."
        }
    }

    private fun primaryActionTintRes(): Int =
        if (!isConnected()) {
            R.color.statusBlue
        } else {
            when (controllerStatus.lowercase(Locale.US)) {
                "ready" -> R.color.statusGreen
                "cooldown" -> R.color.statusOrange
                "locked", "bad-command" -> R.color.statusRed
                else -> R.color.statusBlue
            }
        }

    private fun isConnected(): Boolean = bluetoothGatt != null && commandCharacteristic != null

    private fun applyStatusPresentation(view: TextView, presentation: StatusPresentation) {
        view.text = presentation.title
        view.setTextColor(ContextCompat.getColor(this, presentation.colorRes))
    }

    private fun statusPresentationForConnection(value: String): StatusPresentation =
        when (value.lowercase(Locale.US)) {
            "connected" -> StatusPresentation("On", R.color.statusGreen)
            "scanning" -> StatusPresentation("Scan", R.color.statusBlue)
            "connecting", "discovering services" -> StatusPresentation("Join", R.color.statusBlue)
            "permission needed" -> StatusPresentation("Perm", R.color.statusOrange)
            "not found" -> StatusPresentation("Miss", R.color.statusOrange)
            "scan failed", "connect failed", "service discovery failed", "wrong device" ->
                StatusPresentation("Error", R.color.statusRed)

            else -> StatusPresentation("Off", R.color.statusMuted)
        }

    private fun statusPresentationForController(value: String): StatusPresentation =
        when (value.lowercase(Locale.US)) {
            "ready" -> StatusPresentation("Ready", R.color.statusGreen)
            "pulsing" -> StatusPresentation("Open", R.color.statusBlue)
            "cooldown" -> StatusPresentation("Wait", R.color.statusOrange)
            "locked" -> StatusPresentation("Lock", R.color.statusRed)
            "bad-command" -> StatusPresentation("Error", R.color.statusRed)
            else -> StatusPresentation("Idle", R.color.statusMuted)
        }

    private fun statusPresentationForAuth(value: String): StatusPresentation =
        when (value.lowercase(Locale.US)) {
            "authorized" -> StatusPresentation("Open", R.color.statusGreen)
            "required" -> StatusPresentation("Need", R.color.statusOrange)
            "denied" -> StatusPresentation("No", R.color.statusRed)
            "disabled" -> StatusPresentation("Off", R.color.statusMuted)
            else -> StatusPresentation("Idle", R.color.statusMuted)
        }

    @SuppressLint("MissingPermission")
    private fun startScanAndConnect() {
        if (!hasAllPermissions()) {
            requestMissingPermissions()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            setMessage("This phone does not expose a Bluetooth adapter.")
            return
        }

        val bluetoothEnabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
            return
        }

        if (!bluetoothEnabled) {
            setMessage("Enable Bluetooth on the phone first.")
            return
        }

        if (scanInProgress) {
            setMessage("Already scanning for the gate controller.")
            return
        }

        bluetoothScanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
            return
        }
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

        try {
            bluetoothScanner?.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
            return
        }
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

        if (hasScanPermission()) {
            try {
                bluetoothScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Permission can be revoked while a scan is active; local state still needs cleanup.
            }
        }
        bluetoothScanner = null
        scanInProgress = false
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            handleBlePermissionMissing()
            requestMissingPermissions()
            return
        }

        setConnection("Connecting")
        setMessage("Connecting to ${deviceLabel(device)}.")
        bluetoothGatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
            null
        }
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        stopScan()
        disconnectGatt(bluetoothGatt)
        bluetoothGatt = null
        clearResolvedCharacteristics()
        operationQueue.clear()
        operationInFlight = false
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(gatt: BluetoothGatt?) {
        if (gatt == null || !hasConnectPermission()) {
            return
        }

        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
            return
        }

        closeGatt(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null || !hasConnectPermission()) {
            return
        }

        try {
            gatt.close()
        } catch (_: SecurityException) {
            handleBlePermissionMissing()
        }
    }

    private fun queueStatusRefresh() {
        if (!hasConnectPermission()) {
            handleBlePermissionMissing()
            return
        }

        authStatusCharacteristic?.let { operationQueue.add(GattOperation.ReadCharacteristic(it)) }
        authChallengeCharacteristic?.let { operationQueue.add(GattOperation.ReadCharacteristic(it)) }
        controllerStatusCharacteristic?.let {
            operationQueue.add(GattOperation.ReadCharacteristic(it))
        }
        infoCharacteristic?.let { operationQueue.add(GattOperation.ReadCharacteristic(it)) }
        runNextOperation()
    }

    private fun enqueueCommand(command: String) {
        if (!hasConnectPermission()) {
            handleBlePermissionMissing()
            return
        }

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

        if (!hasConnectPermission()) {
            clearPendingAuthRequest()
            operationQueue.clear()
            handleBlePermissionMissing()
            return
        }

        val gatt = bluetoothGatt ?: return
        val operation = operationQueue.pollFirst() ?: return

        when (operation) {
            is GattOperation.WriteCommand -> {
                val characteristic = commandCharacteristic
                if (characteristic == null) {
                    clearPendingAuthRequest()
                    setMessage("Command characteristic is unavailable.")
                    return
                }
                characteristic.value = operation.command.toByteArray(StandardCharsets.UTF_8)
                operationInFlight = true
                val started = try {
                    gatt.writeCharacteristic(characteristic)
                } catch (_: SecurityException) {
                    operationInFlight = false
                    clearPendingAuthRequest()
                    handleBlePermissionMissing()
                    return
                }
                if (!started) {
                    operationInFlight = false
                    clearPendingAuthRequest()
                    setMessage("Failed to start command write.")
                    runNextOperation()
                }
            }

            is GattOperation.ReadCharacteristic -> {
                operationInFlight = true
                val started = try {
                    gatt.readCharacteristic(operation.characteristic)
                } catch (_: SecurityException) {
                    operationInFlight = false
                    clearPendingAuthRequest()
                    handleBlePermissionMissing()
                    return
                }
                if (!started) {
                    operationInFlight = false
                    clearPendingAuthRequest()
                    setMessage("Failed to start status read.")
                    runNextOperation()
                }
            }
        }
    }

    private fun applyCharacteristicValue(uuid: UUID, value: String) {
        when (uuid) {
            CONTROLLER_STATUS_CHAR_UUID -> {
                controllerStatus = value
                applyStatusPresentation(controllerValue, statusPresentationForController(value))
            }

            AUTH_STATUS_CHAR_UUID -> {
                authStatus = value
                applyStatusPresentation(authValue, statusPresentationForAuth(value))
            }

            AUTH_CHALLENGE_CHAR_UUID -> authChallenge = value
            INFO_CHAR_UUID -> deviceInfo = value
        }

        updatePrimaryCard()
        updateButtonState()
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String =
        if (hasConnectPermission()) {
            try {
                device.name ?: device.address
            } catch (_: SecurityException) {
                "gate controller"
            }
        } else {
            "gate controller"
        }

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val AUTH_HASH_ROUNDS = 2048

        private val AUTH_LABEL = "D5-EVO-AUTH-V1|".toByteArray(StandardCharsets.UTF_8)

        private val SERVICE_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1")
        private val COMMAND_CHAR_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2")
        private val CONTROLLER_STATUS_CHAR_UUID =
            UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3")
        private val AUTH_CHALLENGE_CHAR_UUID =
            UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc4")
        private val INFO_CHAR_UUID = UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5")
        private val AUTH_STATUS_CHAR_UUID =
            UUID.fromString("4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6")
    }
}

private data class StatusPresentation(
    val title: String,
    val colorRes: Int,
)
