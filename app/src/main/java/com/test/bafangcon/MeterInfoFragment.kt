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
import com.test.bafangcon.databinding.FragmentMeterInfoBinding
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

class MeterInfoFragment : Fragment() {

    private var _binding: FragmentMeterInfoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    private val displayList = ArrayList<InfoItem>()
    // Store a temporary, editable copy of the MeterInfo
    private var editableInfo: MeterInfo? = null

    companion object {
        private const val TAG = "MeterInfoFragment"
        private val gearDisplayValues = mapOf(0 to 0, 2 to 1, 4 to 2, 6 to 3, 8 to 4, 9 to 5)
        private val gearProtocolValues = mapOf(0 to 0, 1 to 2, 2 to 4, 3 to 6, 4 to 8, 5 to 9)

        // Keys for identifying items during edit callback
        const val KEY_LIGHT = "Light Status (On=1)" // Keep existing key
        const val KEY_TOTAL_GEAR = "Total Gears (3-5)"
        const val KEY_SPORT_MODEL = "Sport Mode (On=Sport)" // Switch: On -> 2, Off -> 1
        const val KEY_BOOST_STATE = "Boost Enabled"         // Switch: On -> 1, Off -> 0
        const val KEY_CURRENT_GEAR = "Current Level (0=E,1=T,2=S,3=S+,4=B)"   // EditText (If editable)
        const val KEY_AUTOSHUTDOWN_ENABLED = "Auto Shutdown Enabled" // Switch: On -> value from Max, Off -> 255
        const val KEY_MAX_AUTOSHUTDOWN = "Max Auto Shutdown Time (0-30 min)" // EditText
        const val KEY_UNIT_SWITCH = "Units (On=MPH)"        // Switch: On -> 1, Off -> 0

        // Add keys for non-editable fields if needed for identification, though not strictly necessary
        const val KEY_HARD_VERSION = "Hardware Version"
        // ... other non-editable keys
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMeterInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Meter Information"
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun observeViewModel() {
        Log.d(TAG, "observeViewModel: Setting up observers.")
        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle ensures this block restarts when the fragment is STARTED
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "observeViewModel: Lifecycle STARTED. Collecting MeterInfo.")

                // Collect the LATEST value from the ViewModel's StateFlow
                // This will run when the fragment starts and whenever the StateFlow emits a new value.
                viewModel.meterInfo.collect { currentMeterInfoFromVm ->
                    Log.i(TAG, "Collector Received MeterInfo from VM: $currentMeterInfoFromVm")

                    // *** ALWAYS re-initialize editableInfo from the ViewModel's current state ***
                    // This ensures that when the fragment becomes visible, it reflects the latest data
                    // from the source of truth (ViewModel, which should reflect the bike's state).
                    if (currentMeterInfoFromVm != null) {
                        // Make a fresh copy for editing
                        editableInfo = currentMeterInfoFromVm.copy()
                        Log.d(TAG, "Re-initialized editableInfo: ${editableInfo.toString().take(100)}...")
                        populateList(editableInfo!!)
                    } else {
                        // ViewModel's data is null (e.g., disconnected, not yet loaded)
                        editableInfo = null
                        displayList.clear()
                        infoAdapter.submitList(emptyList())
                        Log.d(TAG, "ViewModel's MeterInfo is null. Cleared editableInfo and list.")
                    }
                }
            }
            Log.d(TAG, "observeViewModel: repeatOnLifecycle block finished.")
        }
        Log.d(TAG, "observeViewModel: Coroutine launch call finished.")
    }


    private fun setupRecyclerView() {
        infoAdapter = InfoAdapter { position, newValue ->
            if (editableInfo == null) {
                Log.w(TAG, "Adapter callback: editableInfo is null. Ignoring change.")
                // Optionally, you could try to refresh the list here if it got out of sync
                // but the observeViewModel should handle re-populating.
                return@InfoAdapter
            }
            if (position >= 0 && position < displayList.size) {
                val item = displayList[position]
                item.value = newValue
                updateEditableInfo(item.key, newValue)
            } else {
                Log.w(TAG,"RecyclerView Callback: Invalid position $position")
            }
        }
        binding.infoRecyclerView.apply {
            adapter = infoAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }


    // NEW: Updates the editableInfo object based on changes from the adapter
    private fun updateEditableInfo(key: String, newValue: String) {
        if (editableInfo == null) {
            Log.e(TAG, "updateEditableInfo called but editableInfo is null!")
            return
        }
        Log.d(TAG, "Updating editableInfo: key='$key', newValue='$newValue'")

        try {
            when (key) {
                // --- EditText Cases ---
                KEY_TOTAL_GEAR -> {
                    val intValue = newValue.toIntOrNull()
                    if (intValue != null) {
                        // Store potentially invalid value; validation happens on Update click
                        editableInfo?.totalGear = intValue
                    } else { Log.w(TAG, "Invalid integer format for $key: $newValue") }
                }
                KEY_CURRENT_GEAR -> { // If editable
                    val intValue = newValue.toIntOrNull()
                    if (intValue != null) {
                        editableInfo?.currentGear = gearProtocolValues[intValue] ?: intValue
                    } else { Log.w(TAG, "Invalid integer format for $key: $newValue") }
                }
                KEY_MAX_AUTOSHUTDOWN -> {
                    val intValue = newValue.toIntOrNull()
                    if (intValue != null) {
                        editableInfo?.maxAutoShutDown = intValue
                    } else { Log.w(TAG, "Invalid integer format for $key: $newValue") }
                }

                // --- Switch Cases (newValue is "0" or "1") ---
                KEY_LIGHT -> {
                    editableInfo?.light = newValue.toIntOrNull() ?: 0 // 0 or 1
                }
                KEY_SPORT_MODEL -> {
                    editableInfo?.sportModel = if (newValue == "1") MeterInfo.SPORT_MODEL_SPORT else MeterInfo.SPORT_MODEL_NORMAL
                }
                KEY_BOOST_STATE -> {
                    editableInfo?.boostState = if (newValue == "1") MeterInfo.BOOST_STATE_ON else MeterInfo.BOOST_STATE_OFF
                }
                KEY_AUTOSHUTDOWN_ENABLED -> {
                    if (newValue == "1") { // Turning ON
                        // If it was previously Never (255), use maxAutoShutDown (or default if max is invalid)
                        if (editableInfo?.autoShutDown == MeterInfo.AUTOSHUTDOWN_NEVER) {
                            val maxShutdown = editableInfo?.maxAutoShutDown ?: MeterInfo.AUTOSHUTDOWN_DEFAULT_ON
                            editableInfo?.autoShutDown = maxShutdown.coerceIn(
                                MeterInfo.MIN_MAX_AUTOSHUTDOWN,
                                MeterInfo.MAX_MAX_AUTOSHUTDOWN
                            )
                        } // else: Keep existing value if it was already on (e.g., 15)
                    } else { // Turning OFF
                        editableInfo?.autoShutDown = MeterInfo.AUTOSHUTDOWN_NEVER // Set to 255
                    }
                }
                KEY_UNIT_SWITCH -> {
                    editableInfo?.unitSwitch = if (newValue == "1") MeterInfo.UNIT_MPH else MeterInfo.UNIT_KMH
                }

                // else -> Log.w(TAG, "Attempted to update non-editable field or unhandled key: $key")
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number format error during update for $key: $newValue")
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateEditableInfo for $key: ${e.message}", e)
        }
    }


    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.popBackStack()
        }

        binding.updateButton.setOnClickListener {
            hideKeyboard()
            if (editableInfo == null) { // Should be rare now with the new observe logic
                Toast.makeText(requireContext(), "No data loaded to update", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Update clicked but editableInfo is null!")
                return@setOnClickListener
            }

            // Perform validation using the current state of editableInfo
            var isValid = true
            val validationErrors = mutableListOf<String>()
            val infoToUpdate = editableInfo!! // Shadow with non-null

            // ... (Keep ALL your existing validation logic for totalGear, currentGear, maxAutoShutDown, etc.)
            if (infoToUpdate.totalGear !in MeterInfo.MIN_TOTAL_GEAR..MeterInfo.MAX_TOTAL_GEAR) {
                isValid = false; validationErrors.add("Total Gears: ${MeterInfo.MIN_TOTAL_GEAR}-${MeterInfo.MAX_TOTAL_GEAR}.")
            }
            if (infoToUpdate.currentGear !in gearDisplayValues) {
                isValid = false; validationErrors.add("Current Level: 0-5 (E=1,T=2,S=3,S+=4,B=5).")
            }
            if (infoToUpdate.maxAutoShutDown !in MeterInfo.MIN_MAX_AUTOSHUTDOWN..MeterInfo.MAX_MAX_AUTOSHUTDOWN) {
                isValid = false; validationErrors.add("Max Auto Shutdown: ${MeterInfo.MIN_MAX_AUTOSHUTDOWN}-${MeterInfo.MAX_MAX_AUTOSHUTDOWN} min.")
            }
            if (infoToUpdate.autoShutDown != MeterInfo.AUTOSHUTDOWN_NEVER && infoToUpdate.autoShutDown !in MeterInfo.MIN_MAX_AUTOSHUTDOWN..MeterInfo.MAX_MAX_AUTOSHUTDOWN) {
                if(infoToUpdate.maxAutoShutDown in MeterInfo.MIN_MAX_AUTOSHUTDOWN..MeterInfo.MAX_MAX_AUTOSHUTDOWN) {
                    Log.w(TAG, "Auto-correcting autoShutDown (${infoToUpdate.autoShutDown}) to match valid maxAutoShutDown (${infoToUpdate.maxAutoShutDown})")
                    infoToUpdate.autoShutDown = infoToUpdate.maxAutoShutDown
                } else {
                    isValid = false; validationErrors.add("Auto Shutdown value (${infoToUpdate.autoShutDown}) is invalid.")
                }
            } else if (infoToUpdate.autoShutDown != MeterInfo.AUTOSHUTDOWN_NEVER && infoToUpdate.autoShutDown > infoToUpdate.maxAutoShutDown) {
                Log.w(TAG, "Auto-correcting autoShutDown (${infoToUpdate.autoShutDown}) to not exceed maxAutoShutDown (${infoToUpdate.maxAutoShutDown})")
                infoToUpdate.autoShutDown = infoToUpdate.maxAutoShutDown
            }


            if (isValid) {
                Log.d(TAG, "Validation passed. Sending updated Meter settings using: ${infoToUpdate.toString().take(100)}...")
                // Send update commands using infoToUpdate
                viewModel.updateMeterLight(infoToUpdate.light)
                viewModel.updateMeterTotalGear(infoToUpdate.totalGear)
                viewModel.updateMeterSportModel(infoToUpdate.sportModel)
                viewModel.updateMeterBoostState(infoToUpdate.boostState)
                viewModel.updateMeterCurrentGear(infoToUpdate.currentGear)
                viewModel.updateMeterAutoShutdown(infoToUpdate.autoShutDown)
                viewModel.updateMeterMaxAutoShutdown(infoToUpdate.maxAutoShutDown)
                viewModel.updateMeterUnitSwitch(infoToUpdate.unitSwitch)

                Toast.makeText(requireContext(), "Update commands sent", Toast.LENGTH_SHORT).show()
                // Consider delaying popBackStack slightly OR wait for a confirmation from ViewModel
                // that the write was acknowledged by the BLE stack (if your repo provides that).
                // For now, direct pop is fine.
                parentFragmentManager.popBackStack()
            } else {
                val errorMsg = "Validation failed:\n${validationErrors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }


    // Populates the RecyclerView list from the MeterInfo object
    private fun populateList(info: MeterInfo) {
        Log.i(TAG, "populateList: START - Populating with editable info: $info")
        displayList.clear()

        // --- Non-Editable Fields ---
        displayList.add(InfoItem(KEY_HARD_VERSION, info.hardVersion))
        displayList.add(InfoItem("Software Version", info.softVersion))
        displayList.add(InfoItem("Model", info.model))
        displayList.add(InfoItem("Serial Number", info.sn))
        displayList.add(InfoItem("Customer Number", info.customerNo))
        displayList.add(InfoItem("Manufacturer", info.manufacturer))
        // --- Editable Fields ---
        displayList.add(InfoItem(KEY_TOTAL_GEAR, info.totalGear.toString(), EditableType.EDIT_TEXT_NUMBER))
        displayList.add(InfoItem(KEY_SPORT_MODEL, if (info.sportModel == MeterInfo.SPORT_MODEL_SPORT) "1" else "0", EditableType.SWITCH))
        displayList.add(InfoItem(KEY_BOOST_STATE, if (info.boostState == MeterInfo.BOOST_STATE_ON) "1" else "0", EditableType.SWITCH))
        displayList.add(InfoItem(KEY_CURRENT_GEAR, (gearDisplayValues[info.currentGear] ?: info.currentGear).toString(), EditableType.EDIT_TEXT_NUMBER)) // If editable
        displayList.add(InfoItem(KEY_LIGHT, info.light.toString(), EditableType.SWITCH)) // Keep light editable
        displayList.add(InfoItem(KEY_AUTOSHUTDOWN_ENABLED, if (info.autoShutDown != MeterInfo.AUTOSHUTDOWN_NEVER) "1" else "0", EditableType.SWITCH))
        displayList.add(InfoItem(KEY_MAX_AUTOSHUTDOWN, info.maxAutoShutDown.toString(), EditableType.EDIT_TEXT_NUMBER))
        displayList.add(InfoItem(KEY_UNIT_SWITCH, if (info.unitSwitch == MeterInfo.UNIT_MPH) "1" else "0", EditableType.SWITCH))
        // --- Remaining Non-Editable Fields ---
        displayList.add(InfoItem("Total Mileage (km)", info.totalMileage.toString())) // Display only
        displayList.add(InfoItem("Single Mileage (0.1km)", String.format(Locale.US, "%.1f", info.singleMileage * 0.1)))
        displayList.add(InfoItem("Max Speed (0.1km/h)", String.format(Locale.US, "%.1f", info.maxSpeed * 0.1)))
        displayList.add(InfoItem("Average Speed (0.1km/h)", String.format(Locale.US, "%.1f", info.averageSpeed * 0.1)))
        displayList.add(InfoItem("Maintenance Mileage (0.1km)", String.format(Locale.US, "%.1f", info.maintenanceMileage * 0.1)))
        // Display the actual auto shutdown time if enabled
        val autoOffStr = when (info.autoShutDown) {
            MeterInfo.AUTOSHUTDOWN_NEVER -> "Never"
            else -> "${info.autoShutDown} min"
        }
        displayList.add(InfoItem("Auto Shutdown Set Time", autoOffStr)) // Display only derived value
        displayList.add(InfoItem("Total Ride Time (min)", info.totalRideTime.toString()))
        displayList.add(InfoItem("Total Calories (kcal)", info.totalCalories.toString()))
        displayList.add(InfoItem("Single Mileage 2 (0.1km)", String.format(Locale.US, "%.1f", info.singleMileage2 * 0.1)))


        Log.i(TAG, "populateList: Populated displayList with ${displayList.size} items.")
        infoAdapter.submitList(ArrayList(displayList)) // Submit a copy
        Log.i(TAG, "populateList: END - Submitted list to adapter.")
    }


    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = activity?.currentFocus // Use activity's focus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
        } else {
            // Fallback if no view has focus
            view?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideKeyboard() // Hide keyboard when fragment is destroyed
        _binding = null
    }
}