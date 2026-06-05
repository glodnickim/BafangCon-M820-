package com.test.bafangcon

import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.test.bafangcon.databinding.FragmentScanBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!! // Only valid between onCreateView and onDestroyView

    // Use activityViewModels to share the ViewModel with MainActivity/RootActivity
    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var deviceScanAdapter: DeviceScanAdapter
    private lateinit var devicePrefs: DevicePreferences
    private var autoConnectAttempted = false

    // Permission Launcher
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if *all* requested permissions were granted
            if (permissions.all { it.value }) {
                Log.d("ScanFragment", "All permissions granted by user.")
                // Permissions granted, start scan
                viewModel.startScan()
            } else {
                // At least one permission denied
                Log.w("ScanFragment", "Permissions denied by user.")
                Snackbar.make(binding.root, R.string.permissions_denied, Snackbar.LENGTH_LONG).show()
                updateUiState(BleConnectionState.DISCONNECTED) // Reset state visually
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePrefs = DevicePreferences(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSystemBarInsets()
        setupRecyclerView()
        setupListeners()
        observeViewModel()

        // Auto-start scan if we have a saved device (silent — no permission dialog)
        if (devicePrefs.lastDeviceAddress != null) {
            val missingPermissions = viewModel.requiredPermissions.filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isEmpty()) {
                viewModel.startScan()
            }
        }
    }

    private fun setupSystemBarInsets() {
        val headerStart = binding.scanHeaderLayout.paddingStart
        val headerTop = binding.scanHeaderLayout.paddingTop
        val headerEnd = binding.scanHeaderLayout.paddingEnd
        val headerBottom = binding.scanHeaderLayout.paddingBottom
        val actionStart = binding.scanActionLayout.paddingStart
        val actionTop = binding.scanActionLayout.paddingTop
        val actionEnd = binding.scanActionLayout.paddingEnd
        val actionBottom = binding.scanActionLayout.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.scanHeaderLayout.setPadding(
                headerStart,
                headerTop + systemBars.top,
                headerEnd,
                headerBottom
            )
            binding.scanActionLayout.setPadding(
                actionStart,
                actionTop,
                actionEnd,
                actionBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        // If already scanning when resuming, ensure permissions are still valid
        if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
            checkRequiredPermissions(showAlertAndRequest = false) // Re-check without showing alert immediately
        }
    }

    // Modify checkAndRequestPermissions slightly
    private fun checkAndRequestPermissions(showAlert: Boolean = true) {
        val missingPermissions = viewModel.requiredPermissions.filter {
            requireContext().checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // All permissions granted
            // Only start scan if not already scanning (check state)
            if (viewModel.connectionState.value != BleConnectionState.SCANNING &&
                viewModel.connectionState.value != BleConnectionState.CONNECTING) {
                viewModel.startScan()
            }
        } else {
            // Stop scan if it was running without permissions
            if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
                viewModel.stopScan()
                updateUiState(BleConnectionState.DISCONNECTED) // Reflect stopped state
            }
            // Request missing permissions
            if (showAlert) { // Only show alert/request if explicitly told to
                Log.w("ScanFragment", "Requesting missing permissions: $missingPermissions")
                // Show rationale if needed before launching
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            } else {
                Log.w("ScanFragment", "Permissions missing on resume check: $missingPermissions")
            }
        }
    }


    private fun setupRecyclerView() {
        deviceScanAdapter = DeviceScanAdapter { device ->
            connectToDevice(device)
        }
        binding.scanRecyclerView.apply {
            adapter = deviceScanAdapter
            layoutManager = LinearLayoutManager(requireContext())
            // Optional: Add dividers
            addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
            // Prevent clicks while connecting (handled also by disabling scan button)
            // isEnabled = viewModel.connectionState.value != BleConnectionState.CONNECTING
        }
    }

    private fun setupListeners() {
        binding.scanInfoButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_message)
                .setPositiveButton("OK", null)
                .show()
        }
        binding.scanButton.setOnClickListener {
            when (viewModel.connectionState.value) {
                BleConnectionState.SCANNING -> {
                    // If scanning, stop the scan
                    viewModel.stopScan()
                }
                BleConnectionState.CONNECTING -> {
                    // Should not happen as button is disabled, but good practice
                    Log.d("ScanFragment", "Scan button clicked while connecting, ignoring.")
                }
                BleConnectionState.CONNECTED -> {
                    viewModel.disconnect()
                }
                else -> {
                    // If disconnected or failed, check permissions and start scan
                    checkRequiredPermissions(showAlertAndRequest = true)
                }
            }
        }

    }

    private fun connectToDevice(device: DiscoveredBluetoothDevice) {
        devicePrefs.lastDeviceAddress = device.address
        devicePrefs.lastDeviceName = device.name
        Log.d("ScanFragment", "Saved device: ${device.name} (${device.address})")
        if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
            viewModel.stopScan()
        }
        viewModel.connect(device)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle ensures collection stops when the fragment is STOPPED
            // and restarts when STARTED.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Scan Results
                launch {
                    viewModel.scanResults.collect { results ->
                        val filtered = results.filter { !it.name.isNullOrBlank() && it.name!!.startsWith("DP") }
                        deviceScanAdapter.submitList(filtered.sortedByDescending { it.rssi })

                        if (!autoConnectAttempted && viewModel.connectionState.value == BleConnectionState.SCANNING) {
                            val savedAddress = devicePrefs.lastDeviceAddress
                            if (savedAddress != null) {
                                val match = filtered.find { it.address == savedAddress }
                                if (match != null) {
                                    Log.d("ScanFragment", "Auto-connecting to saved device: ${match.name} ($savedAddress)")
                                    autoConnectAttempted = true
                                    connectToDevice(match)
                                }
                            }
                        }
                    }
                }

                // Observe Connection State for UI updates and navigation
                launch {
                    combine(viewModel.connectionState, viewModel.authState) { state, authState ->
                        state to authState
                    }.collect { (state, authState) ->
                        updateUiState(state, authState) // Update button text, progress bar visibility etc.

                        // Navigate only when BLE and protocol authentication are ready.
                        if (state == BleConnectionState.CONNECTED && authState == BleAuthState.AUTHENTICATED) {
                            Log.d("ScanFragment", "Authentication successful, navigating to MainFragment.")
                            (activity as? RootActivity)?.navigateToMainFragment()
                        } else if (state == BleConnectionState.DISCONNECTED) {
                            // Reset auto-connect flag when we return to scan screen
                            autoConnectAttempted = false
                        }
                    }
                }
                // Observe connection errors
                launch {
                    viewModel.connectionError.collect { error ->
                        if (error != null) {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // Updates the Scan Button text, ProgressBar visibility based on state
    private fun updateUiState(
        state: BleConnectionState,
        authState: BleAuthState = viewModel.authState.value
    ) {
        val isAuthenticating = state == BleConnectionState.CONNECTED && authState == BleAuthState.AUTHENTICATING
        binding.scanProgressBar.isVisible = state == BleConnectionState.SCANNING || state == BleConnectionState.CONNECTING || isAuthenticating
        binding.scanLogo.isVisible = state != BleConnectionState.SCANNING
        binding.scanButton.text = if (state == BleConnectionState.CONNECTED) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.scan_start)
        }
        // Disable scan button AND recyclerview interaction while connecting
        val isBusy = state == BleConnectionState.CONNECTING || isAuthenticating
        binding.scanButton.isEnabled = !isBusy
        binding.scanRecyclerView.isEnabled = !isBusy // Prevent clicks during connection attempt

        binding.scanStatusText.isVisible = true
        binding.scanStatusText.text = when {
            state == BleConnectionState.CONNECTED && authState == BleAuthState.AUTHENTICATING -> "Authenticating..."
            state == BleConnectionState.CONNECTED && authState == BleAuthState.AUTH_FAILED -> "Authentication failed"
            state == BleConnectionState.CONNECTED && authState == BleAuthState.AUTHENTICATED -> getString(R.string.connected)
            else -> when(state) {
            BleConnectionState.SCANNING -> getString(R.string.status_scanning)
            BleConnectionState.CONNECTING -> getString(R.string.connecting) // Maybe add device name later
            BleConnectionState.DISCONNECTED -> getString(R.string.scan_stopped) // Or "Ready to Scan"
            BleConnectionState.CONNECTED -> getString(R.string.connected) // Should navigate away
            BleConnectionState.FAILED -> getString(R.string.connection_failed) // Indicate failure clearly
            }
        }
        // Show error text on FAILED
        if (state == BleConnectionState.FAILED) {
            binding.scanStatusText.text = getString(R.string.connection_failed)
        }
        // Hide status text if disconnected and no results yet
        if(state == BleConnectionState.DISCONNECTED && deviceScanAdapter.itemCount == 0) {
            binding.scanStatusText.isVisible = false
        }
    }


    /**
     * Checks if necessary permissions are granted. If not, and [showAlertAndRequest] is true,
     * it triggers the permission request flow.
     * If permissions are missing but [showAlertAndRequest] is false (e.g., onResume check),
     * it just logs the issue and potentially stops an ongoing scan.
     */
    private fun checkRequiredPermissions(showAlertAndRequest: Boolean) {
        val missingPermissions = viewModel.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // All permissions are granted.
            Log.d("ScanFragment", "All required permissions are granted.")
            // If triggered by user action (showAlertAndRequest=true), start scan.
            // Avoid auto-starting scan onResume if permissions were already granted.
            if (showAlertAndRequest) {
                // Ensure we are not already scanning or connecting
                if (viewModel.connectionState.value != BleConnectionState.SCANNING &&
                    viewModel.connectionState.value != BleConnectionState.CONNECTING) {
                    viewModel.startScan()
                }
            }
        } else {
            // Permissions are missing.
            Log.w("ScanFragment", "Missing permissions: $missingPermissions")
            // If currently scanning, stop it because permissions are missing.
            if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
                Log.w("ScanFragment", "Stopping scan because permissions are missing.")
                viewModel.stopScan()
                // Update UI immediately to reflect stopped state if needed
                updateUiState(BleConnectionState.DISCONNECTED)
            }

            // If triggered by user action (like button press), show rationale and request.
            if (showAlertAndRequest) {
                // TODO: Consider showing a rationale dialog before requesting if !shouldShowRequestPermissionRationale
                Log.d("ScanFragment", "Requesting missing permissions...")
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Important: Stop scanning if the view is destroyed to prevent leaks/battery drain
        // Check the state before stopping, might have already stopped or connected.
        if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
            Log.d("ScanFragment", "Stopping scan in onDestroyView")
            viewModel.stopScan()
        }
        _binding = null // Avoid memory leaks by nulling the binding reference
    }
}
