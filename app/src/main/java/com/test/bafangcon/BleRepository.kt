package com.test.bafangcon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.test.bafangcon.canble.crypto.NativeCryptoProvider
import com.test.bafangcon.canble.handshake.CanBleHandshake
import com.test.bafangcon.canble.handshake.CoroutineHandshakeTimer
import com.test.bafangcon.canble.handshake.HandshakeRetryConfig
import com.test.bafangcon.canble.transport.CanBleTransport
import com.test.bafangcon.canble.transport.TransportDetector
import com.test.bafangcon.utils.AESUtils
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList // Import LinkedList for Queue
import java.util.Locale
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean // Import AtomicBoolean


class BleRepository(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null // NUS RX Char
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null // NUS TX Char

    // --- CAN BLE Transport ---
    private var canControlCharacteristic: BluetoothGattCharacteristic? = null
    private var canRxCharacteristic: BluetoothGattCharacteristic? = null
    private var canTxCharacteristic: BluetoothGattCharacteristic? = null
    private var canBleTransport: CanBleTransport? = null
    private var canControlSubscribed = false
    private var canRxSubscribed = false

    // --- Command Queue ---
    private val commandQueue: Queue<ByteArray> = LinkedList()
    private val isWriting = AtomicBoolean(false) // Flag to indicate ongoing write

    // --- StateFlows for Parsed Data ---
    private val _controllerInfo = MutableStateFlow<ControllerInfo?>(null)
    val controllerInfo: StateFlow<ControllerInfo?> = _controllerInfo.asStateFlow()

    private val _meterInfo = MutableStateFlow<MeterInfo?>(null)
    val meterInfo: StateFlow<MeterInfo?> = _meterInfo.asStateFlow()

    // Add StateFlow for PersonalizedInfo
    private val _personalizedInfo = MutableStateFlow<PersonalizedInfo?>(null)
    val personalizedInfo: StateFlow<PersonalizedInfo?> = _personalizedInfo.asStateFlow()

    private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
    val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo.asStateFlow()

    private val _sensorInfo = MutableStateFlow<SensorInfo?>(null)
    val sensorInfo: StateFlow<SensorInfo?> = _sensorInfo.asStateFlow()

    private val _iotConfigInfo = MutableStateFlow<IotConfigInfo?>(null)
    val iotConfigInfo: StateFlow<IotConfigInfo?> = _iotConfigInfo.asStateFlow()

    private val _iotCanInfo = MutableStateFlow<IotCanInfo?>(null)
    val iotCanInfo: StateFlow<IotCanInfo?> = _iotCanInfo.asStateFlow()
    // --------------------------------------
    // --- Auth State ---
    private val _authState = MutableStateFlow(BleAuthState.NOT_AUTHENTICATED)
    val authState: StateFlow<BleAuthState> = _authState.asStateFlow()
    // --------------------------------------
    // --- General BLE State ---
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<Set<DiscoveredBluetoothDevice>>(emptySet())
    val scanResults: StateFlow<Set<DiscoveredBluetoothDevice>> = _scanResults.asStateFlow()

    val bleLogs = MutableStateFlow<List<String>>(emptyList())
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    private val _rideLogState = MutableStateFlow(RideLogState())
    val rideLogState: StateFlow<RideLogState> = _rideLogState.asStateFlow()

    // --- CAN BLE Service Detection ---
    private val _canBleServiceFound = MutableStateFlow<Boolean?>(null)
    val canBleServiceFound: StateFlow<Boolean?> = _canBleServiceFound.asStateFlow()

    // --- BLE Services Debug ---
    private val _bleServicesDebug = MutableStateFlow<List<BleServiceDebugEntry>>(emptyList())
    val bleServicesDebug: StateFlow<List<BleServiceDebugEntry>> = _bleServicesDebug.asStateFlow()

    // --- AA55 Raw Logger ---
    private val aa55RawLogger = Aa55RawLogger()
    private val _aa55RawStats = MutableStateFlow(Aa55RawStats())
    val aa55RawStats: StateFlow<Aa55RawStats> = _aa55RawStats.asStateFlow()

    // --- BLE Raw Notification Logger ---
    private val bleRawNotificationLogger = BleRawNotificationLogger()
    private val _bleRawNotificationStats = MutableStateFlow(BleRawNotificationStats())
    val bleRawNotificationStats: StateFlow<BleRawNotificationStats> = _bleRawNotificationStats.asStateFlow()

    fun addLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        bleLogs.value = bleLogs.value + "[$timestamp] $msg"
    }

    fun clearBleLogs() { bleLogs.value = emptyList() }
    fun clearPersonalizedInfo() { _personalizedInfo.value = null }
    fun setPersonalizedInfo(info: PersonalizedInfo) { _personalizedInfo.value = info }

    fun startRideLogging(destinationTreeUri: Uri) {
        if (_rideLogState.value.isLogging) return
        if (_authState.value != BleAuthState.AUTHENTICATED) {
            addLog("Ride logging requires authenticated connection.")
            return
        }

        val startedAt = System.currentTimeMillis()
        try {
            val filename = "ride-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))}.csv"
            val documentUri = createRideLogDocument(destinationTreeUri, filename)
            val stream = context.contentResolver.openOutputStream(documentUri, "w")
                ?: throw IOException("Cannot open $filename for writing")
            val writer = BufferedWriter(OutputStreamWriter(stream))
            writer.write(RideTelemetrySample.csvHeader())
            writer.newLine()
            writer.flush()

            synchronized(rideLogLock) {
                rideLogWriter = writer
                rideLogStartedAtMs = startedAt
                lastRideTelemetryLogMs = 0
                _rideLogState.value = RideLogState(isLogging = true, filePath = documentUri.toString())
            }

            addLog("Ride logging started: $filename")
            rideLoggingJob?.cancel()
            rideLoggingJob = coroutineScope.launch {
                while (
                    isActive &&
                    _connectionState.value == BleConnectionState.CONNECTED &&
                    _authState.value == BleAuthState.AUTHENTICATED
                ) {
                    requestDataSegment(
                        CMD_ID_CONTROLLER,
                        RideTelemetrySample.CONTROLLER_TELEMETRY_OFFSET,
                        RideTelemetrySample.CONTROLLER_TELEMETRY_LENGTH
                    )
                    delay(RIDE_LOG_INTERVAL_MS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start ride logging: ${e.message}", e)
            addLog("Ride logging failed: ${e.message}")
        }
    }

    fun stopRideLogging() {
        val previousPath = _rideLogState.value.filePath
        rideLoggingJob?.cancel()
        rideLoggingJob = null

        synchronized(rideLogLock) {
            try {
                rideLogWriter?.flush()
                rideLogWriter?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Could not close ride log: ${e.message}", e)
            } finally {
                rideLogWriter = null
                rideLogStartedAtMs = null
                _rideLogState.value = RideLogState()
            }
        }

        if (previousPath != null) {
            addLog("Ride logging stopped: $previousPath")
        }
    }

    private fun createRideLogDocument(destinationTreeUri: Uri, filename: String): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, treeDocumentId)
        return DocumentsContract.createDocument(context.contentResolver, parentUri, "text/csv", filename)
            ?: throw IOException("Cannot create $filename in selected folder")
    }

    // --- Coroutine Scope ---
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var rideLoggingJob: Job? = null
    private var rideLogWriter: BufferedWriter? = null
    private var rideLogStartedAtMs: Long? = null
    private val rideLogLock = Any()
    private var lastRideTelemetryLogMs: Long = 0

    // --- Fragmentation Buffering State ---
    private var isAssemblingFragmentedFrame: Boolean = false
    private val fragmentedFrameBuffer = ByteArrayOutputStream()
    private var expectedFragmentedFrameLength: Int = 0 // Store expected total length
    private var negotiatedMtu: Int = 23
    private var pendingPersonalizedWrite: PendingPersonalizedWrite? = null
    private var lastWriteTimeMs: Long = 0
    // -----------------------------------
    // --- Auth State Tracking ---
    private var authChallenge: ByteArray? = null
    private var isAuthFlowRunning = false
    private var authTimeoutJob: Job? = null
    private var mtuAuthFallbackJob: Job? = null
    // ------------------------

    private data class PendingPersonalizedWrite(
        val targetType: Byte,
        val startPosition: Byte,
        val payload: ByteArray
    )

    companion object {
        private const val TAG = "BleRepository"
        private const val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds
        private const val CONNECTION_TIMEOUT: Long = 10000 // Connection attempt timeout
        private const val AUTH_TIMEOUT: Long = 10000
        private const val MTU_AUTH_FALLBACK_DELAY: Long = 1500
        private const val RIDE_LOG_INTERVAL_MS: Long = 1000
        private const val RIDE_LOG_STATUS_INTERVAL_MS: Long = 2000

     //  Constants
        private const val FRAME_START_BYTE_1: Byte = 0x55
        private const val FRAME_START_BYTE_2: Byte = -0x56 // 0xAA
        private const val FIXED_VALUE_1: Byte = 0x01
        private const val FIXED_VALUE_2: Byte = 0x11        // Indicates a request/command
        private const val READ_INDICATOR: Byte = 0x01       // Respond command for read 0X2 for write
        private const val WRITE_INDICATOR: Byte = 0x02      // Differentiates write commands
        private const val CERTIFICATION_INDICATOR: Byte = 0x20 // Certification command
    private const val EVENT_NOTIFICATION_INDICATOR: Byte = 0x21 // Event notification command

        private const val CMD_ID_AUTH: Byte = 0x10         // Auth device

        private const val READ_FRAME_SIZE: Int = 10

        // --- Received Frame Structure Indices/Offsets
        // Byte Pos | Field                  | Index | Example (Partial SoftVer)
        //----------|------------------------|-------|---------------------------
        // 1-2      | Start of Frame         | 0-1   | 55 aa
        // 3        | Total Payload Length   | 2     | 18 (Hex = 24 Dec)
        // 4        | Command ID / Type      | 3     | a5
        // 5        | Fixed Param 1? Echo?   | 4     | 11  Target ID
        // 6        | Fixed Param 2? Status? | 5     | 04   Response ?
        // 7        | Start Position         | 6     | 18 (Hex = 24 Dec)
        // 8 to CRC | Payload Data           | 7+    | 44 50 ...
   // last 2 bytes  | CRC                    | 7+    | ...c4 e9
        //--------------------------------------------------------------------
        private const val RSP_TOTAL_PAYLOAD_LEN_INDEX = 2
        private const val RSP_TYPE_INDEX = 3        // Command ID/Type from device
        private const val RSP_CMD_ECHO_INDEX = 4    // Often 0x11?
        private const val RSP_STATUS_INDEX = 5      // Often 0x04?
        private const val RSP_START_POS_INDEX = 6   // Start position for partial data
        private const val RSP_PAYLOAD_START_INDEX = 7 // Index where actual data begins
        private const val RSP_MIN_HEADER_SIZE = 7 // Minimum bytes needed to read up to payload start
        private const val RSP_TRAILER_SIZE = 2 // Size after payload (CRC_L CRC_H FE)

        // --- Command IDs (Used as Byte 5 in SENT frame) ---
        const val CMD_ID_CONTROLLER: Byte = -0x5d // 0xA3
        const val CMD_ID_METER: Byte = -0x5b      // 0xA5
        const val CMD_ID_PERSONALIZED: Byte = -0x57 // 0xA9
        const val CMD_ID_BATTERY: Byte = -0x5c    // 0xA4
        const val CMD_ID_SENSOR: Byte = -0x59     // 0xA7
        const val CMD_ID_CONFIG: Byte = -0x5f     // 0xA1
        const val CMD_ID_CAN: Byte = -0x5e        // 0xA2

        // --- Write Target Offsets (Based on MeterInfo analysis) ---
        const val METER_OFFSET_LIGHT: Byte = -0x5a      // 0xA6
        // Add other offsets if needed (e.g., for ControllerInfo writes)
        // const val CONTROLLER_OFFSET_XXX: Byte = ...


        // --- Expected Response Payload Sizes (Mapping CMD ID to Size) ---
        // Using a map for easier lookup
        private val EXPECTED_RESPONSE_PAYLOAD_SIZES  = mapOf(
            CMD_ID_CONTROLLER to BfMeterConfig.BfControllerInfo_Total_Size, // 237
            CMD_ID_METER to BfMeterConfig.BfMeterInfo_Total_Size,           // 198
            CMD_ID_PERSONALIZED to BfMeterConfig.BfPersonalizedInfo_Total_Size, // 115
            CMD_ID_BATTERY to BfMeterConfig.BfBattery_Total_Size,         // 244 (Add to BfMeterConfig.kt)
            CMD_ID_SENSOR to BfMeterConfig.BfSensorInfo_Total_Size,         // 164 (Add to BfMeterConfig.kt)
            CMD_ID_CONFIG to BfMeterConfig.BfIotConfigInfo_Total_Size,      // 237 (Add to BfMeterConfig.kt)
            CMD_ID_CAN to BfMeterConfig.BfIotCanInfo_Total_Size             // 97  (Add to BfMeterConfig.kt)
            // Add mappings for other commands if their response size is known/fixed
        )

    }

    // --- Scanning ---
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "Scanning failed: Missing Permissions")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.d(TAG, "Scanning failed: Bluetooth not enabled")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (_connectionState.value == BleConnectionState.SCANNING) {
            Log.d(TAG, "Scan already in progress")
            return
        }

        _scanResults.value = emptySet()
        _connectionState.value = BleConnectionState.SCANNING
        Log.d(TAG, "Starting BLE Scan for ALL nearby devices...")

        scanJob?.cancel()
        scanJob = coroutineScope.launch {
            try {
                val filters: List<ScanFilter>? = null // No filtering
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setReportDelay(0)
                    .build()

                bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
                Log.d(TAG, "Scanner started (no filters).")

                delay(SCAN_PERIOD)
                if (_connectionState.value == BleConnectionState.SCANNING) {
                    stopScan() // Stop scan after timeout
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scan: ${e.message}", e)
                _connectionState.value = BleConnectionState.FAILED
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasRequiredPermissions()) {
            return
        }
        if (_connectionState.value != BleConnectionState.SCANNING) {
            return
        }

        scanJob?.cancel()
        scanJob = null
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(TAG,"Scan stopped.")
            if (_connectionState.value == BleConnectionState.SCANNING) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (_connectionState.value != BleConnectionState.SCANNING) return

            val discoveredDevice = DiscoveredBluetoothDevice(
                name = result.device.name ?: "Unknown Device",
                address = result.device.address,
                rssi = result.rssi
            )
            _scanResults.update { it + discoveredDevice }
        }
        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            if (_connectionState.value != BleConnectionState.SCANNING) return

            val newDevices = results.mapNotNull { result ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    DiscoveredBluetoothDevice(
                        name = "Unknown Device",
                        address = result.device.address,
                        rssi = result.rssi
                    )
                } else {
                        DiscoveredBluetoothDevice(
                        name = result.device.name ?: "Unknown Device",
                        address = result.device.address,
                        rssi = result.rssi
                    )
                }
            }.toSet()
            _scanResults.update { it + newDevices }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown scan error: $errorCode"
            }
            Log.e(TAG, "onScanFailed: $errorText")
            _connectionState.value = BleConnectionState.FAILED
            scanJob?.cancel()
            scanJob = null
        }
    }

    // --- Connection ---
    @SuppressLint("MissingPermission")
    fun connectDevice(deviceAddress: String) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG,"Connection failed: Missing Permissions")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG,"Connection failed: Bluetooth not enabled")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG,"Connection failed: Device not found")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (_connectionState.value == BleConnectionState.CONNECTING || _connectionState.value == BleConnectionState.CONNECTED) {
            Log.d(TAG,"Already connecting or connected to ${currentGatt?.device?.address}")
            if (currentGatt?.device?.address != deviceAddress) { disconnect() } else { return }
        }

        stopScan() // Stop scanning before connecting

        addLog("Connecting to ${device.name ?: deviceAddress}...")
        Log.d(TAG,"Connecting to ${device.name ?: deviceAddress}...")
        _connectionState.value = BleConnectionState.CONNECTING
        clearCommandQueue()

        connectJob?.cancel()
        connectJob = coroutineScope.launch(Dispatchers.IO) {
            var attempt = 0
            var connected = false
            while (attempt < 2 && !connected) {
                attempt++
                try {
                    _connectionState.value = BleConnectionState.CONNECTING
                    currentGatt?.close()
                    currentGatt = null
                    writeCharacteristic = null
                    notifyCharacteristic = null

                    delay(600) // pause after stopScan for Samsung M51 compat

                    addLog("connectGatt attempt $attempt")
                    currentGatt = if (attempt == 1) {
                        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, gattCallback)
                    }
                    if (currentGatt == null) throw Exception("connectGatt returned null")
                    addLog("connectGatt returned non-null")

                    withTimeout(CONNECTION_TIMEOUT) {
                        _connectionState.first { it != BleConnectionState.CONNECTING }
                    }
                    connected = true
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Attempt $attempt timed out for $deviceAddress")
                    addLog("Attempt $attempt timeout")
                    if (attempt < 2) {
                        addLog("Retrying after 1s...")
                        delay(1000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Attempt $attempt failed for $deviceAddress: ${e.message}", e)
                    addLog("Attempt $attempt failed: ${e.message}")
                    if (attempt < 2) {
                        addLog("Retrying after 1s...")
                        delay(1000)
                    }
                }
            }
            if (!connected) {
                addLog("All connection attempts failed")
                handleDisconnectOrFailure()
                _connectionState.value = BleConnectionState.FAILED
                _connectionError.value = "Connection failed after $attempt attempts"
            }
            connectJob = null
        }
    }

    // --- Disconnection ---
    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val deviceAddress = currentGatt?.device?.address
        if (currentGatt != null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.d(TAG,"Disconnecting from ${currentGatt?.device?.name ?: deviceAddress}...")
            clearCommandQueue() // Clear queue before disconnecting
            currentGatt?.disconnect()
            // Safeguard close
            coroutineScope.launch {
                delay(500)
                if (currentGatt != null) {
                    Log.w(TAG, "Forcing GATT close after disconnect timeout")
                    currentGatt?.close()
                    handleDisconnectOrFailure()
                }
            }
        } else {
            handleDisconnectOrFailure() // Reset state if already disconnected/null
        }
    }
    @SuppressLint("MissingPermission")
    private fun handleDisconnectOrFailure() {
        val wasConnecting = _connectionState.value == BleConnectionState.CONNECTING
        aa55RawLogger.stopFileLogging()
        _aa55RawStats.value = aa55RawLogger.snapshot()
        bleRawNotificationLogger.stopLogging()
        _bleRawNotificationStats.value = bleRawNotificationLogger.snapshot()
        stopRideLogging()
        canBleTransport?.onDisconnected()
        canBleTransport = null
        canControlCharacteristic = null
        canRxCharacteristic = null
        canTxCharacteristic = null
        canControlSubscribed = false
        canRxSubscribed = false
        currentGatt?.close() // Ensure closed if not null
        currentGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        clearCommandQueue() // Clear queue and reset flag
        // Reset parsed data StateFlows
        _controllerInfo.value = null
        _meterInfo.value = null
        _personalizedInfo.value = null
        _batteryInfo.value = null
        _sensorInfo.value = null
        _iotConfigInfo.value = null
        _iotCanInfo.value = null
        // Reset auth state
        _authState.value = BleAuthState.NOT_AUTHENTICATED
        authChallenge = null
        isAuthFlowRunning = false
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        mtuAuthFallbackJob?.cancel()
        mtuAuthFallbackJob = null
        pendingPersonalizedWrite = null
        negotiatedMtu = 23
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (wasConnecting) {
            _connectionError.value = "Connection failed or timeout"
        }
        if (_connectionState.value != BleConnectionState.DISCONNECTED) {
            _connectionState.value = BleConnectionState.DISCONNECTED // Default to disconnected state
        }
        connectJob?.cancel() // Ensure any lingering connect job is cancelled
    }

    // --- GATT Callback ---
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: deviceAddress
            coroutineScope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Successfully connected to $deviceName")
                            addLog("GATT connected")
                            clearCommandQueue() // Ensure queue is clear
                            delay(600)
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to start service discovery for $deviceName")
                                gatt.disconnect()
                            } else {
                                Log.d(TAG, "Started service discovery for $deviceName")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Successfully disconnected from $deviceName")
                            handleDisconnectOrFailure() // Use helper to reset state
                        }
                        else -> Log.w(TAG, "Unhandled connection state change: $newState for $deviceName")
                    }
                } else {
                    Log.e(TAG, "GATT Error onConnectionStateChange for $deviceName. Status: $status, NewState: $newState")
                    addLog("GATT error: status=$status newState=$newState")
                    handleDisconnectOrFailure() // Use helper to reset state
                    if (newState == BluetoothProfile.STATE_CONNECTING || _connectionState.value == BleConnectionState.CONNECTING) {
                        _connectionState.value = BleConnectionState.FAILED
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            coroutineScope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered successfully for $deviceName.")
                    addLog("Services discovered")
                    logDiscoveredGatt(gatt)
                    _bleServicesDebug.value = gatt.services.map { service ->
                        BleServiceDebugEntry(
                            serviceUuid = service.uuid.toString(),
                            characteristicUuids = service.characteristics.take(20).map { it.uuid.toString() }
                        )
                    }.take(20)
                    val service = gatt.getService(BleConstants.SERVICE_UUID) // Check for NUS
                    if (service == null) {
                        Log.e(TAG, "Nordic UART Service ${BleConstants.SERVICE_UUID} not found on $deviceName")
                        addLog("NUS service not found")
                        gatt.disconnect()
                        _connectionState.value = BleConnectionState.FAILED
                        return@launch
                    }
                    writeCharacteristic = service.getCharacteristic(BleConstants.UART_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(BleConstants.UART_NOTIFY_UUID)
                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        Log.e(TAG, "Required NUS characteristics (TX/RX) not found in service on $deviceName")
                        gatt.disconnect()
                        _connectionState.value = BleConnectionState.FAILED
                        return@launch
                    }
                    Log.i(TAG, "NUS write characteristic properties: ${describeProperties(writeCharacteristic!!.properties)}")
                    Log.i(TAG, "NUS notify characteristic properties: ${describeProperties(notifyCharacteristic!!.properties)}")
                    enableNotifications(gatt, notifyCharacteristic!!)
                    aa55RawLogger.startFileLogging(context)
                    _aa55RawStats.value = aa55RawLogger.snapshot()
                    bleRawNotificationLogger.startLogging(context)
                    _bleRawNotificationStats.value = bleRawNotificationLogger.snapshot()

                    val canInfo = TransportDetector.detect(gatt.services)
                    _canBleServiceFound.value = canInfo != null
                    if (canInfo != null) {
                        canControlCharacteristic = canInfo.controlCharacteristic
                        canRxCharacteristic = canInfo.rxCharacteristic
                        canTxCharacteristic = canInfo.txCharacteristic
                        canControlSubscribed = false
                        canRxSubscribed = false

                        val crypto = NativeCryptoProvider()
                        val timer = CoroutineHandshakeTimer()
                        val handshake = CanBleHandshake(crypto, timer, HandshakeRetryConfig())
                        canBleTransport = CanBleTransport(handshake)
                        canBleTransport!!.onWriteRequired = { _, data ->
                            sendCanCommand(data)
                        }
                        canBleTransport!!.onServiceFound()

                        enableNotifications(gatt, canInfo.controlCharacteristic)
                        enableNotifications(gatt, canInfo.rxCharacteristic)
                    }
                } else {
                    Log.e(TAG, "Service discovery failed for $deviceName with status: $status")
                    gatt.disconnect()
                    _connectionState.value = BleConnectionState.FAILED
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val cccdUuid = BleConstants.CCCD_UUID
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) { /* Error handling */ Log.e(TAG, "CCCD descriptor not found"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            if (!gatt.setCharacteristicNotification(characteristic, true)) { /* Error handling */                         Log.e(TAG, "Failed to enable local notification"); addLog("setCharacteristicNotification failed"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to CCCD ${descriptor.uuid}")
            val payload = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> { /* Error handling */ Log.e(TAG, "Characteristic supports neither Notify nor Indicate"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(descriptor, payload); Log.d(TAG, "writeDescriptor (Tiramisu+) result code: $result"); if(result != BluetoothStatusCodes.SUCCESS) { Log.e(TAG, "gatt.writeDescriptor failed immediately with code: $result") }
            } else {
                descriptor.value = payload; if (!gatt.writeDescriptor(descriptor)) { /* Error handling */ Log.e(TAG, "Legacy gatt.writeDescriptor failed"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; }
            }
        }



        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            coroutineScope.launch {
                if (descriptor.uuid == BleConstants.CCCD_UUID && descriptor.characteristic.uuid == BleConstants.UART_NOTIFY_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Notifications enabled successfully for $deviceName")
                        addLog("CCCD written, requesting MTU")
                        _connectionState.value = BleConnectionState.CONNECTED
                        val mtuRequested = requestMtu(gatt)
                        if (mtuRequested) {
                            mtuAuthFallbackJob?.cancel()
                            mtuAuthFallbackJob = coroutineScope.launch {
                                delay(MTU_AUTH_FALLBACK_DELAY)
                                if (_connectionState.value == BleConnectionState.CONNECTED &&
                                    _authState.value == BleAuthState.NOT_AUTHENTICATED
                                ) {
                                    addLog("MTU callback timeout, starting authentication")
                                    startAuthentication()
                                }
                            }
                        } else {
                            addLog("MTU request failed immediately, starting authentication")
                            startAuthentication()
                        }
                    } else {
                        Log.e(TAG, "Failed to write CCCD for $deviceName. Status: $status")
                        gatt.disconnect()
                    }
                } else if (descriptor.characteristic.uuid == TransportDetector.CAN_CONTROL_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        canControlSubscribed = true
                        checkCanBleReady()
                    } else {
                        Log.e(TAG, "Failed to enable CAN_CONTROL notifications. Status: $status")
                    }
                } else if (descriptor.characteristic.uuid == TransportDetector.CAN_RX_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        canRxSubscribed = true
                        checkCanBleReady()
                    } else {
                        Log.e(TAG, "Failed to enable CAN_RX notifications. Status: $status")
                    }
                } else { Log.w(TAG, "onDescriptorWrite for unknown descriptor: ${descriptor.uuid}") }
            }
        }




        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            val dataWrittenHex = characteristic.value?.toHexString() ?: "N/A"
            coroutineScope.launch {
                if (characteristic.uuid == BleConstants.UART_WRITE_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        lastWriteTimeMs = System.currentTimeMillis()
                        Log.i(TAG, "Successfully wrote to NUS RX (${characteristic.uuid}) on $deviceName: $dataWrittenHex")
                    } else {
                        Log.e(TAG, "Failed to write to NUS RX (${characteristic.uuid}) on $deviceName. Status: $status ")
                    }
                    isWriting.set(false) // Signal write completion
                    processNextCommand() // Process next command
                } else {
                    Log.w(TAG, "onCharacteristicWrite for unknown characteristic: ${characteristic.uuid}")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { handleCharacteristicChange(characteristic) }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleCharacteristicChange(characteristic, value) }
        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
            val data = value ?: characteristic.value
            bleRawNotificationLogger.log(characteristic.uuid, data)
            _bleRawNotificationStats.value = bleRawNotificationLogger.snapshot()
            when (characteristic.uuid) {
                BleConstants.UART_NOTIFY_UUID -> {
                    if (data != null) { processBleNotificationData(data) }
                    else { Log.w(TAG, "Received null data on ${characteristic.uuid}") }
                }
                TransportDetector.CAN_CONTROL_UUID -> {
                    if (data != null) { canBleTransport?.onControlNotificationReceived(data) }
                }
                TransportDetector.CAN_RX_UUID -> {
                    if (data != null) { canBleTransport?.onRxNotificationReceived(data, characteristic.uuid) }
                }
                else -> { Log.w(TAG, "onCharacteristicChanged for unexpected characteristic: ${characteristic.uuid}") }
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.i(TAG, "MTU changed to $mtu for $deviceName (max write payload ${maxWritePayloadSize()} bytes)")
                addLog("MTU changed to $mtu")
            }
            else { Log.w(TAG, "MTU change failed for $deviceName. Status: $status"); addLog("MTU change failed: status $status"); }
            mtuAuthFallbackJob?.cancel()
            mtuAuthFallbackJob = null
            if (_connectionState.value == BleConnectionState.CONNECTED &&
                _authState.value == BleAuthState.NOT_AUTHENTICATED
            ) {
                addLog("Starting authentication after MTU callback")
                startAuthentication()
            }
        }
    } // End gattCallback

