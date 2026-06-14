package com.test.bafangcon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.test.bafangcon.databinding.FragmentMainBinding
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var devicePreferences: DevicePreferences

    private val rideLogDirectoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.ride_log_folder_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                Log.e(TAG, "Could not persist ride log folder permission", e)
                Toast.makeText(requireContext(), R.string.ride_log_folder_permission_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            viewModel.startRideLogging(uri)
        }

    private var pendingExportJson: String? = null

    private val presetExportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingExportJson
            pendingExportJson = null
            if (uri == null || json == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("openOutputStream returned null")
                Toast.makeText(requireContext(), R.string.preset_export_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Preset export failed", e)
                Toast.makeText(requireContext(), R.string.preset_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val presetImportPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val json = try {
                requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Preset import read failed", e)
                null
            }
            if (json == null) {
                Toast.makeText(requireContext(), R.string.preset_import_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val presets = presetManager.parsePresetsJson(json)
            if (presets.isEmpty()) {
                Toast.makeText(requireContext(), R.string.preset_import_empty, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            showImportSelectionDialog(presets)
        }

    private var editablePersonalizedInfo: PersonalizedInfo? = null
    private var originalPersonalizedInfo: PersonalizedInfo? = null
    private var autoPersonalizedRequested = false
    private var currentGlobalAccel = 1
    private var globalAccelInitialized = false
    private var currentGlobalStartAngle = 0
    private var globalStartAngleInitialized = false
    private var autoRequestJob: Job? = null
    private var zeroDataRetryCount = 0
    private var isSystemInfoVisible = false
    private var systemInfoFragmentLoaded = false
    private var isInitialDataRenderComplete = false
    private lateinit var presetManager: PresetManager

    private data class AssistLevel(
        val gearIndex: Int,
        val labelRes: Int,
        val colorRes: Int
    )

    private companion object {
        private const val DATA_WAIT_TIMEOUT_MS = 5000L
        private const val TAG = "MainFragment"

        private val ASSIST_LEVELS = listOf(
            AssistLevel(2, R.string.level_e, R.color.level_e),
            AssistLevel(4, R.string.level_t, R.color.level_t),
            AssistLevel(6, R.string.level_s, R.color.level_s),
            AssistLevel(8, R.string.level_sp, R.color.level_sp),
            AssistLevel(9, R.string.level_b, R.color.level_b)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        devicePreferences = DevicePreferences(requireContext())
        presetManager = PresetManager(requireContext())
        presetManager.createDefaultPresetsIfNeeded()
        setupSystemBarInsets()
        setupListeners()
        setupDebugLogPanel()
        setupMainBottomGlobalSliders()
        observeViewModel()
        binding.debugLogToggle.text = getString(R.string.info_label)
        requestInitialDataIfAuthenticated()
    }

    private fun setupSystemBarInsets() {
        val topBarStart = binding.topBarLayout.paddingStart
        val topBarTop = binding.topBarLayout.paddingTop
        val topBarEnd = binding.topBarLayout.paddingEnd
        val topBarBottom = binding.topBarLayout.paddingBottom
        val bottomNavStart = binding.bottomNavigationLayout.paddingStart
        val bottomNavTop = binding.bottomNavigationLayout.paddingTop
        val bottomNavEnd = binding.bottomNavigationLayout.paddingEnd
        val bottomNavBottom = binding.bottomNavigationLayout.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBarLayout.setPadding(
                topBarStart,
                topBarTop + systemBars.top,
                topBarEnd,
                topBarBottom
            )
            binding.bottomNavigationLayout.setPadding(
                bottomNavStart,
                bottomNavTop,
                bottomNavEnd,
                bottomNavBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        checkRequiredPermissions()
    }

    // --- MODIFIED setupListeners ---
    private fun setupListeners() {
        binding.topDisconnectButton.setOnClickListener {
            devicePreferences.clear()
            if (isSystemInfoVisible) toggleSystemInfo()
            viewModel.disconnect()
        }

        binding.systemInfoNavButton.setOnClickListener {
            toggleSystemInfo()
        }

        binding.rideLogNavButton.setOnClickListener {
            if (viewModel.rideLogState.value.isLogging) {
                viewModel.stopRideLogging()
            } else {
                pickRideLogDirectory()
            }
        }

        binding.updateNavButton.setOnClickListener {
            val infoToUpdate = editablePersonalizedInfo
            if (infoToUpdate == null) {
                Toast.makeText(requireContext(), R.string.assist_no_data, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val validationErrors = validateAssistSettings(infoToUpdate)
            if (validationErrors.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Validation failed:\n${validationErrors.joinToString("\n")}",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val sb = StringBuilder()
            for (level in ASSIST_LEVELS) {
                val assistPct = bytePercent(infoToUpdate.gearSpeedLimit.getOrElse(level.gearIndex) { 0 })
                val motorPct = bytePercent(infoToUpdate.gearCurrentLimit.getOrElse(level.gearIndex) { 0 })
                val label = getString(level.labelRes)
                sb.append("$label: $assistPct% / $motorPct%\n")
            }
            val avgAccel = ASSIST_LEVELS.map { bytePercent(infoToUpdate.accelerationSettings.getOrElse(it.gearIndex) { 0 }) }
                .average().roundToInt().coerceIn(1, 8)
            val angle = infoToUpdate.motorStartingAngle.getOrElse(0) { 0 }.toInt().coerceIn(0, 360)
            sb.append("\nPrzyspieszenie: $avgAccel")
            sb.append("\nKąt startu: $angle")

            AlertDialog.Builder(requireContext())
                .setTitle("Wyślij ustawienia")
                .setMessage(sb.toString())
                .setPositiveButton("Wyślij") { _, _ ->
                    fun buildAndSend() {
                        val rawData = viewModel.controllerInfo.value?.rawData
                        if (rawData == null || rawData.size < 234) {
                            viewModel.requestControllerInfo()
                            Toast.makeText(requireContext(), "Odświeżam dane kontrolera, spróbuj ponownie za chwilę", Toast.LENGTH_SHORT).show()
                            return
                        }
                        val partial = rawData.copyOfRange(188, 234)
                        val avgAccel = ASSIST_LEVELS.map { bytePercent(infoToUpdate.accelerationSettings.getOrElse(it.gearIndex) { 0 }) }
                            .average().roundToInt().coerceIn(1, 8)
                        partial[25] = avgAccel.toByte()
                        val angle = infoToUpdate.motorStartingAngle.getOrElse(0) { 0 }.toInt().coerceIn(0, 360)
                        partial[23] = (angle and 0xFF).toByte()
                        partial[24] = ((angle shr 8) and 0xFF).toByte()
                        for (i in 0 until 10) {
                            partial[26 + i] = infoToUpdate.gearSpeedLimit.getOrElse(i) { 0 }
                            partial[36 + i] = infoToUpdate.gearCurrentLimit.getOrElse(i) { 0 }
                        }
                        viewModel.updateControllerPartial(partial, 188)
                        Toast.makeText(requireContext(), R.string.assist_update_sent, Toast.LENGTH_SHORT).show()
                        viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.delay(500)
                            viewModel.requestControllerInfo()
                        }
                    }
                    buildAndSend()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.presetNavButton.setOnClickListener {
            showPresetDialog()
        }

        binding.resetNavButton.setOnClickListener {
            val original = originalPersonalizedInfo
            if (original == null) {
                Toast.makeText(requireContext(), "Brak danych do przywrócenia", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            editablePersonalizedInfo = original.copy(
                motorStartingAngle = original.motorStartingAngle.copyOf(),
                accelerationSettings = original.accelerationSettings.copyOf(),
                gearSpeedLimit = original.gearSpeedLimit.copyOf(),
                gearCurrentLimit = original.gearCurrentLimit.copyOf()
            )
            viewModel.controllerInfo.value?.accelerationSettings?.let {
                currentGlobalAccel = it.coerceIn(1, 8)
            }
            globalAccelInitialized = true
            viewModel.controllerInfo.value?.motorStartingAngle?.let {
                currentGlobalStartAngle = it.coerceIn(1, 360)
            }
            globalStartAngleInitialized = true
            if (!isSystemInfoVisible) {
                renderMainAssistRows(editablePersonalizedInfo!!)
                bindMainBottomGlobalSliders(editablePersonalizedInfo!!)
                bindAssistChart(editablePersonalizedInfo!!)
            }
            Toast.makeText(requireContext(), "Przywrócono oryginalne wartości", Toast.LENGTH_SHORT).show()
        }

    }

    private fun pickRideLogDirectory() {
        Toast.makeText(requireContext(), R.string.ride_log_folder_required, Toast.LENGTH_SHORT).show()
        rideLogDirectoryPicker.launch(null)
    }


    // --- Other functions (checkRequiredPermissions, setInteractionEnabled, etc.) remain the same ---
    private fun checkRequiredPermissions() {
        // Check specifically for BLUETOOTH_CONNECT if on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG,"BLUETOOTH_CONNECT permission missing onResume.")
                // Don't automatically show snackbar on resume, but disable interaction
                setInteractionEnabled(false)
                // Optionally show a non-intrusive indicator
                showPermissionMissingSnackbar("interact with connected device")
                binding.connectionStatusTextView.text = getString(R.string.status_connected_permission_missing)
            } else {
                // Permission exists, update UI based on connection state
                updateUiState(viewModel.connectionState.value, true)
            }
        } else {
            setInteractionEnabled(true) // No specific connect permission needed before S
        }
    }

    private fun setInteractionEnabled(enabled: Boolean) {
        val isConnected = viewModel.connectionState.value == BleConnectionState.CONNECTED
        val isAuthenticated = viewModel.authState.value == BleAuthState.AUTHENTICATED
        val enableButtons = enabled && isConnected && isAuthenticated

        binding.topDisconnectButton.visibility = if (isConnected) View.VISIBLE else View.GONE
        binding.updateNavButton.isEnabled = enableButtons && editablePersonalizedInfo != null
        binding.rideLogNavButton.isEnabled = enableButtons || viewModel.rideLogState.value.isLogging
        binding.rideLogNavButton.text = getString(
            if (viewModel.rideLogState.value.isLogging) R.string.ride_log_stop else R.string.ride_log_start
        )

        val hasInfo = editablePersonalizedInfo != null
        setPresetButtonEnabled(hasInfo)
    }

    private fun setPresetButtonEnabled(enabled: Boolean) {
        binding.presetNavButton.isEnabled = enabled
        binding.presetNavButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before Android 12
        }
    }

    private fun showPermissionMissingSnackbar(actionDesc: String) {
        if (view == null) return
        Snackbar.make(binding.root, "Bluetooth Connect permission needed to $actionDesc.", Snackbar.LENGTH_LONG)
            .setAction("Settings") {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", requireActivity().packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG,"Could not open app settings", e)
                    Toast.makeText(requireContext(), "Could not open app settings", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun setupMainBottomGlobalSliders() {
        binding.accelGlobalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val clamped = value.roundToInt().coerceIn(1, 8)
                currentGlobalAccel = clamped
                globalAccelInitialized = true
                binding.accelGlobalValueText.text = clamped.toString()
                val info = editablePersonalizedInfo ?: return@addOnChangeListener
                for (i in info.accelerationSettings.indices) {
                    info.accelerationSettings[i] = clamped.toByte()
                }
            }
        }
        binding.startAngleGlobalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val clamped = value.roundToInt().coerceIn(1, 360)
                currentGlobalStartAngle = clamped
                globalStartAngleInitialized = true
                binding.startAngleGlobalValueText.text = clamped.toString()
                val info = editablePersonalizedInfo ?: return@addOnChangeListener
                for (i in info.motorStartingAngle.indices) {
                    info.motorStartingAngle[i] = clamped.toShort()
                }
            }
        }
    }

    private fun bindMainBottomGlobalSliders(info: PersonalizedInfo) {
        if (!globalAccelInitialized) {
            val fromA3 = viewModel.controllerInfo.value?.accelerationSettings?.coerceIn(1, 8)
            if (fromA3 != null) {
                currentGlobalAccel = fromA3
                globalAccelInitialized = true
            }
        }
        binding.accelGlobalSlider.value = currentGlobalAccel.toFloat()
        binding.accelGlobalValueText.text = currentGlobalAccel.toString()

        if (!globalStartAngleInitialized) {
            val fromA3 = viewModel.controllerInfo.value?.motorStartingAngle?.coerceIn(1, 360)
            if (fromA3 != null) {
                currentGlobalStartAngle = fromA3
                globalStartAngleInitialized = true
            }
        }
        binding.startAngleGlobalSlider.value = currentGlobalStartAngle.toFloat()
        binding.startAngleGlobalValueText.text = currentGlobalStartAngle.toString()
    }

    private fun showPresetDialog() {
        val info = editablePersonalizedInfo
        if (info == null) {
            Toast.makeText(requireContext(), R.string.assist_no_data, Toast.LENGTH_SHORT).show()
            return
        }

        val names = presetManager.getPresetNames()
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_preset_list, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.presetListContainer)
        val addButton = dialogView.findViewById<Button>(R.id.addPresetButton)
        val exportButton = dialogView.findViewById<Button>(R.id.exportPresetsButton)
        val importButton = dialogView.findViewById<Button>(R.id.importPresetsButton)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
        var dialog: AlertDialog? = null

        container.removeAllViews()
        if (names.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = getString(R.string.no_presets)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (20 * px).toInt(), 0, (20 * px).toInt())
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            container.addView(emptyText)
        } else {
            for (name in names) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((4 * px).toInt(), 0, (8 * px).toInt(), 0)
                }

                val loadBtn = Button(requireContext()).apply {
                    text = getString(R.string.preset_load)
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (40 * px).toInt()
                    )
                    setPadding((8 * px).toInt(), 0, (8 * px).toInt(), 0)
                    setOnClickListener {
                        val preset = presetManager.loadPreset(name)
                        if (preset != null) {
                            val loadedInfo = preset.toPersonalizedInfo()
                            val currentInfo = editablePersonalizedInfo
                            if (currentInfo != null) {
                                loadedInfo.controllerProtocolVersion = currentInfo.controllerProtocolVersion
                            }
                            editablePersonalizedInfo = loadedInfo
                            currentGlobalAccel = loadedInfo.accelerationSettings.map { it.toInt() and 0xFF }
                                .average().roundToInt().coerceIn(1, 8)
                            globalAccelInitialized = true
                            currentGlobalStartAngle = loadedInfo.motorStartingAngle.map { it.toInt() }
                                .average().roundToInt().coerceIn(1, 360)
                            globalStartAngleInitialized = true
                            renderMainAssistRows(editablePersonalizedInfo!!)
                            bindMainBottomGlobalSliders(editablePersonalizedInfo!!)
                            bindAssistChart(editablePersonalizedInfo!!)
                            Toast.makeText(requireContext(), R.string.preset_loaded, Toast.LENGTH_SHORT).show()
                            dialog?.dismiss()
                        } else {
                            Toast.makeText(requireContext(), "Failed to load preset", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val nameText = TextView(requireContext()).apply {
                    text = name
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding((8 * px).toInt(), (10 * px).toInt(), (8 * px).toInt(), (10 * px).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showPresetRenameOverwriteDialog(name, info, dialog)
                    }
                }

                val deleteBtn = Button(requireContext()).apply {
                    text = "✕"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * px).toInt(),
                        (40 * px).toInt()
                    )
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                    setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_preset_title)
                            .setMessage(getString(R.string.confirm_delete_preset, name))
                            .setPositiveButton(R.string.confirm_delete) { _, _ ->
                                presetManager.deletePreset(name)
                                Toast.makeText(requireContext(), R.string.preset_deleted, Toast.LENGTH_SHORT).show()
                                dialog?.dismiss()
                                showPresetDialog()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }

                row.addView(loadBtn)
                row.addView(nameText)
                row.addView(deleteBtn)
                container.addView(row)
            }
        }

        addButton.setOnClickListener {
            showPresetSaveDialog(info, dialog)
        }

        exportButton.setOnClickListener {
            if (names.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_presets, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog?.dismiss()
            showExportSelectionDialog()
        }

        importButton.setOnClickListener {
            dialog?.dismiss()
            presetImportPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }

        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun showExportSelectionDialog() {
        val names = presetManager.getPresetNames()
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_presets, Toast.LENGTH_SHORT).show()
            return
        }
        val items = names.toTypedArray()
        val checked = BooleanArray(items.size) { true }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_export_title)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.preset_export) { _, _ ->
                val selected = items.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.preset_none_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingExportJson = presetManager.exportPresetsToJson(selected)
                presetExportPicker.launch("bafang_presets.json")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportSelectionDialog(presets: List<AssistPreset>) {
        val items = presets.map { it.name }.toTypedArray()
        val checked = BooleanArray(items.size) { true }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_import_title)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.preset_import) { _, _ ->
                val selected = presets.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.preset_none_selected, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val count = presetManager.importPresets(selected)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_import_done, count),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPresetRenameOverwriteDialog(name: String, info: PersonalizedInfo, parentDialog: AlertDialog?) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.preset_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(name)
            selectAll()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Preset: $name")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.preset_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val preset = AssistPreset.fromPersonalizedInfo(newName, info)
                presetManager.savePreset(preset)
                if (newName != name) {
                    presetManager.deletePreset(name)
                }
                Toast.makeText(requireContext(), R.string.preset_saved, Toast.LENGTH_SHORT).show()
                d.dismiss()
                parentDialog?.dismiss()
                showPresetDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPresetSaveDialog(info: PersonalizedInfo, parentDialog: AlertDialog?) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.preset_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText("")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_save_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.preset_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (presetManager.presetExists(name)) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.preset_save_title)
                        .setMessage(getString(R.string.preset_name_exists, name))
                        .setPositiveButton(R.string.overwrite_preset) { _, _ ->
                            val preset = AssistPreset.fromPersonalizedInfo(name, info)
                            presetManager.savePreset(preset)
                            Toast.makeText(requireContext(), R.string.preset_saved, Toast.LENGTH_SHORT).show()
                            d.dismiss()
                            parentDialog?.dismiss()
                            showPresetDialog()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    val preset = AssistPreset.fromPersonalizedInfo(name, info)
                    presetManager.savePreset(preset)
                    Toast.makeText(requireContext(), R.string.preset_saved, Toast.LENGTH_SHORT).show()
                    d.dismiss()
                    parentDialog?.dismiss()
                    showPresetDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupDebugLogPanel() {
        var isDebugVisible = false
        binding.debugLogToggle.setOnClickListener {
            isDebugVisible = !isDebugVisible
            binding.debugLogPanel.visibility = if (isDebugVisible) View.VISIBLE else View.GONE
        }
        binding.debugLogClearButton.setOnClickListener {
            viewModel.clearBleLogs()
            binding.debugLogText.text = ""
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Connection State
                launch {
                    viewModel.connectionState.collect { state ->
                        // Update UI based on state and permission status
                        updateUiState(state, hasConnectPermission())

                        if (state == BleConnectionState.DISCONNECTED || state == BleConnectionState.FAILED) {
                            autoPersonalizedRequested = false
                            editablePersonalizedInfo = null
                            isInitialDataRenderComplete = false
                            // Navigate back to ScanFragment if not already there
                            if (activity is RootActivity && parentFragmentManager.findFragmentById(R.id.fragment_container) !is ScanFragment) {
                                (activity as RootActivity).navigateToScanFragment()
                            }
                        }
                    }
                }

                launch {
                    viewModel.authState.collect { authState ->
                        updateUiState(viewModel.connectionState.value, hasConnectPermission())
                        when (authState) {
                            BleAuthState.AUTHENTICATED -> {
                                requestInitialDataIfAuthenticated()
                                requestPersonalizedInfoIfNeeded()
                            }
                            BleAuthState.AUTH_FAILED -> {
                                autoPersonalizedRequested = false
                                autoRequestJob?.cancel()
                                binding.assistSummaryTextView.text = getString(R.string.assist_summary_empty)
                            }
                            else -> Unit
                        }
                    }
                }

                // Observe Data StateFlows for UI display updates
                launch { viewModel.personalizedInfo.collect { info -> updatePersonalizedInfoUI(info) } }

                launch {
                    viewModel.rideLogState.collect {
                        setInteractionEnabled(hasConnectPermission())
                    }
                }

                // Re-bind global sliders when A3 controller data arrives (e.g. after write)
                launch { viewModel.controllerInfo.collect { ci ->
                    if (!globalAccelInitialized) {
                        ci?.accelerationSettings?.let {
                            currentGlobalAccel = it.coerceIn(1, 8)
                            globalAccelInitialized = true
                        }
                    }
                    val pInfo = editablePersonalizedInfo
                    if (pInfo != null) {
                        bindMainBottomGlobalSliders(pInfo)
                    }
                } }

                // Observe BLE debug logs
                launch {
                    viewModel.bleLogs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            val last = logs.last()
                            val currentText = binding.debugLogText.text.toString()
                            binding.debugLogText.text = if (currentText.length > 50000) {
                                currentText.takeLast(30000) + "\n" + last
                            } else {
                                currentText + "\n" + last
                            }
                            binding.debugLogScroll.post { binding.debugLogScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }
            }
        }
    }

    // Update UI based on Connection State and Permissions
    private fun updateUiState(state: BleConnectionState, hasPermission: Boolean) {
        val isConnected = state == BleConnectionState.CONNECTED
        val authState = viewModel.authState.value
        setInteractionEnabled(hasPermission && isConnected) // Use helper

        binding.connectionStatusTextView.text = when(state) {
            BleConnectionState.CONNECTED -> when (authState) {
                BleAuthState.AUTHENTICATED -> getString(R.string.status_connected, "E-Bike") + if(!hasPermission) " (Permission Missing!)" else ""
                BleAuthState.AUTHENTICATING -> "Status: Authenticating"
                BleAuthState.AUTH_FAILED -> "Status: Authentication failed"
                BleAuthState.NOT_AUTHENTICATED -> "Status: Connected, waiting for authentication"
            }
            BleConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            BleConnectionState.CONNECTING -> getString(R.string.connecting)
            BleConnectionState.FAILED -> getString(R.string.status_failed)
            BleConnectionState.SCANNING -> "Unexpected: Scanning" // Should not be in this fragment when scanning
        }
    }

    private fun requestInitialDataIfAuthenticated() {
        if (viewModel.authState.value != BleAuthState.AUTHENTICATED) return
        if (viewModel.controllerInfo.value?.rawData == null) {
            viewModel.requestControllerInfo()
        }
    }

    private fun requestPersonalizedInfoIfNeeded() {
        if (viewModel.authState.value != BleAuthState.AUTHENTICATED) return
        if (autoPersonalizedRequested && autoRequestJob?.isActive == true) return
        autoPersonalizedRequested = true
        autoRequestJob?.cancel()
        binding.assistSummaryTextView.text = getString(R.string.assist_summary_loading)
        Log.d(TAG, "Auto-requesting Assist data for main screen.")

        autoRequestJob = viewLifecycleOwner.lifecycleScope.launch {
            // Ponawiaj AŻ przyjdą SENSOWNE (niezerowe) dane. KLUCZOWE: w trybie boot licznik (HMI)
            // odpowiada na A3/A9 SAMYMI ZERAMI (potwierdzone logiem aa55_raw). Wcześniej kod traktował
            // zerowy, ale != null obiekt jako "gotowe" i przestawał pytać → ekran wisiał na "Ładowanie...".
            // Teraz zerowe odpowiedzi ignorujemy i pytamy dalej, aż licznik wystanie i zwróci realne dane.
            // Stały, krótki interwał (bez backoffu) — render jest reaktywny (collector renderuje
            // w chwili przyjścia niezerowych danych), więc latencja = jak często pytamy. Licznik
            // odpowiada w ~150 ms, więc odpytywanie co POLL_INTERVAL daje dane niemal natychmiast
            // po zabootowaniu HMI, bez wrażenia "wisi".
            val pollInterval = 400L
            while (autoPersonalizedRequested && viewModel.authState.value == BleAuthState.AUTHENTICATED) {
                // Mamy już sensowne A9 (personalized)? — collector je wyrenderuje, kończymy.
                viewModel.personalizedInfo.value?.let { p ->
                    if (p.gearSpeedLimit.any { it.toInt() != 0 } || p.gearCurrentLimit.any { it.toInt() != 0 }) {
                        Log.d(TAG, "Personalized (A9) ma sensowne dane — koniec ponawiania.")
                        return@launch
                    }
                }
                // Mamy sensowne A3 (controller)? — buduj syntetyczne PersonalizedInfo (fallback DP).
                viewModel.controllerInfo.value?.let { ci ->
                    if (ci.rawData != null && ci.gearSpeedLimit.any { it.toInt() != 0 }) {
                        Log.d(TAG, "Controller (A3) ma sensowne dane — buduję fallback.")
                        buildFromControllerFallback(ci)
                        return@launch
                    }
                }

                // Wciąż zera/brak — poproś ponownie o A9 i A3 i poczekaj krótko.
                viewModel.requestPersonalizedInfo()
                viewModel.requestControllerInfo()
                kotlinx.coroutines.delay(pollInterval)
            }
        }
    }

    private fun buildFromControllerFallback(ci: ControllerInfo) {
        val syntheticInfo = PersonalizedInfo(
            controllerProtocolVersion = ci.controllerProtocolVersion,
            motorStartingAngle = ShortArray(10) { ci.motorStartingAngle.toShort() },
            accelerationSettings = ByteArray(10) { ci.accelerationSettings.toByte() },
            gearSpeedLimit = ci.gearSpeedLimit.copyOf(),
            gearCurrentLimit = ci.gearCurrentLimit.copyOf(),
            rawData = ci.rawData
        )
        Log.i(TAG, "Built synthetic PersonalizedInfo from A3 controller data")
        viewModel.setPersonalizedInfo(syntheticInfo)
        updatePersonalizedInfoUI(syntheticInfo)
    }

    private val ASSIST_LEVEL_LABELS = listOf("E", "T", "S", "S+", "B")
    private val ASSIST_LEVEL_COLORS = listOf(
        R.color.level_e, R.color.level_t, R.color.level_s, R.color.level_sp, R.color.level_b
    )

    private fun updatePersonalizedInfoUI(info: PersonalizedInfo?) {
        // Silent first load: suppress GUI refresh until we have real data
        if (!isInitialDataRenderComplete) {
            if (info == null) return

            // Dane całkowicie zerowe = licznik jeszcze nie gotowy (boot). Nie renderuj zer i NIE
            // ustawiaj editablePersonalizedInfo — trzymaj "Ładowanie...", aż pętla doprosi realne dane.
            // (Wcześniej po 3 zerach renderowało "Nie udało się odczytać" i blokowało dalsze ponawianie.)
            val allZero = isAllVisibleDataZero(info)
            if (allZero) {
                zeroDataRetryCount++
                return
            }

            // First complete render
            isInitialDataRenderComplete = true
            zeroDataRetryCount = 0
            editablePersonalizedInfo = info.copy(
                motorStartingAngle = info.motorStartingAngle.copyOf(),
                accelerationSettings = info.accelerationSettings.copyOf(),
                gearSpeedLimit = info.gearSpeedLimit.copyOf(),
                gearCurrentLimit = info.gearCurrentLimit.copyOf()
            )
            originalPersonalizedInfo = info.copy(
                motorStartingAngle = info.motorStartingAngle.copyOf(),
                accelerationSettings = info.accelerationSettings.copyOf(),
                gearSpeedLimit = info.gearSpeedLimit.copyOf(),
                gearCurrentLimit = info.gearCurrentLimit.copyOf()
            )
            binding.assistSummaryTextView.text = getString(R.string.assist_summary_loaded)
            renderMainAssistRows(editablePersonalizedInfo!!)
            bindMainBottomGlobalSliders(editablePersonalizedInfo!!)
            binding.bottomGlobalTitleText.visibility = View.VISIBLE
            binding.bottomGlobalControlsLayout.visibility = View.VISIBLE
            setPresetButtonEnabled(true)
            binding.updateNavButton.isEnabled = true
            if (!isSystemInfoVisible) {
                binding.assistChart.visibility = View.VISIBLE
            }
            bindAssistChart(editablePersonalizedInfo!!)
            if (allZero) {
                binding.assistSummaryTextView.text = "Nie udało się odczytać danych"
            }
            return
        }

        // Subsequent updates (after writes, reconnect) – normal flow
        if (info == null) {
            editablePersonalizedInfo = null
            originalPersonalizedInfo = null
            binding.assistSummaryTextView.text = getString(R.string.assist_summary_empty)
            binding.mainAssistLevelsContainer.removeAllViews()
            binding.bottomGlobalTitleText.visibility = View.GONE
            binding.bottomGlobalControlsLayout.visibility = View.GONE
            setPresetButtonEnabled(false)
            binding.assistChart.visibility = View.GONE
            return
        }

        editablePersonalizedInfo = info.copy(
            motorStartingAngle = info.motorStartingAngle.copyOf(),
            accelerationSettings = info.accelerationSettings.copyOf(),
            gearSpeedLimit = info.gearSpeedLimit.copyOf(),
            gearCurrentLimit = info.gearCurrentLimit.copyOf()
        )
        if (originalPersonalizedInfo == null) {
            originalPersonalizedInfo = info.copy(
                motorStartingAngle = info.motorStartingAngle.copyOf(),
                accelerationSettings = info.accelerationSettings.copyOf(),
                gearSpeedLimit = info.gearSpeedLimit.copyOf(),
                gearCurrentLimit = info.gearCurrentLimit.copyOf()
            )
        }
        binding.assistSummaryTextView.text = getString(R.string.assist_summary_loaded)
        renderMainAssistRows(editablePersonalizedInfo!!)
        bindMainBottomGlobalSliders(editablePersonalizedInfo!!)
        binding.bottomGlobalTitleText.visibility = View.VISIBLE
        binding.bottomGlobalControlsLayout.visibility = View.VISIBLE
        setPresetButtonEnabled(true)
        binding.updateNavButton.isEnabled = true
        if (!isSystemInfoVisible) {
            binding.assistChart.visibility = View.VISIBLE
        }
        bindAssistChart(editablePersonalizedInfo!!)
    }

    private fun isAllVisibleDataZero(info: PersonalizedInfo): Boolean {
        val indices = ASSIST_LEVELS.map { it.gearIndex }
        return indices.all { i ->
            bytePercent(info.gearSpeedLimit.getOrElse(i) { 0 }) == 0 &&
            bytePercent(info.gearCurrentLimit.getOrElse(i) { 0 }) == 0
        }
    }

    private fun refreshChart() {
        val info = editablePersonalizedInfo ?: return
        bindAssistChart(info)
    }

    private fun bindAssistChart(info: PersonalizedInfo) {
        val gearIndices = ASSIST_LEVELS.map { it.gearIndex }
        val speedPercents = gearIndices.map { bytePercent(info.gearSpeedLimit.getOrElse(it) { 0 }) }
        val currentPercents = gearIndices.map { bytePercent(info.gearCurrentLimit.getOrElse(it) { 0 }) }
        val colors = ASSIST_LEVEL_COLORS.map { ContextCompat.getColor(requireContext(), it) }
        binding.assistChart.setData(ASSIST_LEVEL_LABELS, colors, speedPercents, currentPercents)
    }

    private fun renderMainAssistRows(info: PersonalizedInfo) {
        binding.mainAssistLevelsContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (level in ASSIST_LEVELS) {
            val row = inflater.inflate(
                R.layout.list_item_assist_level,
                binding.mainAssistLevelsContainer,
                false
            )

            val assistValue = bytePercent(info.gearSpeedLimit.getOrElse(level.gearIndex) { 0 })
            val motorPowerValue = bytePercent(info.gearCurrentLimit.getOrElse(level.gearIndex) { 0 })

            val badge = row.findViewById<TextView>(R.id.levelTitleBadge)
            badge.text = getString(level.labelRes)

            setRoundedBg(row.findViewById(R.id.levelRowRoot), level.colorRes, 12f)

            // Jednolity, bialy tekst etykiet na kazdym poziomie (tla sa stonowane pod biel)
            val labelTextColor = ContextCompat.getColor(requireContext(), R.color.white)
            row.findViewById<TextView>(R.id.assistLevelLabel).setTextColor(labelTextColor)
            row.findViewById<TextView>(R.id.motorPowerLabel).setTextColor(labelTextColor)

            bindControls(
                row.findViewById(R.id.assistLevelEdit),
                row.findViewById(R.id.assistLevelSlider),
                row.findViewById(R.id.assistLevelMinusBtn),
                row.findViewById(R.id.assistLevelPlusBtn),
                assistValue
            ) { newValue ->
                editablePersonalizedInfo?.gearSpeedLimit?.set(level.gearIndex, newValue.toByte())
                refreshChart()
            }

            bindControls(
                row.findViewById(R.id.motorPowerEdit),
                row.findViewById(R.id.motorPowerSlider),
                row.findViewById(R.id.motorPowerMinusBtn),
                row.findViewById(R.id.motorPowerPlusBtn),
                motorPowerValue
            ) { newValue ->
                editablePersonalizedInfo?.gearCurrentLimit?.set(level.gearIndex, newValue.toByte())
                refreshChart()
            }

            binding.mainAssistLevelsContainer.addView(row)
        }
    }

    private fun bindControls(
        editText: EditText,
        slider: Slider,
        minusBtn: View,
        plusBtn: View,
        initialValue: Int,
        onChanged: (Int) -> Unit
    ) {
        val safeInitialValue = initialValue.coerceIn(0, 100)

        fun updateAll(value: Int, fromUser: Boolean) {
            val clamped = value.coerceIn(0, 100)
            editText.setText(clamped.toString())
            slider.value = clamped.toFloat()
            if (fromUser) {
                onChanged(clamped)
            }
        }

        editText.filters = arrayOf(InputFilter.LengthFilter(3))
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.setText(safeInitialValue.toString())

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                val v = editText.text.toString().toIntOrNull() ?: safeInitialValue
                updateAll(v, true)
                true
            } else false
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = editText.text.toString().toIntOrNull() ?: safeInitialValue
                updateAll(v, true)
            }
        }

        slider.value = safeInitialValue.toFloat()
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val clamped = value.roundToInt().coerceIn(0, 100)
                updateAll(clamped, true)
            }
        }

        minusBtn.setOnClickListener {
            val current = editText.text.toString().toIntOrNull() ?: safeInitialValue
            updateAll(current - 1, true)
        }

        plusBtn.setOnClickListener {
            val current = editText.text.toString().toIntOrNull() ?: safeInitialValue
            updateAll(current + 1, true)
        }
    }

    private fun validateAssistSettings(info: PersonalizedInfo): List<String> {
        val validationErrors = mutableListOf<String>()
        for (level in ASSIST_LEVELS) {
            val si = bytePercent(info.gearSpeedLimit.getOrElse(level.gearIndex) { 0 })
            val ci = bytePercent(info.gearCurrentLimit.getOrElse(level.gearIndex) { 0 })
            if (si !in 0..100) {
                validationErrors.add("${getString(level.labelRes)} Assist Level must be 0-100%.")
            }
            if (ci !in 0..100) {
                validationErrors.add("${getString(level.labelRes)} Motor Power Limit must be 0-100%.")
            }
        }
        return validationErrors
    }

    private fun bytePercent(value: Byte): Int = value.toInt() and 0xFF

    private fun averageByteValue(values: ByteArray): Int {
        if (values.isEmpty()) return 0
        return values.map { bytePercent(it) }.average().roundToInt()
    }

    private fun setRoundedBg(view: View, colorRes: Int, radiusDp: Float) {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, radiusDp,
            view.context.resources.displayMetrics
        )
        val drawable = GradientDrawable().apply {
            setColor(ContextCompat.getColor(view.context, colorRes))
            cornerRadius = px
        }
        view.background = drawable
    }


    private fun toggleSystemInfo() {
        isSystemInfoVisible = !isSystemInfoVisible
        binding.mainAssistScrollView.visibility = if (isSystemInfoVisible) View.GONE else View.VISIBLE
        binding.systemInfoContainer.visibility = if (isSystemInfoVisible) View.VISIBLE else View.GONE
        binding.assistChart.visibility = if (!isSystemInfoVisible && editablePersonalizedInfo != null) View.VISIBLE else View.GONE

        if (isSystemInfoVisible && !systemInfoFragmentLoaded) {
            systemInfoFragmentLoaded = true
            childFragmentManager.beginTransaction()
                .add(R.id.systemInfoContainer, SystemInfoFragment())
                .commitAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
