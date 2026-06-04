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
import kotlin.collections.ArrayList

class GearsInfoFragment : Fragment() {

    private var _binding: FragmentControllerInfoBinding ? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    private val displayList = ArrayList<InfoItem>()
    private var editableInfo: PersonalizedInfo? = null
    private var currentGearCount = 5
    private var currentGearIndices: IntArray = GEAR_INDICES[5]!!
    private var currentGearLabels: List<String> = GEAR_LABELS[5]!!

    companion object {
        const val KEY_PROTOCOL = "Protocol Version"
        const val KEY_GLOBAL_ACCEL = "Global Acceleration (s)"
        private const val TAG = "GearsInfoFragment"

        // Gear index maps: number of gear levels -> which indices in the 10-element array are valid
        private val GEAR_INDICES = mapOf(
            3 to intArrayOf(3, 5, 9),
            4 to intArrayOf(1, 3, 6, 9),
            5 to intArrayOf(2, 4, 6, 8, 9),
            9 to intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        )

        private val GEAR_LABELS = mapOf(
            3 to listOf("Eco", "Normal", "Sport"),
            4 to listOf("Eco", "Tour", "Sport", "Turbo"),
            5 to listOf("E", "T", "S", "S+", "B"),
            9 to listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        )

        fun keyAngle(label: String) = "Motor Angle $label"
        fun keySpeed(label: String) = "Assist Ratio $label"
        fun keyCurrent(label: String) = "Current Limit $label"
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
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Personalized Gear Settings"
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.meterInfo.collect { meter ->
                        val count = meter?.totalGear ?: 5
                        if (count in GEAR_INDICES) {
                            currentGearCount = count
                            currentGearIndices = GEAR_INDICES[count]!!
                            currentGearLabels = GEAR_LABELS[count]!!
                        } else {
                            currentGearCount = 5
                            currentGearIndices = GEAR_INDICES[5]!!
                            currentGearLabels = GEAR_LABELS[5]!!
                        }
                        editableInfo?.let { populateList(it) }
                    }
                }
                launch {
                    viewModel.personalizedInfo.collect { currentPersonalizedInfoFromVm ->
                        if (currentPersonalizedInfoFromVm != null) {
                            editableInfo = currentPersonalizedInfoFromVm.copy(
                                motorStartingAngle = currentPersonalizedInfoFromVm.motorStartingAngle.copyOf(),
                                accelerationSettings = currentPersonalizedInfoFromVm.accelerationSettings.copyOf(),
                                gearSpeedLimit = currentPersonalizedInfoFromVm.gearSpeedLimit.copyOf(),
                                gearCurrentLimit = currentPersonalizedInfoFromVm.gearCurrentLimit.copyOf()
                            )
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

    private fun updateEditableInfo(key: String, newValue: String) {
        if (editableInfo == null) {
            Log.e(TAG, "updateEditableInfo called but editableInfo is null!")
            return
        }

        try {
            when {
                key == KEY_GLOBAL_ACCEL -> {
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 255)?.toByte()
                    if (byteValue != null) {
                        editableInfo?.accelerationSettings?.set(0, byteValue)
                        for (i in 1 until 10) {
                            editableInfo?.accelerationSettings?.set(i, 0.toByte())
                        }
                    }
                }
                key.startsWith("Motor Angle") -> {
                    val label = key.removePrefix("Motor Angle ").trim()
                    val uiIndex = currentGearLabels.indexOf(label)
                    val shortValue = newValue.toShortOrNull()
                    if (uiIndex != -1 && uiIndex < currentGearIndices.size && shortValue != null) {
                        val arrayIndex = currentGearIndices[uiIndex]
                        editableInfo?.motorStartingAngle?.set(arrayIndex, shortValue)
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
                key.startsWith("Assist Ratio") -> {
                    val label = key.removePrefix("Assist Ratio ").trim()
                    val uiIndex = currentGearLabels.indexOf(label)
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 100)?.toByte()
                    if (uiIndex != -1 && uiIndex < currentGearIndices.size && byteValue != null) {
                        val arrayIndex = currentGearIndices[uiIndex]
                        editableInfo?.gearSpeedLimit?.set(arrayIndex, byteValue)
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
                key.startsWith("Current Limit") -> {
                    val label = key.removePrefix("Current Limit ").trim()
                    val uiIndex = currentGearLabels.indexOf(label)
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 100)?.toByte()
                    if (uiIndex != -1 && uiIndex < currentGearIndices.size && byteValue != null) {
                        val arrayIndex = currentGearIndices[uiIndex]
                        editableInfo?.gearCurrentLimit?.set(arrayIndex, byteValue)
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number format error during update for $key: $newValue")
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
                Toast.makeText(requireContext(), "No data to update", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val infoToUpdate = editableInfo!!
            var isValid = true
            val validationErrors = mutableListOf<String>()

            for (uiIndex in currentGearIndices.indices) {
                val arrayIndex = currentGearIndices[uiIndex]
                val label = currentGearLabels.getOrElse(uiIndex) { "Lv${uiIndex + 1}" }
                val angle = infoToUpdate.motorStartingAngle.getOrElse(arrayIndex) { 0 }
                if (angle !in 0..3600) {
                    isValid = false
                    validationErrors.add("Angle $label must be 0-3600 (0-360.0°).")
                }
                val speed = infoToUpdate.gearSpeedLimit.getOrElse(arrayIndex) { 0 }.toInt() and 0xFF
                if (speed !in 0..100) {
                    isValid = false
                    validationErrors.add("Assist Ratio $label must be 0-100%.")
                }
                val current = infoToUpdate.gearCurrentLimit.getOrElse(arrayIndex) { 0 }.toInt() and 0xFF
                if (current !in 0..100) {
                    isValid = false
                    validationErrors.add("Current Limit $label must be 0-100%.")
                }
            }
            val globalAccel = infoToUpdate.accelerationSettings.getOrElse(0) { 0 }.toInt() and 0xFF
            if (globalAccel !in 0..100) {
                isValid = false
                validationErrors.add("Global Acceleration must be 0-100.")
            }

            if (isValid) {
                val raw = viewModel.controllerInfo.value?.rawData?.copyOf()
                if (raw == null || raw.size < 237) {
                    Toast.makeText(requireContext(), "No Controller data. Press Controller button first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val angleIdx = if (currentGearIndices.isNotEmpty()) currentGearIndices[0] else 0
                val angle = infoToUpdate.motorStartingAngle.getOrElse(angleIdx) { 0 }.toInt() and 0xFFFF
                raw[211] = (angle and 0xFF).toByte()
                raw[212] = ((angle shr 8) and 0xFF).toByte()
                raw[213] = infoToUpdate.accelerationSettings.getOrElse(0) { 0 }
                for (i in 0..9) {
                    raw[214 + i] = infoToUpdate.gearSpeedLimit.getOrElse(i) { 0 }
                    raw[224 + i] = infoToUpdate.gearCurrentLimit.getOrElse(i) { 0 }
                }

                val partial = raw.copyOfRange(188, 234) // 46 bytes
                Log.i(TAG, "Writing Gears settings through A3 partial (46 bytes at offset 188)")
                viewModel.updateControllerPartial(partial, 188)
                Toast.makeText(requireContext(), "Wysłano", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                val errorMsg = "Validation failed:\n${validationErrors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun populateList(info: PersonalizedInfo) {
        displayList.clear()

        displayList.add(InfoItem(KEY_PROTOCOL, info.controllerProtocolVersion.toString()))
        displayList.add(InfoItem(KEY_GLOBAL_ACCEL,
            (info.accelerationSettings.getOrElse(0) { 0 }.toInt() and 0xFF).toString(),
            EditableType.EDIT_TEXT_NUMBER))

        for (uiIndex in currentGearIndices.indices) {
            val arrayIndex = currentGearIndices[uiIndex]
            val label = currentGearLabels.getOrElse(uiIndex) { "Lv${uiIndex + 1}" }
            displayList.add(InfoItem(keyAngle(label), info.motorStartingAngle.getOrElse(arrayIndex) { 0 }.toString(), EditableType.EDIT_TEXT_NUMBER))
            displayList.add(InfoItem(keySpeed(label), (info.gearSpeedLimit.getOrElse(arrayIndex) { 0 }.toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
            displayList.add(InfoItem(keyCurrent(label), (info.gearCurrentLimit.getOrElse(arrayIndex) { 0 }.toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
        }

        infoAdapter.submitList(ArrayList(displayList))
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
