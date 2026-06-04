package com.test.bafangcon

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.test.bafangcon.databinding.FragmentControllerInfoBinding
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

class ControllerInfoFragment : Fragment() {

    private var _binding: FragmentControllerInfoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    private val displayList = ArrayList<InfoItem>()
    private var editableInfo: ControllerInfo? = null

    companion object {
        private const val TAG = "ControllerInfoFragment"
        const val KEY_TIRE_CIRCUMFERENCE = "Tire Circumference (mm)"
        const val KEY_MOTOR_ANGLE = "Motor Start Angle (0.1°)"
        const val KEY_ACCELERATION = "Acceleration Setting"
        fun keySpeedLimit(index: Int) = "Speed Lv$index"
        fun keyCurrentLimit(index: Int) = "Current Lv$index"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControllerInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Controller Settings"
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.controllerInfo.collect { current ->
                    if (current != null) {
                        editableInfo = current.copy(
                            gearSpeedLimit = current.gearSpeedLimit.copyOf(),
                            gearCurrentLimit = current.gearCurrentLimit.copyOf()
                        ).also { it.rawData = current.rawData }
                        populateList(editableInfo!!)
                    } else {
                        editableInfo = null
                        displayList.clear()
                        infoAdapter.submitList(emptyList())
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        infoAdapter = InfoAdapter { position, newValue ->
            if (editableInfo == null) return@InfoAdapter
            if (position >= 0 && position < displayList.size) {
                val item = displayList[position]
                item.value = newValue
                updateEditableInfo(item.key, newValue)
            }
        }
        binding.infoRecyclerView.apply {
            adapter = infoAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.popBackStack()
        }

        binding.updateButton.setOnClickListener {
            hideKeyboard()
            if (editableInfo == null) {
                Toast.makeText(requireContext(), "No data loaded to update", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val info = editableInfo!!
            val raw = info.rawData ?: run {
                Toast.makeText(requireContext(), "No raw data available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (raw.size < 237) {
                Toast.makeText(requireContext(), "Raw data too short", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var isValid = true
            val errors = mutableListOf<String>()

            if (info.tireCircumference !in 0..3000) {
                isValid = false; errors.add("Tire Circ: 0-3000 mm")
            }
            if (info.motorStartingAngle !in 0..3600) {
                isValid = false; errors.add("Motor Angle: 0-3600 (0-360.0°)")
            }
            if (info.accelerationSettings !in 0..100) {
                isValid = false; errors.add("Acceleration: 0-100")
            }
            for (i in 0..9) {
                val speed = info.gearSpeedLimit[i].toInt() and 0xFF
                if (speed !in 0..100) {
                    isValid = false; errors.add("Speed Lv$i: 0-100")
                }
                val current = info.gearCurrentLimit[i].toInt() and 0xFF
                if (current !in 0..100) {
                    isValid = false; errors.add("Current Lv$i: 0-100")
                }
            }

            if (!isValid) {
                Toast.makeText(requireContext(), errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val modified = raw.copyOf()
            modified[188] = (info.tireCircumference and 0xFF).toByte()
            modified[189] = ((info.tireCircumference shr 8) and 0xFF).toByte()
            modified[211] = (info.motorStartingAngle and 0xFF).toByte()
            modified[212] = ((info.motorStartingAngle shr 8) and 0xFF).toByte()
            modified[213] = info.accelerationSettings.toByte()
            for (i in 0..9) {
                modified[214 + i] = info.gearSpeedLimit[i]
                modified[224 + i] = info.gearCurrentLimit[i]
            }

            val partial = modified.copyOfRange(188, 234) // 46 bytes
            Log.i(TAG, "Writing controller partial update (46 bytes at offset 188)")
            viewModel.updateControllerPartial(partial, 188)
            Toast.makeText(requireContext(), "Wysłano", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    private fun populateList(info: ControllerInfo) {
        displayList.clear()

        displayList.add(InfoItem("Hardware Version", info.hardVersion))
        displayList.add(InfoItem("Software Version", info.softVersion))
        displayList.add(InfoItem("Model", info.model))
        displayList.add(InfoItem("Serial Number", info.sn))
        displayList.add(InfoItem("Customer Number", info.customerNo))
        displayList.add(InfoItem("Manufacturer", info.manufacturer))
        displayList.add(InfoItem("Power (%)", info.soc.toString()))
        displayList.add(InfoItem("Single Mileage (0.01km)", String.format(Locale.US, "%.2f", info.singleMileage * 0.01)))
        displayList.add(InfoItem("Total Mileage (0.01km)", String.format(Locale.US, "%.2f", info.totalMileage * 0.01)))
        displayList.add(InfoItem("Remaining Mileage (0.01km)", String.format(Locale.US, "%.2f", info.emainingMileage * 0.01)))
        displayList.add(InfoItem("Cadence (RPM)", info.cadence.toString()))
        displayList.add(InfoItem("Moment (mV)", info.moment.toString()))
        displayList.add(InfoItem("Speed (0.01km/h)", String.format(Locale.US, "%.2f", info.speed * 0.01)))
        displayList.add(InfoItem("Current (0.01A)", String.format(Locale.US, "%.2f", info.electricCurrent * 0.01)))
        displayList.add(InfoItem("Voltage (0.01V)", String.format(Locale.US, "%.2f", info.voltage * 0.01)))
        displayList.add(InfoItem("Controller Temp (°C)", info.controllerTemperature.toString()))
        displayList.add(InfoItem("Motor Temp (°C)", if (info.motorTemperature == 255) "N/A" else info.motorTemperature.toString()))
        displayList.add(InfoItem("Boost State", if (info.boostState == 1) "Active" else "Inactive"))
        displayList.add(InfoItem("Speed Limit (0.01km/h)", String.format(Locale.US, "%.2f", info.speedLimit * 0.01)))
        displayList.add(InfoItem("Wheel Diameter (raw)", info.wheelDiameter.toString()))
        displayList.add(InfoItem(KEY_TIRE_CIRCUMFERENCE, info.tireCircumference.toString(), EditableType.EDIT_TEXT_NUMBER))
        displayList.add(InfoItem("Calories (kcal)", info.calories.toString()))
        displayList.add(InfoItem("Current Gear", info.currentGear.toString()))
        displayList.add(InfoItem("Total Gears", info.totalGear.toString()))
        displayList.add(InfoItem("Wheel Speed (RPM?)", info.wheelSpeed.toString()))
        displayList.add(InfoItem("Wheel Counter", info.wheelCounter.toString()))
        displayList.add(InfoItem("Last Sensor Time (ms?)", info.lastTestSenserTime.toString()))
        displayList.add(InfoItem("Crank Pulse Counter", info.crankCadencePulseCounter.toString()))
        displayList.add(InfoItem("Motor Var Speed Master Gear", info.motorVariableSpeedMasterGear.toString()))
        displayList.add(InfoItem("Motor Speed Current Gear", info.motorSpeedCurrentGear.toString()))
        displayList.add(InfoItem("Cruise Control", if (info.cruiseControl == 1) "On" else "Off"))
        displayList.add(InfoItem("Boot Default Gear Setting", if (info.bootDefaultGear == 1) "On" else "Off"))
        displayList.add(InfoItem("Boot Default Gear Value", info.bootDefaultGearValue.toString()))
        displayList.add(InfoItem(KEY_MOTOR_ANGLE, info.motorStartingAngle.toString(), EditableType.EDIT_TEXT_NUMBER))
        displayList.add(InfoItem(KEY_ACCELERATION, info.accelerationSettings.toString(), EditableType.EDIT_TEXT_NUMBER))
        for (i in 0..9) {
            displayList.add(InfoItem(keySpeedLimit(i), (info.gearSpeedLimit[i].toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
        }
        for (i in 0..9) {
            displayList.add(InfoItem(keyCurrentLimit(i), (info.gearCurrentLimit[i].toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
        }
        displayList.add(InfoItem("Buzzer Switch", if (info.buzzerSwitch == 1) "On" else "Off"))
        displayList.add(InfoItem("Protocol Version", info.controllerProtocolVersion.toString()))

        infoAdapter.submitList(ArrayList(displayList))
    }

    private fun updateEditableInfo(key: String, newValue: String) {
        if (editableInfo == null) return
        try {
            val intVal = newValue.toIntOrNull()
            if (intVal == null) return

            when (key) {
                KEY_TIRE_CIRCUMFERENCE -> editableInfo?.tireCircumference = intVal
                KEY_MOTOR_ANGLE -> editableInfo?.motorStartingAngle = intVal
                KEY_ACCELERATION -> editableInfo?.accelerationSettings = intVal
            }
            for (i in 0..9) {
                if (key == keySpeedLimit(i)) {
                    editableInfo?.gearSpeedLimit?.set(i, intVal.toByte())
                }
                if (key == keyCurrentLimit(i)) {
                    editableInfo?.gearCurrentLimit?.set(i, intVal.toByte())
                }
            }
        } catch (_: NumberFormatException) { }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = requireActivity().currentFocus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
        } else {
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
