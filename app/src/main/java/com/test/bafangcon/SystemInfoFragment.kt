package com.test.bafangcon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SystemInfoFragment : Fragment() {

    private var _view: View? = null
    private val root get() = _view!!
    private val viewModel: DeviceViewModel by activityViewModels()

    private lateinit var adapter: SystemInfoAdapter
    private var toggleDeadBlocksButton: Button? = null

    // Bloki, które w logu (aa55_raw_*.log) były całe zerowe = martwe.
    // Domyślnie ukryte, odkrywane przyciskiem na dole ekranu System.
    private var showDeadBlocks = false

    private var currentBattery: BatteryInfo? = null
    private var currentSensor: SensorInfo? = null
    private var currentIotConfig: IotConfigInfo? = null
    private var currentIotCan: IotCanInfo? = null
    private var currentController: ControllerInfo? = null
    private var currentMeter: MeterInfo? = null
    private var currentCanBleDebug = CanBleDebugInfo()
    private var currentAa55RawStats = Aa55RawStats()
    private var currentBleRawStats = BleRawNotificationStats()
    private var currentPersonalized: PersonalizedInfo? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_system_info, container, false)
        _view = view

        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.systemInfoRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SystemInfoAdapter()
        recyclerView.adapter = adapter

        toggleDeadBlocksButton = view.findViewById<Button>(R.id.toggleDeadBlocksButton).apply {
            setOnClickListener {
                showDeadBlocks = !showDeadBlocks
                updateToggleButtonText()
                rebuildSections()
            }
        }
        updateToggleButtonText()

        return view
    }

    private fun updateToggleButtonText() {
        toggleDeadBlocksButton?.text =
            if (showDeadBlocks) "Ukryj nieaktywne bloki" else "Pokaż nieaktywne bloki"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.batteryInfo.collect { currentBattery = it; rebuildSections() } }
                launch { viewModel.sensorInfo.collect { currentSensor = it; rebuildSections() } }
                launch { viewModel.iotConfigInfo.collect { currentIotConfig = it; rebuildSections() } }
                launch { viewModel.iotCanInfo.collect { currentIotCan = it; rebuildSections() } }
                launch { viewModel.controllerInfo.collect { currentController = it; rebuildSections() } }
                launch { viewModel.meterInfo.collect { currentMeter = it; rebuildSections() } }
                launch { viewModel.personalizedInfo.collect { currentPersonalized = it; rebuildSections() } }
                launch { viewModel.canBleDebug.collect { currentCanBleDebug = it; rebuildSections() } }
                launch { viewModel.aa55RawStats.collect { currentAa55RawStats = it; rebuildSections() } }
                launch { viewModel.bleRawNotificationStats.collect { currentBleRawStats = it; rebuildSections() } }
            }
        }

        viewModel.requestControllerInfo()
        viewModel.requestBatteryInfo()
        viewModel.requestSensorInfo()
        viewModel.requestConfigInfo()
        viewModel.requestCanInfo()

        // Auto-refresh controller data co 3s for live values
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(3000)
                    viewModel.requestControllerInfo()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toggleDeadBlocksButton = null
        _view = null
    }

    private fun rebuildSections() {
        val sections = mutableListOf<SystemInfoSection>()

        if (currentController != null) {
            val c = currentController!!
            val identityItems = mutableListOf(
                SystemInfoItem("Hardware", c.hardVersion.cleanValue()),
                SystemInfoItem("Software", c.softVersion.cleanValue()),
                SystemInfoItem("Model", c.model.cleanValue()),
                SystemInfoItem("Serial", c.sn.cleanValue()),
                SystemInfoItem("Customer No", c.customerNo.cleanValue()),
                SystemInfoItem("Manufacturer", c.manufacturer.cleanValue()),
                SystemInfoItem("Protocol Ver", "0x${String.format("%02X", c.controllerProtocolVersion)}")
            )
            sections.add(SystemInfoSection("Controller Identity (A3)", identityItems))

            val liveItems = mutableListOf(
                SystemInfoItem("SOC", "${c.soc}%"),
                SystemInfoItem("Speed", String.format("%.1f km/h", c.speed * 0.01)),
                SystemInfoItem("Current", String.format("%.2f A", c.electricCurrent * 0.01)),
                SystemInfoItem("Voltage", String.format("%.2f V", c.voltage * 0.01)),
                SystemInfoItem("Power", String.format("%.1f W", c.voltage * 0.01 * c.electricCurrent * 0.01)),
                SystemInfoItem("Cadence", "${c.cadence} RPM"),
                SystemInfoItem("Torque Raw", c.moment.toString()),
                SystemInfoItem("Controller Temp", "${c.controllerTemperature} °C"),
                SystemInfoItem("Motor Temp", if (c.motorTemperature > 200) "N/A" else "${c.motorTemperature} °C"),
                SystemInfoItem("Boost", if (c.boostState != 0) "ON" else "OFF"),
                SystemInfoItem("Gear", "${c.currentGear}/${c.totalGear}")
            )
            sections.add(SystemInfoSection("Controller Live (A3)", liveItems))

            val telemetryItems = mutableListOf(
                SystemInfoItem("Single Mileage Raw", c.singleMileage.toString()),
                SystemInfoItem("Total Mileage Raw", c.totalMileage.toString()),
                SystemInfoItem("Remaining Mileage Raw", c.emainingMileage.toString()),
                SystemInfoItem("Calories", c.calories.toString()),
                SystemInfoItem("Wheel Speed", c.wheelSpeed.toString()),
                SystemInfoItem("Wheel Counter", c.wheelCounter.toString()),
                SystemInfoItem("Last Sensor Time", c.lastTestSenserTime.toString()),
                SystemInfoItem("Crank Pulse Counter", c.crankCadencePulseCounter.toString()),
                SystemInfoItem("Motor Var Speed Master Gear", c.motorVariableSpeedMasterGear.toString()),
                SystemInfoItem("Motor Speed Current Gear", c.motorSpeedCurrentGear.toString())
            )
            sections.add(SystemInfoSection("Controller Telemetry (A3 160..207)", telemetryItems))

            val settingsItems = mutableListOf(
                SystemInfoItem("Speed Limit", String.format("%.1f km/h", c.speedLimit * 0.01)),
                SystemInfoItem("Wheel Diameter", "${c.wheelDiameter} \""),
                SystemInfoItem("Tire Circumference", "${c.tireCircumference} mm"),
                SystemInfoItem("Cruise Control", c.cruiseControl.toString()),
                SystemInfoItem("Boot Default Gear", c.bootDefaultGear.toString()),
                SystemInfoItem("Boot Default Gear Value", c.bootDefaultGearValue.toString()),
                SystemInfoItem("Motor Start Angle", c.motorStartingAngle.toString()),
                SystemInfoItem("Acceleration", c.accelerationSettings.toString()),
                SystemInfoItem("Gear Speed Limits", c.gearSpeedLimit.joinToString(", ") { "${it.toInt() and 0xFF}%" }),
                SystemInfoItem("Gear Current Limits", c.gearCurrentLimit.joinToString(", ") { "${it.toInt() and 0xFF}%" }),
                SystemInfoItem("Buzzer", c.buzzerSwitch.toString())
            )
            sections.add(SystemInfoSection("Controller Settings (A3)", settingsItems))

        }

        if (currentMeter != null) {
            val m = currentMeter!!
            val items = mutableListOf(
                SystemInfoItem("Model", m.model),
                SystemInfoItem("Hardware", m.hardVersion),
                SystemInfoItem("Firmware", m.softVersion),
                SystemInfoItem("Serial", m.sn),
                SystemInfoItem("Total Mileage", "${m.totalMileage} km"),
                SystemInfoItem("Max Speed", String.format("%.1f km/h", m.maxSpeed * 0.1)),
                SystemInfoItem("Avg Speed", String.format("%.1f km/h", m.averageSpeed * 0.1))
            )
            sections.add(SystemInfoSection("Meter (A5)", items))
        }

        if (currentPersonalized != null) {
            val p = currentPersonalized!!
            val items = mutableListOf(
                SystemInfoItem("Protocol Ver", "0x${String.format("%02X", p.controllerProtocolVersion)}"),
                SystemInfoItem("Start Angle", p.motorStartingAngle.joinToString(", ") { it.toString() }),
                SystemInfoItem("Speed Limits", p.gearSpeedLimit.joinToString(", ") { "${it.toInt() and 0xFF}%" }),
                SystemInfoItem("Current Limits", p.gearCurrentLimit.joinToString(", ") { "${it.toInt() and 0xFF}%" }),
                SystemInfoItem("Accel Set", p.accelerationSettings.joinToString(", ") { "${it.toInt() and 0xFF}" })
            )
            sections.add(SystemInfoSection("Personalized (A9)", items))
        }

        if (showDeadBlocks && currentBattery != null) {
            val b = currentBattery!!
            val items = mutableListOf(
                SystemInfoItem("Model", b.model),
                SystemInfoItem("HW", b.hardVersion),
                SystemInfoItem("FW", b.softVersion),
                SystemInfoItem("Serial", b.sn),
                SystemInfoItem("Manufacturer", b.manufacturer),
                SystemInfoItem("Capacity", "${b.totalCapacity} Ah"),
                SystemInfoItem("Residual", "${b.residualCapacity} Ah"),
                SystemInfoItem("SOC", "${b.relativeCapacityPercent}%"),
                SystemInfoItem("Voltage", String.format("%.2f V", b.totalVoltage * 0.01)),
                SystemInfoItem("Current", String.format("%.2f A", b.electricCurrent * 0.01)),
                SystemInfoItem("Temp", "${b.temperature} °C"),
                SystemInfoItem("Cycles", b.cycles.toString()),
                SystemInfoItem("Design Cap", "${b.designCapacity} Ah"),
                SystemInfoItem("Max Charge V", String.format("%.2f V", b.maxChargeVoltage * 0.01))
            )
            sections.add(SystemInfoSection("Battery", items))
        }

        if (showDeadBlocks && currentSensor != null) {
            val s = currentSensor!!
            val items = mutableListOf(
                SystemInfoItem("Model", s.model),
                SystemInfoItem("HW", s.hardVersion),
                SystemInfoItem("FW", s.softVersion),
                SystemInfoItem("Serial", s.sn),
                SystemInfoItem("Manufacturer", s.manufacturer),
                SystemInfoItem("Voltage Signal", s.voltageSignal.toString()),
                SystemInfoItem("Cadence", s.cadence.toString())
            )
            sections.add(SystemInfoSection("Torque Sensor", items))
        }

        if (showDeadBlocks && currentIotConfig != null) {
            val c = currentIotConfig!!
            val items = mutableListOf(
                SystemInfoItem("Model", c.model),
                SystemInfoItem("FW", c.softVersion),
                SystemInfoItem("Serial", c.sn),
                SystemInfoItem("ICCID", c.iccid),
                SystemInfoItem("IMEI", c.imei),
                SystemInfoItem("Server", c.server),
                SystemInfoItem("Port", c.port),
                SystemInfoItem("APN", c.apn),
                SystemInfoItem("BLE Name", c.bleName)
            )
            sections.add(SystemInfoSection("IOT Config (A1)", items))
        }

        if (showDeadBlocks && currentIotCan != null) {
            val c = currentIotCan!!
            val items = mutableListOf(
                SystemInfoItem("BLE Model", c.bleModel.toString()),
                SystemInfoItem("Network Model", c.networkModel.toString()),
                SystemInfoItem("BLE Report Model", c.bleReportModel.toString()),
                SystemInfoItem("BLE Interval", "${c.bleReportInterval} ms"),
                SystemInfoItem("Network Interval", "${c.networkReportInterval} ms"),
                SystemInfoItem("Custom Data", c.customizeData),
                SystemInfoItem("CAN Filters", c.canFilters.joinToString(", "))
            )
            sections.add(SystemInfoSection("IOT CAN (A2)", items))
        }

        val debug = currentCanBleDebug
        val canBleItems = listOf(
            SystemInfoItem("Service Found", when (debug.serviceFound) {
                true -> "YES"
                false -> "NO"
                null -> "UNKNOWN"
            }),
            SystemInfoItem("Transport State", debug.transportState),
            SystemInfoItem("Handshake State", debug.handshakeState),
            SystemInfoItem("Notifications", debug.notifications.toString()),
            SystemInfoItem("Frames Accepted", debug.framesAccepted.toString()),
            SystemInfoItem("Frames Filtered", debug.framesFiltered.toString()),
            SystemInfoItem("Decrypt Failures", debug.decryptFailures.toString())
        )
        sections.add(SystemInfoSection("CAN BLE Debug", canBleItems))

        if (debug.bleServicesDebug.isNotEmpty()) {
            val serviceItems = mutableListOf<SystemInfoItem>()
            debug.bleServicesDebug.forEachIndexed { i, entry ->
                serviceItems.add(SystemInfoItem("Service ${i + 1}", entry.serviceUuid))
                entry.characteristicUuids.forEach { charUuid ->
                    serviceItems.add(SystemInfoItem("  Char", charUuid))
                }
            }
            sections.add(SystemInfoSection("BLE Services Debug", serviceItems))
        }

        val aa55 = currentAa55RawStats
        val aa55Items = mutableListOf(
            SystemInfoItem("Logging", if (aa55.isLogging) "ON" else "OFF"),
            SystemInfoItem("RX Frames", aa55.rxFrames.toString()),
            SystemInfoItem("TX Frames", aa55.txFrames.toString()),
            SystemInfoItem("Unknown Frames", aa55.unknownFrames.toString()),
            SystemInfoItem("Last RX Command", aa55.lastRxCommand),
            SystemInfoItem("Last TX Command", aa55.lastTxCommand),
            SystemInfoItem("Last Frame Hex", aa55.lastFrameHex),
            SystemInfoItem("File Write Errors", aa55.fileWriteErrors.toString())
        )
        if (aa55.currentLogFilePath.isNotEmpty()) {
            aa55Items.add(1, SystemInfoItem("Log File", aa55.currentLogFilePath))
        }
        sections.add(SystemInfoSection("AA55 Raw Debug", aa55Items))

        val bleRaw = currentBleRawStats
        val bleRawItems = mutableListOf(
            SystemInfoItem("Logging", if (bleRaw.loggingEnabled) "ON" else "OFF"),
            SystemInfoItem("Total Notifications", bleRaw.totalNotifications.toString()),
            SystemInfoItem("Total Bytes", bleRaw.totalBytes.toString()),
            SystemInfoItem("Unique UUIDs", bleRaw.uniqueUuids.size.toString()),
            SystemInfoItem("Last UUID", bleRaw.lastUuid),
            SystemInfoItem("Last Length", bleRaw.lastLength.toString()),
            SystemInfoItem("Last Raw Hex", bleRaw.lastRawHex),
            SystemInfoItem("File Write Errors", bleRaw.fileWriteErrors.toString())
        )
        if (bleRaw.currentLogFilePath.isNotEmpty()) {
            bleRawItems.add(1, SystemInfoItem("Log File", bleRaw.currentLogFilePath))
        }
        sections.add(SystemInfoSection("BLE Raw Debug", bleRawItems))

        if (sections.isEmpty()) {
            sections.add(SystemInfoSection("Info", listOf(SystemInfoItem("No data", "Requesting..."))))
        }

        adapter.updateData(sections)
    }

    private fun String.cleanValue(): String {
        val cleaned = replace("\u0000", "").trim()
        return cleaned.ifBlank { "N/A" }
    }
}