// --- Data Sending and Queue Handling ---

    /**
     * Creates a write command frame (55 AA ... ChecksumL ChecksumH).
     * Always uses AA55Pack 2-byte checksum logic.
     *
     * @param targetInfoType The Command ID byte indicating the target data structure (e.g., CMD_ID_METER).
     * @param startPosition The offset within the target structure to write to.
     * @param payloadData The byte array containing the data to write.
     * @return The command frame byte array.
     */
    private fun createWriteRequestFrame(targetInfoType: Byte, startPosition: Byte, payloadData: ByteArray): ByteArray {
        val payloadLength = payloadData.size
        if (payloadLength < 1) {
            Log.e(TAG, "Write payload cannot be empty.")
            return byteArrayOf()
        }

        val headerSize = 7 // 55 AA Len 11 Cmd 02 StartPos
        val checksumSize = 2 // Always 2-byte AA55 checksum
        val totalFrameSize = headerSize + payloadLength + checksumSize

        val frame = ByteArray(totalFrameSize)

        var index = 0
        frame[index++] = FRAME_START_BYTE_1      // 55
        frame[index++] = FRAME_START_BYTE_2      // AA
        frame[index++] = payloadLength.toByte()  // Actual payload length
        frame[index++] = FIXED_VALUE_2           // 11
        frame[index++] = targetInfoType          // e.g., A5
        frame[index++] = WRITE_INDICATOR         // 02
        frame[index++] = startPosition           // e.g., A4, A6

        System.arraycopy(payloadData, 0, frame, index, payloadLength)
        index += payloadLength

        // AA55Pack 16-bit checksum: (~sum) & 0xFFFF
        val checksumEndIndex = index - 1
        var sum: Int = 0
        for (i in 2..checksumEndIndex) {
            sum += (frame[i].toInt() and 0xFF)
        }
        val calculatedChecksum = sum.inv() and 0xFFFF
        Log.v(TAG, "AA55 Checksum Calc: Sum=0x${sum.toString(16)}, CS=0x${calculatedChecksum.toString(16).padStart(4, '0')}")

        frame[index++] = (calculatedChecksum and 0xFF).toByte()       // LSB
        frame[index++] = ((calculatedChecksum shr 8) and 0xFF).toByte() // MSB

        if (frame.size > maxWritePayloadSize()) {
            Log.w(
                TAG,
                "Write frame (${frame.size} bytes) is larger than current GATT payload limit " +
                    "${maxWritePayloadSize()} bytes (MTU=$negotiatedMtu). Long writes may fail unless MTU changed."
            )
        }
        Log.d(TAG, "Created Write Frame: ${frame.toHexString()}")
        return frame
    }

    /** Generic function to send a single byte update */
    internal  fun sendSingleByteUpdate(targetType: Byte, offset: Byte, value: Byte) {
        val payload = byteArrayOf(value)
        val command = createWriteRequestFrame(targetType, offset, payload)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Single Byte Write: Type=0x${targetType.toHexString()}, Offset=0x${offset.toHexString()}, Value=0x${value.toHexString()}")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create single byte write frame.")
        }
    }
    /** Generic function to send a short (16-bit) update - uses multi-byte CRC */
    internal  fun sendShortUpdate(targetType: Byte, offset: Byte, value: Short) {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
        val command = createWriteRequestFrame(targetType, offset, payload)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Short Write: Type=0x${targetType.toHexString()}, Offset=0x${offset.toHexString()}, Value=$value (0x${value.toString(16)})")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create short write frame.")
        }
    }

    /**
     * Sends the complete 50-byte personalized settings block using the standard
     * write frame creation which handles the AA55 checksum.
     */
    fun sendPersonalizedSettings(
        motorAngles: ShortArray, // 10 shorts (20 bytes)
        accelerations: ByteArray, // 10 bytes
        speedLimits: ByteArray, // 10 bytes
        currentLimits: ByteArray // 10 bytes
    ) {
        // 1. Validate input array sizes
        if (motorAngles.size != 10 || accelerations.size != 10 ||
            speedLimits.size != 10 || currentLimits.size != 10) {
            Log.e(TAG, "Invalid array sizes for personalized settings write.")
            return
        }

        // 2. Prepare Payload Buffer (50 bytes)
        val payloadSize = 50
        val payloadBuffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)

        // Motor Start Angle (Bytes 0-19)
        for (angle in motorAngles) {
            payloadBuffer.putShort(angle)
        }
        // Acceleration (Bytes 20-29)
        payloadBuffer.put(accelerations)
        // Speed Limit (Bytes 30-39)
        payloadBuffer.put(speedLimits)
        // Current Limit (Bytes 40-49)
        payloadBuffer.put(currentLimits)

        val payload = payloadBuffer.array()

        // 3. Create the command frame using the generic function
        //    Target Type: CMD_ID_PERSONALIZED (0xA9)
        //    Start Position: 0x41 (65) - This seems to be the fixed start for this block
        //    Payload: The 50 bytes we just assembled
        Log.d(TAG, "Creating Personalized Settings write frame...")
        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_PERSONALIZED,
            startPosition = 0x41.toByte(), // 65 decimal
            payloadData = payload
        )

        // 4. Queue the command if frame creation was successful
        if (commandFrame.isNotEmpty()) {
            pendingPersonalizedWrite = PendingPersonalizedWrite(
                targetType = CMD_ID_PERSONALIZED,
                startPosition = 0x41.toByte(),
                payload = payload.copyOf()
            )
            Log.i(TAG, "Personalized write expected payload[0x41..0x72]: ${payload.toHexString()}")
            Log.d(TAG,"Queueing Personalized Write: ${commandFrame.toHexString()}")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG,"Failed to create personalized write frame.")
        }
    }

    fun sendControllerAccelerationUpdate(value: Byte) {
        Log.d(TAG, "TEST: Sending Controller acceleration single byte = $value (0x${value.toHexString()})")
        sendSingleByteUpdate(CMD_ID_CONTROLLER, 213.toByte(), value)
    }

    fun sendControllerFullBlockUpdate(modifiedRawData: ByteArray) {
        Log.d(TAG, "Writing full Controller block (${modifiedRawData.size} bytes at offset 0)")
        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_CONTROLLER,
            startPosition = 0.toByte(),
            payloadData = modifiedRawData
        )
        if (commandFrame.isNotEmpty()) {
            Log.i(TAG, "Queueing full Controller block write")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG, "Failed to create full Controller block write frame")
        }
    }

    fun sendControllerPartialUpdate(startOffset: Byte, payload: ByteArray) {
        Log.d(TAG, "Writing Controller partial (${payload.size} bytes at offset 0x${String.format("%02X", startOffset)})")
        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_CONTROLLER,
            startPosition = startOffset,
            payloadData = payload
        )
        if (commandFrame.isNotEmpty()) {
            Log.i(TAG, "Queueing Controller partial write: ${commandFrame.toHexString()}")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG, "Failed to create Controller partial write frame")
        }
    }

    fun sendPersonalizedAccelerationSingleUpdate(value: Byte) {
        Log.d(TAG, "TEST: Sending Personalized acceleration[0] single byte = $value (0x${value.toHexString()})")
        sendSingleByteUpdate(CMD_ID_PERSONALIZED, 0x55.toByte(), value)
    }

    /**
     * Creates a certification frame (command 0x20) for AES auth.
     * Format: 55 AA 10 11 10 20 00 <16 encrypted bytes> CS_LO CS_HI
     */
    private fun createCertificationFrame(targetType: Byte, address: Byte, encryptedData: ByteArray): ByteArray {
        if (encryptedData.size != 16) {
            Log.e(TAG, "Certification data must be exactly 16 bytes, got ${encryptedData.size}")
            return byteArrayOf()
        }

        val headerSize = 7
        val payloadLength = encryptedData.size // 16
        val checksumSize = 2
        val totalFrameSize = headerSize + payloadLength + checksumSize

        val frame = ByteArray(totalFrameSize)

        var index = 0
        frame[index++] = FRAME_START_BYTE_1        // 55
        frame[index++] = FRAME_START_BYTE_2        // AA
        frame[index++] = payloadLength.toByte()    // 0x10
        frame[index++] = FIXED_VALUE_2             // 11
        frame[index++] = targetType                // 0x10 (auth)
        frame[index++] = CERTIFICATION_INDICATOR   // 0x20
        frame[index++] = address                   // 0x00

        System.arraycopy(encryptedData, 0, frame, index, payloadLength)
        index += payloadLength

        var sum: Int = 0
        for (i in 2 until index) {
            sum += (frame[i].toInt() and 0xFF)
        }
        val calculatedChecksum = sum.inv() and 0xFFFF

        frame[index++] = (calculatedChecksum and 0xFF).toByte()
        frame[index++] = ((calculatedChecksum shr 8) and 0xFF).toByte()

        Log.d(TAG, "Created Certification Frame: ${frame.toHexString()}")
        return frame
    }

    /**
     * Creates an event notification frame (command 0x21) used for heartbeat.
     * Format: 55 AA LEN 11 10 21 02 <payload> CS_LO CS_HI
     * Target = 0x10 (DeviceType_IOT), Address = 0x02
     */
    private fun createEventNotificationFrame(payloadData: ByteArray): ByteArray {
        if (payloadData.isEmpty()) {
            Log.e(TAG, "Event notification payload cannot be empty.")
            return byteArrayOf()
        }
        val headerSize = 7
        val checksumSize = 2
        val totalFrameSize = headerSize + payloadData.size + checksumSize

        val frame = ByteArray(totalFrameSize)
        var index = 0
        frame[index++] = FRAME_START_BYTE_1
        frame[index++] = FRAME_START_BYTE_2
        frame[index++] = payloadData.size.toByte()
        frame[index++] = FIXED_VALUE_2
        frame[index++] = CMD_ID_AUTH          // 0x10 = DeviceType_IOT
        frame[index++] = EVENT_NOTIFICATION_INDICATOR // 0x21
        frame[index++] = 0x02                 // address

        System.arraycopy(payloadData, 0, frame, index, payloadData.size)
        index += payloadData.size

        // AA55Pack 16-bit checksum: (~sum) & 0xFFFF
        var sum = 0
        for (i in 2 until index) {
            sum += (frame[i].toInt() and 0xFF)
        }
        val checksum = sum.inv() and 0xFFFF
        frame[index++] = (checksum and 0xFF).toByte()
        frame[index++] = ((checksum shr 8) and 0xFF).toByte()

        Log.d(TAG, "Created Event Notification Frame: ${frame.toHexString()}")
        return frame
    }

    /** Sets the Headlight state (Assumes 1 byte payload) */
    fun setLightState(isOn: Boolean) {
        Log.d(TAG, "Setting Light State to: ${if(isOn) "ON" else "OFF"}")
        val value = if (isOn) 0x01.toByte() else 0x00.toByte()
        val payload = byteArrayOf(value) // Create 1-byte payload
        val command = createWriteRequestFrame(
            CMD_ID_METER,               // Target Meter Info
            METER_OFFSET_LIGHT,         // Start Position 0xA6
            payload                     // Pass payload byte array
        )
        Log.d(TAG,"Queueing Set Light Cmd: ${command.toHexString()}")
        sendCommand(command)
    }

    fun setTireCircumference(circumferenceMm: Int) {
        if (circumferenceMm !in 0..3000) { Log.w(TAG, "Invalid tire circumference: $circumferenceMm"); return }
        val value = circumferenceMm.toShort()
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_CONTROLLER,
            startPosition = 188.toByte(),
            payloadData = payload
        )
        //  Queue the command if frame creation was successful
        if (commandFrame.isNotEmpty()) {
            Log.d(TAG,"Queueing Personalized Write: ${commandFrame.toHexString()}")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG,"Failed to create personalized write frame.")
        }

    }

    // --- MTU ---
    private fun requestMtu(gatt: BluetoothGatt): Boolean {
        @SuppressLint("MissingPermission")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.requestMtu(250)
        } else {
            @Suppress("DEPRECATION")
            gatt.requestMtu(250)
        }
        Log.d(TAG, "Requested MTU 250, result: $result")
        addLog("Requested MTU 250: $result")
        return result
    }

    // --- Auth Flow ---
    private fun startAuthentication() {
        if (isAuthFlowRunning || _authState.value == BleAuthState.AUTHENTICATED) {
            Log.d(TAG, "Authentication already running or complete, skipping start")
            return
        }
        Log.i(TAG, "Starting authentication flow...")
        addLog("Authentication started")
        authChallenge = null
        isAuthFlowRunning = true
        _authState.value = BleAuthState.AUTHENTICATING
        authTimeoutJob?.cancel()
        authTimeoutJob = coroutineScope.launch {
            delay(AUTH_TIMEOUT)
            if (isAuthFlowRunning && _authState.value == BleAuthState.AUTHENTICATING) {
                failAuthentication("Authentication timeout after ${AUTH_TIMEOUT}ms")
            }
        }

        // Step 1: Read 4 random bytes from auth device
        val frame = createReadRequestFrame(CMD_ID_AUTH, 0, 4)
        if (frame.isNotEmpty()) {
            addLog("Auth read TX: ${frame.toHexString()}")
            sendCommand(frame)
            Log.d(TAG, "Auth step 1: sent meterRead(0x10, 0x00, 4)")
        } else {
            failAuthentication("Failed to create auth read frame")
        }
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        if (payload.size != 4) {
            failAuthentication("Auth challenge expected 4 bytes, got ${payload.size}")
            return
        }

        authChallenge = payload.copyOf()
        Log.d(TAG, "Auth step 1 complete: received challenge ${payload.toHexString()}")
        addLog("Auth challenge RX: ${payload.toHexString()}")

        // Step 2: Pad to 16 bytes with zeros, encrypt with AES
        val padded = payload + ByteArray(12) // 4 + 12 zeros = 16
        val encrypted = AESUtils.encrypt(padded)

        if (encrypted == null) {
            failAuthentication("Auth step 2 failed: AES encryption returned null")
            return
        }

        Log.d(TAG, "Auth step 2: AES encrypted -> ${encrypted.toHexString()}")

        // Step 3: Send certification
        val certFrame = createCertificationFrame(CMD_ID_AUTH, 0, encrypted)
        if (certFrame.isNotEmpty()) {
            addLog("Auth cert TX: ${certFrame.toHexString()}")
            sendCommand(certFrame)
            Log.d(TAG, "Auth step 3: sent certification")
        } else {
            failAuthentication("Failed to create certification frame")
        }
    }

    private fun handleAuthResult(resultPayload: ByteArray) {
        // Expected payload: [commandEcho, address, result]
        val result = if (resultPayload.size >= 3) {
            resultPayload[2].toInt() and 0xFF
        } else if (resultPayload.size >= 1) {
            resultPayload[0].toInt() and 0xFF
        } else {
            -1
        }

        addLog("Auth result RX: ${resultPayload.toHexString()} -> $result")
        if (result == 0) {
            Log.i(TAG, "Authentication SUCCESSFUL!")
            addLog("Authentication SUCCESSFUL")
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            _authState.value = BleAuthState.AUTHENTICATED
            // Pre-fetch all data immediately so UI has it as soon as it renders
            coroutineScope.launch {
                kotlinx.coroutines.delay(200)
                sendReadRequestCommand(CMD_ID_CONTROLLER)
                sendReadRequestCommand(CMD_ID_METER)
                sendReadRequestCommand(CMD_ID_BATTERY)
                sendReadRequestCommand(CMD_ID_SENSOR)
                sendReadRequestCommand(CMD_ID_CONFIG)
                sendReadRequestCommand(CMD_ID_CAN)
            }
            // Start heartbeat (co 1s) to keep BLE notification stream alive
            heartbeatJob?.cancel()
            heartbeatJob = coroutineScope.launch {
                while (isActive) {
                    val timestamp = System.currentTimeMillis() / 1000
                    val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((timestamp and 0xFFFFFFFFL).toInt()).array()
                    val frame = createEventNotificationFrame(payload)
                    if (frame.isNotEmpty()) {
                        sendCommand(frame)
                    }
                    delay(1000)
                }
            }
        } else {
            failAuthentication("Authentication FAILED. Result: $result (payload: ${resultPayload.toHexString()})")
        }
        isAuthFlowRunning = false
        authChallenge = null
    }

    private fun failAuthentication(message: String) {
        Log.e(TAG, message)
        addLog(message)
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        _authState.value = BleAuthState.AUTH_FAILED
        _connectionError.value = message
        isAuthFlowRunning = false
        authChallenge = null
        clearCommandQueue()
    }

    /**
     * Creates a read request frame (55 AA 01 11 TARGET 01 ADDR LEN CS_LO CS_HI).
     * Uses AA55Pack 2-byte checksum.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     * @param startPosition The starting byte offset of the data to read (0-255).
     * @param requestedLength The number of bytes to read (0-255).
     * @return The read command frame byte array, or an empty ByteArray on error.
     */
    private fun createReadRequestFrame(commandId: Byte, startPosition: Int, requestedLength: Int): ByteArray {
        if (startPosition !in 0..255 || requestedLength !in 0..255) {
            Log.e(TAG, "Invalid startPosition ($startPosition) or requestedLength ($requestedLength). Must be 0-255.")
            return byteArrayOf()
        }

        // Standard AA55Pack format: 55 AA 01 11 TARGET 01 ADDR LEN CS_LO CS_HI (10 bytes)
        // 16-bit checksum: (~sum[2..7]) & 0xFFFF
        val frame = ByteArray(10)

        frame[0] = FRAME_START_BYTE_1          // 55
        frame[1] = FRAME_START_BYTE_2          // AA
        frame[2] = FIXED_VALUE_1               // 01 (LEN)
        frame[3] = FIXED_VALUE_2               // 11
        frame[4] = commandId                   // e.g., 0x10 (auth), 0xA5 (controller)
        frame[5] = READ_INDICATOR              // 01
        frame[6] = startPosition.toByte()      // Starting byte offset
        frame[7] = requestedLength.toByte()    // Length to read

        // AA55Pack 16-bit checksum: (~sum) & 0xFFFF
        var sum: Int = 0
        for (i in 2..7) {
            sum += (frame[i].toInt() and 0xFF)
        }
        val calculatedChecksum = sum.inv() and 0xFFFF
        frame[8] = (calculatedChecksum and 0xFF).toByte()       // CS_LO
        frame[9] = ((calculatedChecksum shr 8) and 0xFF).toByte() // CS_HI

        return frame
    }

    // --- Public Function to Send Custom Request ---
    /**
     * Queues a command to request a specific segment of data from the device.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     * @param start The starting byte offset (0-based) of the data segment.
     * @param length The number of bytes to request.
     */
    fun requestDataSegment(commandId: Byte, start: Int, length: Int) {
        Log.d(TAG, "Requesting data segment: Type=0x${commandId.toHexString()}, Start=$start, Length=$length")
        val command = createReadRequestFrame(commandId, start, length)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Custom Req: ${command.toHexString()}")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create custom request frame (invalid params?).")
        }
    }

    /**
     * Creates and queues a standard 10-byte read request frame.
     * This is intended to be called by the ViewModel.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     */
    internal fun sendReadRequestCommand(commandId: Byte) {
        Log.d(TAG, "Preparing read request for Cmd ID: 0x${commandId.toHexString()}")

        val expectedResponseLength = EXPECTED_RESPONSE_PAYLOAD_SIZES[commandId]
        if (expectedResponseLength == null) {
            Log.w(TAG, "Cannot send read request: Unknown expected response size for Cmd ID 0x${commandId.toHexString()}")
            // Optionally: Send a default length request? Or fail? Let's fail for now.
            // Add Log or some error feedback if needed
            return
        }

        val responseLengthByte = (expectedResponseLength ?: 0).let {
            if (it > 255) 0xFF else it // Cap at 255 if size is too large
        }.toInt()
        val command = createReadRequestFrame(commandId,0, responseLengthByte )

        if (command.isNotEmpty()) {
            Log.d(TAG, "Queueing Read Request: ${command.toHexString()}")
            // Use the *private* sendCommand helper
            sendCommand(command)
        } else {
            Log.e(TAG, "Failed to create read request frame for Cmd ID 0x${commandId.toHexString()}")
        }
    }


    fun sendCanCommand(data: ByteArray) {
        if (currentGatt == null) { Log.d(TAG, "Cannot send CAN command: GATT not available."); return }
        val characteristic = canTxCharacteristic ?: run { Log.d(TAG, "Cannot send CAN command: CAN_TX not available."); return }
        @Suppress("DEPRECATION")
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        currentGatt?.writeCharacteristic(characteristic)
    }

    private fun checkCanBleReady() {
        if (canControlSubscribed && canRxSubscribed && canBleTransport != null) {
            canBleTransport!!.onNotificationsSubscribed()
        }
    }

   fun sendCommand(data: ByteArray) {
        if (_connectionState.value != BleConnectionState.CONNECTED) {   Log.d(TAG,"Cannot queue command: Not connected."); return }
        if (writeCharacteristic == null) {   Log.d(TAG,"Cannot queue command: Write Characteristic not available."); return }
        // Block non-auth commands until authentication completes
        val isAuthCommand = data.size > 4 && data[4] == CMD_ID_AUTH
        if (!isAuthCommand && _authState.value != BleAuthState.AUTHENTICATED) {
            Log.d(TAG, "Cannot queue command: not authenticated (state=${_authState.value}).")
            return
        }
        val writeProperty = writeCharacteristic!!.properties
        if (writeProperty and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            Log.d(TAG,"Error: Write characteristic does not support writing."); return
        }
        synchronized(commandQueue) { commandQueue.offer(data); Log.d(TAG, "Command queued. Queue size: ${commandQueue.size}") }
        aa55RawLogger.logTx(data)
        _aa55RawStats.value = aa55RawLogger.snapshot()
        processNextCommand()
    }

    @SuppressLint("MissingPermission")
    private fun processNextCommand() {
        coroutineScope.launch(Dispatchers.IO) {
            if (_connectionState.value != BleConnectionState.CONNECTED) { Log.w(TAG, "processNextCommand: Not connected, clearing queue."); clearCommandQueue(); return@launch }
            if (isWriting.get()) { Log.d(TAG, "processNextCommand: Write already in progress."); return@launch }

            var commandToSend: ByteArray? = null
            synchronized(commandQueue) {
                if (commandQueue.isNotEmpty()) {
                    if (isWriting.compareAndSet(false, true)) { commandToSend = commandQueue.poll() }
                    else { Log.d(TAG,"processNextCommand: Busy flag was already set, skipping."); return@launch }
                } else { Log.d(TAG,"processNextCommand: Queue is empty."); return@launch }
            }

            if (commandToSend != null) {
                Log.d(TAG, "Processing command: ${commandToSend!!.toHexString()}. Queue size: ${commandQueue.size}")

                // *** CORRECTED PERMISSION CHECK ***
                var permissionCheckPassed = true // Assume true initially
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Only check BLUETOOTH_CONNECT on API 31+
                    if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        Log.e(TAG, "processNextCommand: Missing BLUETOOTH_CONNECT permission (API 31+).")
                        permissionCheckPassed = false
                    }
                }
                // No specific *write* permission check needed for API < 31 here.
                // If connectGatt succeeded, BLUETOOTH permission was implicitly handled.

                if (!permissionCheckPassed) {
                    isWriting.set(false) // Reset busy flag as we are aborting
                    // Command is lost because permission is missing
                    return@launch // Abort processing this command
                }
                // **********************************


                if (currentGatt == null || writeCharacteristic == null) { Log.e(TAG, "processNextCommand: GATT or Write Characteristic became null."); isWriting.set(false); return@launch }
                // Redundant check, already covered above, but keeping for safety:
                // if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) { Log.e(TAG, "processNextCommand: Missing BLUETOOTH_CONNECT permission."); addLog("Send Error: Missing permission."); isWriting.set(false); return@launch }

                val writeType = when {
                    writeCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    writeCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else -> { Log.e(TAG, "processNextCommand: Write characteristic lost write property?"); isWriting.set(false); return@launch }
                }
                Log.d(TAG,"Sending: ${commandToSend!!.toHexString()}")
                if (commandToSend!!.size > maxWritePayloadSize()) {
                    Log.w(
                        TAG,
                        "Sending ${commandToSend!!.size} bytes with MTU=$negotiatedMtu " +
                            "(max payload ${maxWritePayloadSize()}). If this write fails, MTU/fragmentation is the first suspect."
                    )
                }
                val success: Boolean = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        currentGatt?.writeCharacteristic(writeCharacteristic!!, commandToSend!!, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        writeCharacteristic!!.value = commandToSend!!; writeCharacteristic!!.writeType = writeType; currentGatt?.writeCharacteristic(writeCharacteristic!!) ?: false
                    }
                } catch (e: Exception) { Log.e(TAG, "Exception during writeCharacteristic: ${e.message}", e); false }

                if (!success) {
                    Log.e(TAG, "writeCharacteristic initiation failed for ${writeCharacteristic!!.uuid}")
                    isWriting.set(false)
                    processNextCommand()
                } else {
                    Log.d(TAG, "Write initiated for: ${commandToSend!!.toHexString()}. Type: ${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "DEFAULT" else "NO_RESPONSE"}")
                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                        isWriting.set(false)
                        processNextCommand()
                    }
                }
            }
        }
    }
    private fun clearCommandQueue() { synchronized(commandQueue) { commandQueue.clear() }; isWriting.set(false); Log.d(TAG, "Command queue cleared.") }

    // This function now directly processes the 55 AA ... FE frame
    private fun processBleNotificationData(data: ByteArray) {
        val rawHexString = data.toHexString()
        val msSinceWrite = System.currentTimeMillis() - lastWriteTimeMs
        if (msSinceWrite < 2000) {
            Log.i(TAG, "AFTER WRITE notify raw ($rawHexString) [${msSinceWrite}ms after write]")
        }
        Log.d(TAG,"Raw BLE Notify: $rawHexString (Size: ${data.size})")
        if (isAuthFlowRunning || _authState.value != BleAuthState.AUTHENTICATED) {
            addLog("Raw BLE Notify: $rawHexString (size=${data.size})")
        }

        // --- Frame Assembly Logic ---
        if (data.isNotEmpty()) {
            fragmentedFrameBuffer.write(data)
            if (isAssemblingFragmentedFrame || fragmentedFrameBuffer.size() <= RSP_MIN_HEADER_SIZE) {
                Log.d(TAG,"Fragment Assembly: Appended data (New Buffer Size: ${fragmentedFrameBuffer.size()})")
            }
        } else {
            Log.d(TAG,"Fragment Assembly: Received empty notification.")
            return
        }

        // Loop to process buffer content
        while (true) {
            val currentBufferBytes = fragmentedFrameBuffer.toByteArray()
            val currentBufferSize = currentBufferBytes.size

            if (!isAssemblingFragmentedFrame) {
                // --- Try to start a new frame ---
                if (currentBufferSize < RSP_MIN_HEADER_SIZE) { // Need header including StartPos
                    if (currentBufferSize > 0)  Log.d(TAG,"Fragment Assembly: Buffer too short for header ($currentBufferSize bytes). Waiting...")
                    break // Wait for more data
                }

                if (currentBufferBytes[0] == FRAME_START_BYTE_1 && currentBufferBytes[1] == FRAME_START_BYTE_2) {
                    // Found start sequence 55 AA
                    val declaredTotalPayloadLength = currentBufferBytes[RSP_TOTAL_PAYLOAD_LEN_INDEX].toInt() and 0xFF
                    val rspType = currentBufferBytes[RSP_TYPE_INDEX]

                    // Calculate expected total frame size based on DECLARED payload length
                    // Total Frame Size = SOF(2) + DeclaredPayloadLen(1) + HeaderUntilPayload(4) + Payload(N) +Trailer(2)

                    expectedFragmentedFrameLength = RSP_PAYLOAD_START_INDEX + declaredTotalPayloadLength + RSP_TRAILER_SIZE

                    isAssemblingFragmentedFrame = true // Mark as assembling
                    Log.d(TAG,"Fragment Assembly: START detected (Type=0x${rspType.toHexString()}, Declared Payload Len=$declaredTotalPayloadLength), Expecting Total Frame Length: $expectedFragmentedFrameLength (Buffer Size: $currentBufferSize)")
                    // Fall through to check if length is already met

                } else {
                    // Discard invalid start byte
                    Log.d(TAG,"Fragment Assembly: Discarding invalid byte at buffer start: 0x${currentBufferBytes[0].toHexString()}")
                    val remainingInBuffer = currentBufferBytes.sliceArray(1 until currentBufferSize)
                    fragmentedFrameBuffer.reset()
                    if (remainingInBuffer.isNotEmpty()) { fragmentedFrameBuffer.write(remainingInBuffer); continue }
                    else { break }
                }
            } // End if (!isAssemblingFragmentedFrame)

            // --- Continue assembling or process complete frame ---
            if (isAssemblingFragmentedFrame) {
                if (expectedFragmentedFrameLength <= 0) {
                    Log.d(TAG,"Fragment Assembly: Error - Assembling but expected length is unknown (<=0). Resetting.")
                    resetFragmentedFrameState()
                    fragmentedFrameBuffer.reset()
                    break
                }

                if (currentBufferSize >= expectedFragmentedFrameLength) {
                    // --- Received expected number of bytes (or more) ---
                    val completeFrame = currentBufferBytes.sliceArray(0 until expectedFragmentedFrameLength)
                    Log.d(TAG,"Fragment Assembly: Declared length ($expectedFragmentedFrameLength) reached. Processing frame.")

                    // Process the complete frame
                    parseCompleteFrame(completeFrame) // Parse based on extracted length

                    // --- Handle leftover bytes ---
                    val leftoverBytes = currentBufferBytes.sliceArray(expectedFragmentedFrameLength until currentBufferSize)
                    resetFragmentedFrameState()
                    fragmentedFrameBuffer.reset()
                    if (leftoverBytes.isNotEmpty()) {
                        Log.d(TAG,"Fragment Assembly: Found ${leftoverBytes.size} leftover bytes. Re-buffering.")
                        fragmentedFrameBuffer.write(leftoverBytes)
                        continue // Immediately process leftovers
                    } else {
                        Log.d(TAG,"Fragment Assembly: No leftover bytes.")
                        break // Finished processing
                    }
                    // -----------------------------

                } else {
                    // Not enough data yet
                    Log.d(TAG,"Fragment Assembly: Waiting for more data (Have $currentBufferSize/$expectedFragmentedFrameLength bytes)...")
                    break // Exit loop, wait for next ble notification
                }
            } else {
                break // Should not happen
            }

        } // End while(true)
    }

    // --- New function to reset fragmentation state ---
    private fun resetFragmentedFrameState() {
        isAssemblingFragmentedFrame = false
        fragmentedFrameBuffer.reset()
        expectedFragmentedFrameLength = 0 // Reset expected length
        // Log.d(TAG, "Fragmentation state reset.") // Optional log
    }


    // --- Modified parseCompleteFrame (Handles Full vs Partial based on StartPos) ---
    private fun parseCompleteFrame(data: ByteArray) {
        aa55RawLogger.logRx(data)
        _aa55RawStats.value = aa55RawLogger.snapshot()
        val frameHexString = data.toHexString()
        val baseLog = "Processing Assembled Frame: $frameHexString (Size: ${data.size})"

        // --- 1. Basic Frame Structure and Size Validation ---
        // Minimum size for AA55 header, length, target, startID, cmd, startPos + 2 CRC bytes = 9
        if (data.size < 9) {
            Log.w(TAG, "$baseLog (Frame Too Short: ${data.size} bytes, need at least 9)")
            return
        }
        // Check start bytes
        if (data[0] != FRAME_START_BYTE_1 || data[1] != FRAME_START_BYTE_2) {
            Log.w(TAG, "$baseLog (Invalid Frame Start)")
            return
        }

        // Extract header fields needed for validation and parsing
        val declaredTotalPayloadLength = data[RSP_TOTAL_PAYLOAD_LEN_INDEX].toInt() and 0xFF
        val rspType = data[RSP_TYPE_INDEX] // Command ID/Type from device
        // val rspCmdEcho = data[RSP_CMD_ECHO_INDEX] // Often 0x11? (Not used in checksum)
        // val rspStatus = data[RSP_STATUS_INDEX] // Often 0x04? (Not used in checksum)
        val rspStartPos = data[RSP_START_POS_INDEX].toInt() and 0xFF // Start position for partial data

        // --- 2. Checksum Validation (AA55Pack Logic) ---
        val checksumStartIndex = 2 // Start summing from the Length byte (index 2)
        val checksumEndIndex = data.size - 3 // Sum up to the byte *before* the first checksum byte

          // Calculate the sum
        var sum = 0
        for (i in checksumStartIndex..checksumEndIndex) {
            sum += (data[i].toInt() and 0xFF)
        }
        // Mask sum to 32 bits just in case, though unlikely to overflow standard Int
        sum = sum and -1 // Equivalent to 0xFFFFFFFF in this context

        Log.v(TAG, "Checksum Calc - Sum (Decimal): ${sum}, (Hex): 0x${sum.toString(16)}")

        // Calculate the expected checksum value (16-bit NOT of the lower 16 bits of the sum)
        val calculatedChecksum = sum.inv() and 0xFFFF
        Log.v(TAG, "Checksum Calc - Expected Value (Decimal): ${calculatedChecksum}, (Hex): 0x${calculatedChecksum.toString(16).padStart(4, '0')}")

        // Extract calculated checksum bytes (Little-Endian)
        val calculatedLsb = (calculatedChecksum and 0xFF).toByte()
        val calculatedMsb = ((calculatedChecksum shr 8) and 0xFF).toByte()
        Log.v(TAG, "Checksum Calc - Expected Bytes (Hex LE): ${calculatedLsb.toHexString()} ${calculatedMsb.toHexString()}")

        // Extract received checksum bytes
        val receivedChecksumLsb = data[data.size - 2]
        val receivedChecksumMsb = data[data.size - 1]
        Log.v(TAG, "Checksum Calc - Received Bytes (Hex): ${receivedChecksumLsb.toHexString()} ${receivedChecksumMsb.toHexString()}")

        // Compare
        val isValid = (calculatedLsb == receivedChecksumLsb) && (calculatedMsb == receivedChecksumMsb)

        if (!isValid) {
            Log.e(TAG, "$baseLog (Checksum INVALID. Calculated: ${calculatedLsb.toHexString()} ${calculatedMsb.toHexString()}, Received: ${receivedChecksumLsb.toHexString()} ${receivedChecksumMsb.toHexString()})")
            // Optionally: Notify UI or higher layer about checksum error
            // _errorStateFlow.value = BleError.ChecksumMismatch
            return // Discard invalid frame
        } else {
            Log.d(TAG, "$baseLog (Checksum VALID)")
        }
        // --- End Checksum Validation ---


        // --- 3. Determine Actual Payload Boundaries (Based on validated frame) ---
        val payloadStartIndex = RSP_PAYLOAD_START_INDEX // Data starts after the header (index 7)
        val payloadEndIndex = data.size - RSP_TRAILER_SIZE // Data ends before the 2 CRC bytes

        if (payloadStartIndex > payloadEndIndex) { // Should not happen if minimum size check passed
            Log.w(TAG, "$baseLog (Invalid payload indices: Start=$payloadStartIndex, End=$payloadEndIndex)")
            return
        }

        val payloadData = data.sliceArray(payloadStartIndex until payloadEndIndex)
        val actualPayloadSize = payloadData.size // This is the reliable payload size now

        // Log header info AFTER validation
        Log.d(TAG,"Validated Frame Header: Type=0x${rspType.toHexString()} StartPos=$rspStartPos (Actual Payload Size=$actualPayloadSize)")
        logWriteAckIfPresent(data, payloadData)

        // 4. Differentiate Full vs Partial based on Start Position
        if (rspStartPos == 0) {
            // --- Full Data Frame ---
            Log.d(TAG,"Detected Full Data Frame (StartPos = 0).")
            var handled = false
            // Optional: Verify if actual payload size matches expected full size for this type
            val expectedFullSize = EXPECTED_RESPONSE_PAYLOAD_SIZES[rspType]

            // --- Special handling for auth responses ---
            if (rspType == CMD_ID_AUTH && isAuthFlowRunning) {
                if (payloadData.size == 4 && authChallenge == null) {
                    Log.d(TAG, "Auth frame: received challenge (4 bytes)")
                    handleAuthChallenge(payloadData)
                    handled = true
                } else if (payloadData.size <= 4) {
                    Log.d(TAG, "Auth frame: received certification result, payload=${payloadData.toHexString()}")
                    handleAuthResult(payloadData)
                    handled = true
                } else {
                    Log.w(TAG, "Auth frame: unexpected payload size ${payloadData.size}")
                    addLog("Auth frame unexpected payload size ${payloadData.size}: ${payloadData.toHexString()}")
                }
            }

            if (expectedFullSize != null && actualPayloadSize != expectedFullSize) {
                Log.w(TAG,"Warning: Full frame size mismatch! Type=0x${rspType.toHexString()}, Got=$actualPayloadSize, Expected=$expectedFullSize")
                // Don't discard if it was handled (e.g. auth)
                if (handled) return
            }

            when (rspType) {
                CMD_ID_CONTROLLER -> {
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = ControllerInfo.parseControllerInfoPayload(payloadData)
                    if (info != null) {
                        _controllerInfo.value = info
                        handleRideTelemetrySample(
                            RideTelemetrySample.fromControllerInfo(
                                info = info,
                                batteryInfo = _batteryInfo.value,
                                startedAtMs = rideLogStartedAtMs,
                                source = "A3_FULL"
                            )
                        )
                        Log.d(TAG,"Parsed Full Controller Data: ${info.toString()}") // Use data class toString
                    } else {
                        Log.w(TAG,"Failed to parse Full Controller Info payload.")
                    }
                }
                CMD_ID_METER -> {
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = MeterInfo.parseMeterInfoPayload(payloadData)
                    if (info != null) {
                        _meterInfo.value = info
                        Log.d(TAG,"Parsed Full Meter Data: ${info.toString()}") // Use data class toString
                    } else {
                        Log.w(TAG,"Failed to parse Full Meter Info payload.")
                    }
                }
                CMD_ID_PERSONALIZED -> { // Add this case
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = PersonalizedInfo.parsePersonalizedInfoPayload(payloadData)
                    if (info != null) {
                        _personalizedInfo.value = info // Update StateFlow
                        Log.d(TAG,"Parsed Full Personalized Data: ${info.toString()}") // Use data class toString
                        logPendingPersonalizedReadback(info)
                    } else {
                        Log.w(TAG,"Failed to parse Full Personalized Info payload (size: $actualPayloadSize).")
                    }
                }
                CMD_ID_BATTERY -> {
                    handled = true
                    val info = BatteryInfo.parseBatteryInfoPayload(payloadData)
                    if (info != null) {
                        _batteryInfo.value = info
                        Log.d(TAG,"Parsed Full Battery Data: ${info.toString()}")
                    } else {
                        Log.w(TAG,"Failed to parse Full Battery Info payload (size: $actualPayloadSize).")
                    }
                }
                CMD_ID_SENSOR -> {
                    handled = true
                    val info = SensorInfo.parseSensorInfoPayload(payloadData)
                    if (info != null) {
                        _sensorInfo.value = info
                        Log.d(TAG,"Parsed Full Sensor Data: ${info.toString()}")
                    } else {
                        Log.w(TAG,"Failed to parse Full Sensor Info payload (size: $actualPayloadSize).")
                    }
                }
                CMD_ID_CONFIG -> { // Assuming this maps to IotConfigInfo
                    handled = true
                    val info = IotConfigInfo.parseIotConfigInfoPayload(payloadData)
                    if (info != null) {
                        _iotConfigInfo.value = info
                        Log.d(TAG,"Parsed Full IotConfig Data: ${info.toString()}")
                    } else {
                        Log.w(TAG,"Failed to parse Full IotConfig Info payload (size: $actualPayloadSize).")
                    }
                }
                CMD_ID_CAN -> { // Assuming this maps to IotCanInfo
                    handled = true
                    val info = IotCanInfo.parseIotCanInfoPayload(payloadData)
                    if (info != null) {
                        _iotCanInfo.value = info
                        Log.d(TAG,"Parsed Full IotCan Data: ${info.toString()}")
                    } else {
                        Log.w(TAG,"Failed to parse Full IotCan Info payload (size: $actualPayloadSize).")
                    }
                }

                else -> {  Log.w(TAG,("Received Full Frame with Unknown Type: 0x${rspType.toHexString()}")) }
            }
            if (!handled && payloadData.isNotEmpty()) {
                Log.w(TAG,("Unhandled Full Payload (Type 0x${rspType.toHexString()}, Size $actualPayloadSize): ${payloadData.toHexString()}"))
            }

        } else {
            // --- Partial Data Frame ---
            Log.d(TAG,"Detected Partial Data Frame (StartPos = $rspStartPos).") // Changed level to Debug
            var handled = false

            when (rspType) {
                CMD_ID_CONTROLLER -> {
                    handled = true
                    Log.d(TAG,"Partial Controller Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")

                    if (applyControllerTelemetrySegment(payloadData, rspStartPos)) {
                        Log.d(TAG,"Applied controller telemetry segment.")
                    } else {
                        // --- Apply Partial Update ---
                        // 1. Get the current state (make a copy to ensure StateFlow emission)
                        val currentInfo = _controllerInfo.value?.copy(
                            // Deep copy arrays if necessary, although updatePartial modifies the copy directly
                            gearSpeedLimit = _controllerInfo.value?.gearSpeedLimit?.copyOf() ?: ByteArray(10),
                            gearCurrentLimit = _controllerInfo.value?.gearCurrentLimit?.copyOf() ?: ByteArray(10)
                        )?.also { it.rawData = _controllerInfo.value?.rawData }

                        if (currentInfo != null) {
                            // 2. Apply the update to the copy
                            val success = currentInfo.updatePartial(payloadData, rspStartPos)
                            if (success) {
                                // 3. Emit the modified copy
                                _controllerInfo.value = currentInfo
                                Log.d(TAG,"Successfully applied partial update to ControllerInfo.")
                            } else {
                                Log.w(TAG,"Failed to apply partial update to ControllerInfo (offset $rspStartPos).")
                            }
                        } else {
                            // Cannot apply partial update if we don't have the full data yet.
                            Log.w(TAG, "Received partial Controller update (offset $rspStartPos), but no full data available to update.")
                        }
                    }
                    // -------------------------
                }
                CMD_ID_METER -> {
                    handled = true
                    Log.d(TAG,"Partial Meter Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")

                    // --- Apply Partial Update ---
                    // 1. Get current state (make a copy)
                    val currentInfo = _meterInfo.value?.copy() // MeterInfo has no arrays needing deep copy currently

                    if (currentInfo != null) {
                        // 2. Apply update to the copy
                        val success = currentInfo.updatePartial(payloadData, rspStartPos)
                        if (success) {
                            // 3. Emit modified copy
                            _meterInfo.value = currentInfo
                            Log.d(TAG,"Successfully applied partial update to MeterInfo.")
                        } else {
                            Log.w(TAG,"Failed to apply partial update to MeterInfo (offset $rspStartPos).")
                        }
                    } else {
                        Log.w(TAG, "Received partial Meter update (offset $rspStartPos), but no full data available to update.")
                    }
                    // -------------------------
                }
                CMD_ID_PERSONALIZED -> {
                    handled = true
                    Log.d(TAG,"Partial Personalized Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")

                    val currentInfo = _personalizedInfo.value?.copy(
                        motorStartingAngle = _personalizedInfo.value?.motorStartingAngle?.copyOf() ?: ShortArray(10),
                        accelerationSettings = _personalizedInfo.value?.accelerationSettings?.copyOf() ?: ByteArray(10),
                        gearSpeedLimit = _personalizedInfo.value?.gearSpeedLimit?.copyOf() ?: ByteArray(10),
                        gearCurrentLimit = _personalizedInfo.value?.gearCurrentLimit?.copyOf() ?: ByteArray(10)
                    )

                    if (currentInfo != null) {
                        val success = currentInfo.updatePartial(payloadData, rspStartPos)
                        if (success) {
                            _personalizedInfo.value = currentInfo
                            Log.d(TAG,"Successfully applied partial update to PersonalizedInfo.")
                        } else {
                            Log.w(TAG,"Failed to apply partial update to PersonalizedInfo (offset $rspStartPos).")
                        }
                    } else {
                        Log.w(TAG, "Received partial Personalized update (offset $rspStartPos), but no full data available to update.")
                    }
                }
                // Add cases for other types if partial responses are expected/handled
                else -> {
                    Log.w(TAG,("Received Partial Frame with Unknown Type: 0x${rspType.toHexString()}"))
                }
            }
            if (!handled && payloadData.isNotEmpty()) {
                Log.w(TAG,"Unhandled Partial Payload (Type 0x${rspType.toHexString()}, Start=$rspStartPos, Size $actualPayloadSize): ${payloadData.toHexString()}")
            }
        }
    } // End parseCompleteFrame

    private fun applyControllerTelemetrySegment(payloadData: ByteArray, rspStartPos: Int): Boolean {
        if (rspStartPos != RideTelemetrySample.CONTROLLER_TELEMETRY_OFFSET) return false
        val sample = RideTelemetrySample.fromControllerSegment(
            payload = payloadData,
            batteryInfo = _batteryInfo.value,
            startedAtMs = rideLogStartedAtMs,
            source = "A3_SEGMENT"
        ) ?: return false

        val current = _controllerInfo.value
        val updated = current?.copy(
            gearSpeedLimit = current.gearSpeedLimit.copyOf(),
            gearCurrentLimit = current.gearCurrentLimit.copyOf()
        ) ?: ControllerInfo()

        updated.rawData = current?.rawData?.copyOf()
        updated.soc = sample.socPercent
        updated.singleMileage = sample.singleMileageRaw
        updated.totalMileage = sample.totalMileageRaw
        updated.emainingMileage = sample.remainingMileageRaw
        updated.cadence = sample.cadenceRpm
        updated.moment = sample.torqueRaw
        updated.speed = sample.speedRaw
        updated.electricCurrent = sample.currentRaw
        updated.voltage = sample.voltageRaw
        updated.controllerTemperature = sample.controllerTempC
        updated.motorTemperature = sample.motorTempC
        updated.boostState = sample.boostState
        updated.speedLimit = sample.speedLimitRaw
        updated.wheelDiameter = sample.wheelDiameterRaw
        updated.tireCircumference = sample.tireCircumferenceRaw
        updated.calories = sample.caloriesRaw
        updated.currentGear = sample.currentGear
        updated.totalGear = sample.totalGear
        updated.wheelSpeed = sample.wheelSpeed
        updated.wheelCounter = sample.wheelCounter
        updated.lastTestSenserTime = sample.lastSensorTime
        updated.crankCadencePulseCounter = sample.crankPulseCounter
        updated.motorVariableSpeedMasterGear = sample.motorVariableSpeedMasterGear
        updated.motorSpeedCurrentGear = sample.motorSpeedCurrentGear

        val raw = updated.rawData
        if (raw != null && raw.size >= rspStartPos + payloadData.size) {
            System.arraycopy(payloadData, 0, raw, rspStartPos, payloadData.size)
        }

        _controllerInfo.value = updated
        handleRideTelemetrySample(sample)
        return true
    }

    private fun handleRideTelemetrySample(sample: RideTelemetrySample) {
        var wrote = false
        var writeError: Exception? = null

        synchronized(rideLogLock) {
            val writer = rideLogWriter
            if (writer != null) {
                try {
                    writer.write(sample.toCsvLine())
                    writer.newLine()
                    writer.flush()
                    val state = _rideLogState.value
                    _rideLogState.value = state.copy(sampleCount = state.sampleCount + 1)
                    wrote = true
                } catch (e: Exception) {
                    writeError = e
                }
            }
        }

        writeError?.let {
            Log.e(TAG, "Ride log write failed: ${it.message}", it)
            addLog("Ride logging write failed: ${it.message}")
            stopRideLogging()
            return
        }

        if (!wrote) return
        if (sample.timestampMs - lastRideTelemetryLogMs >= RIDE_LOG_STATUS_INTERVAL_MS) {
            lastRideTelemetryLogMs = sample.timestampMs
            addLog(sample.summary())
        }
    }

    private fun logWriteAckIfPresent(frame: ByteArray, payloadData: ByteArray) {
        if (frame.size < RSP_MIN_HEADER_SIZE || payloadData.isEmpty()) return

        val command = payloadData[0]
        if (command != WRITE_INDICATOR && command != CERTIFICATION_INDICATOR) return

        val target = frame[3]
        val address = if (payloadData.size >= 2) payloadData[1] else frame[6]
        val result = if (payloadData.size >= 3) payloadData[2].toInt() and 0xFF else payloadData[0].toInt() and 0xFF
        val operation = when (command) {
            WRITE_INDICATOR -> "WRITE"
            CERTIFICATION_INDICATOR -> "CERT"
            else -> "CMD"
        }
        val statusText = if (result == 0) "OK" else "ERROR"

        Log.i(TAG, "WRITE RESPONSE raw: ${frame.toHexString()}")
        Log.i(TAG, "AA55 $operation ACK: target=0x${target.toHexString()}, address=0x${address.toHexString()}, result=$result ($statusText)")

        if (command == WRITE_INDICATOR && result != 0) {
            Log.e(TAG, "Write to target=0x${target.toHexString()} was rejected by controller. Result=$result")
        }
    }

    private fun logPendingPersonalizedReadback(info: PersonalizedInfo) {
        val pending = pendingPersonalizedWrite ?: return
        if (pending.targetType != CMD_ID_PERSONALIZED || pending.startPosition != 0x41.toByte()) return

        val raw = info.rawData
        if (raw == null || raw.size < 115) {
            Log.w(TAG, "Cannot compare personalized readback: raw payload missing or too short.")
            return
        }

        val actual = raw.sliceArray(65 until 115)
        val expected = pending.payload
        val diffs = expected.indices.filter { expected[it] != actual[it] }

        if (diffs.isEmpty()) {
            Log.i(TAG, "Personalized readback MATCH: controller returned the same 50 bytes that were written.")
            pendingPersonalizedWrite = null
        } else {
            val firstDiffs = diffs.take(12).joinToString(", ") { index ->
                val offset = 65 + index
                "0x${String.format("%02X", offset)} exp=${expected[index].toHexString()} got=${actual[index].toHexString()}"
            }
            Log.w(
                TAG,
                "Personalized readback MISMATCH: ${diffs.size}/50 bytes differ. First diffs: $firstDiffs"
            )
            Log.w(TAG, "Expected A9[0x41..0x72]: ${expected.toHexString()}")
            Log.w(TAG, "Actual   A9[0x41..0x72]: ${actual.toHexString()}")
        }
    }

    private fun maxWritePayloadSize(): Int = (negotiatedMtu - 3).coerceAtLeast(20)

    private fun describeProperties(properties: Int): String {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names.add("INDICATE")
        return if (names.isEmpty()) "0x${properties.toString(16)}" else names.joinToString("|")
    }

    private fun logDiscoveredGatt(gatt: BluetoothGatt) {
        gatt.services.forEach { service ->
            Log.d(TAG, "GATT service ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                Log.d(TAG, "  characteristic ${characteristic.uuid} props=${describeProperties(characteristic.properties)}")
            }
        }
    }


    // --- Permissions ---
    private fun hasPermission(permission: String): Boolean { return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) }
        else { listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION) }
        return requiredPermissions.all { hasPermission(it) }
    }

    // --- Cleanup ---
    fun cleanup() {
        Log.d(TAG, "Cleaning up BleRepository")
        stopRideLogging()
        disconnect()
        scanJob?.cancel()
        coroutineScope.cancel()
    }
    private fun Byte.toHexString(): String = String.format("%02X", this)



    // Helper extension function for logging byte arrays
    fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }

} // End of BleRepository class
