package com.example.autocaller

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MainActivity handles the UI and coordinates with AutoCallerService for background call management.
 * 
 * Key Android Permissions Best Practices (Android 6+ / API 23+):
 * 1. Always check permissions at runtime, even if declared in manifest
 * 2. Request permissions only when needed (just-in-time)
 * 3. Provide clear rationale for why permissions are needed
 * 4. Handle permission denial gracefully with alternative flows
 * 5. For Android 13+, POST_NOTIFICATIONS requires explicit request
 */
class MainActivity : AppCompatActivity(), AutoCallerService.CallStateCallback {

    companion object {
        private const val TAG = "MainActivity"
        
        // Permission request codes - use unique values to identify which permission was requested
        private const val CALL_PERMISSION_REQUEST_CODE = 100
        private const val PHONE_STATE_PERMISSION_REQUEST_CODE = 101
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 102
        
        // Maximum redial attempts before stopping
        private const val MAX_REDIAL_ATTEMPTS = 3
    }

    // UI Components
    private lateinit var phoneNumberInput: TextInputEditText
    private lateinit var startCallButton: MaterialButton
    private lateinit var stopCallButton: MaterialButton
    private lateinit var statusSection: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var callCountText: TextView
    private lateinit var redialAfterDisconnectSwitch: SwitchMaterial
    
    // Service binding for communication with background service
    private var autoCallerService: AutoCallerService? = null
    private var serviceBound = false
    
    // Track permission states and user choices
    private var pendingPhoneNumber: String? = null
    private var hasExplainedPermissions = false
    
    /**
     * ServiceConnection manages the lifecycle of our bound service.
     * This ensures we can communicate with the background service even when
     * the activity is paused or destroyed.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected - UI can now communicate with background service")
            val binder = service as AutoCallerService.LocalBinder
            autoCallerService = binder.getService()
            autoCallerService?.setCallback(this@MainActivity)
            serviceBound = true
            
            // Update UI with current service state in case service was already running
            updateUIFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected - background service is no longer available")
            autoCallerService?.setCallback(null)
            autoCallerService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components and set up event handlers
        initializeViews()
        setupClickListeners()
        updateButtonStates(false)
        
        // Bind to background service for call management
        bindToService()
        
        // Show initial permission status to user
        checkAndDisplayPermissionStatus()
    }

    /**
     * Initialize all UI components with proper null-safety checks.
     * Using findViewById is safe here since we're in onCreate after setContentView.
     */
    private fun initializeViews() {
        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        startCallButton = findViewById(R.id.startCallButton)
        stopCallButton = findViewById(R.id.stopCallButton)
        statusSection = findViewById(R.id.statusSection)
        statusText = findViewById(R.id.statusText)
        callCountText = findViewById(R.id.callCountText)
        redialAfterDisconnectSwitch = findViewById(R.id.redialAfterDisconnectSwitch)
        
        // Set default switch state
        redialAfterDisconnectSwitch.isChecked = true
    }

    /**
     * Set up click listeners with proper input validation and user feedback.
     */
    private fun setupClickListeners() {
        startCallButton.setOnClickListener {
            val phoneNumber = phoneNumberInput.text.toString().trim()
            
            // Validate phone number input
            if (phoneNumber.isEmpty()) {
                showUserMessage("Please enter a phone number", isError = true)
                phoneNumberInput.requestFocus()
                return@setOnClickListener
            }
            
            // Basic phone number validation (you might want more sophisticated validation)
            if (phoneNumber.length < 3) {
                showUserMessage("Please enter a valid phone number", isError = true)
                phoneNumberInput.requestFocus()
                return@setOnClickListener
            }
            
            // Store phone number and begin permission flow
            pendingPhoneNumber = phoneNumber
            requestAllRequiredPermissions()
        }

        stopCallButton.setOnClickListener {
            stopAutoCalling()
        }
        
        /**
         * Handle redial preference changes with immediate user feedback.
         * This setting affects behavior when calls are answered then disconnected.
         */
        redialAfterDisconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            val message = if (isChecked) {
                "✓ Will continue calling even after disconnection"
            } else {
                "✓ Will stop after call is answered and disconnected"
            }
            showUserMessage(message, isError = false)
        }
    }

    /**
     * Bind to the AutoCallerService for background call management.
     * Using BIND_AUTO_CREATE ensures the service starts if it's not running.
     */
    private fun bindToService() {
        Log.d(TAG, "Binding to AutoCallerService for background call management")
        val intent = Intent(this, AutoCallerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    /**
     * Update UI based on current service state.
     * This handles cases where the service is already running (e.g., app was killed and restarted).
     */
    private fun updateUIFromService() {
        autoCallerService?.let { service ->
            val isActive = service.isAutoCalling()
            val callCount = service.getCallCount()
            val status = service.getCurrentStatus()
            
            updateButtonStates(isActive)
            callCountText.text = callCount.toString()
            statusText.text = status
            
            // Show/hide status section based on activity state
            when {
                isActive -> {
                    statusSection.visibility = View.VISIBLE
                }
                callCount > 0 -> {
                    statusSection.visibility = View.VISIBLE
                    statusText.text = "Auto-calling stopped. Total attempts: $callCount"
                }
                else -> {
                    statusSection.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Check and display current permission status to inform the user.
     * This provides transparency about what permissions are granted.
     */
    private fun checkAndDisplayPermissionStatus() {
        val callPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
        val phoneStatePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        
        val statusMessage = when {
            callPermission == PackageManager.PERMISSION_GRANTED && 
            phoneStatePermission == PackageManager.PERMISSION_GRANTED -> {
                "✓ All permissions granted - Ready to make calls"
            }
            callPermission == PackageManager.PERMISSION_GRANTED -> {
                "⚠ Phone permission granted, but call monitoring permission needed for auto-redial"
            }
            else -> {
                "⚠ Permissions required - Tap 'Start Auto Calling' to grant"
            }
        }
        
        // Don't show status immediately on first launch to avoid clutter
        // User will see permission status when they try to start calling
        Log.d(TAG, "Permission status: $statusMessage")
    }

    /**
     * Request all permissions required for auto-calling functionality.
     * Modern Android (6+) requires runtime permission requests even if declared in manifest.
     * 
     * Permission Flow:
     * 1. CALL_PHONE - Required to actually make phone calls
     * 2. READ_PHONE_STATE - Required to detect when calls end for auto-redial
     * 3. POST_NOTIFICATIONS (Android 13+) - Required for foreground service notifications
     */
    private fun requestAllRequiredPermissions() {
        // First, check CALL_PHONE permission (most critical)
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)) {
            PackageManager.PERMISSION_GRANTED -> {
                // Call permission granted, now check phone state permission
                requestPhoneStatePermission()
            }
            else -> {
                // Need to request call permission first
                requestCallPermission()
            }
        }
    }

    /**
     * Request CALL_PHONE permission with user-friendly explanation.
     * This is the most critical permission - without it, the app cannot function.
     */
    private fun requestCallPermission() {
        when {
            // Check if we should show rationale (user previously denied)
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CALL_PHONE) -> {
                showPermissionRationale(
                    title = "Phone Permission Required",
                    message = "This app needs permission to make phone calls for the auto-calling feature to work. " +
                            "Without this permission, the app cannot dial numbers automatically.",
                    onPositive = {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.CALL_PHONE),
                            CALL_PERMISSION_REQUEST_CODE
                        )
                    }
                )
            }
            else -> {
                // First time request or user said "don't ask again"
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CALL_PHONE),
                    CALL_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * Request READ_PHONE_STATE permission for call monitoring.
     * This enables automatic redial when calls end or fail.
     */
    private fun requestPhoneStatePermission() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)) {
            PackageManager.PERMISSION_GRANTED -> {
                // Phone state permission granted, check notification permission
                requestNotificationPermissionIfNeeded()
            }
            else -> {
                when {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_PHONE_STATE) -> {
                        showPermissionRationale(
                            title = "Call Monitoring Permission",
                            message = "This permission allows the app to detect when calls end so it can automatically " +
                                    "redial up to $MAX_REDIAL_ATTEMPTS times. Without this permission, you'll need to " +
                                    "manually start each call attempt.",
                            onPositive = {
                                ActivityCompat.requestPermissions(
                                    this,
                                    arrayOf(Manifest.permission.READ_PHONE_STATE),
                                    PHONE_STATE_PERMISSION_REQUEST_CODE
                                )
                            },
                            onNegative = {
                                // Allow proceeding without phone state permission (reduced functionality)
                                showUserMessage(
                                    "⚠ Auto-redial disabled - you'll need to manually restart calls",
                                    isError = false
                                )
                                requestNotificationPermissionIfNeeded()
                            }
                        )
                    }
                    else -> {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.READ_PHONE_STATE),
                            PHONE_STATE_PERMISSION_REQUEST_CODE
                        )
                    }
                }
            }
        }
    }

    /**
     * Request notification permission for Android 13+ (API 33+).
     * This is required for foreground service notifications.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)) {
                PackageManager.PERMISSION_GRANTED -> {
                    // All permissions granted, proceed with calling
                    proceedWithCalling()
                }
                else -> {
                    when {
                        ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS) -> {
                            showPermissionRationale(
                                title = "Notification Permission",
                                message = "This permission allows the app to show a notification while making calls in the background. " +
                                        "This ensures the calling continues even when you switch to other apps.",
                                onPositive = {
                                    ActivityCompat.requestPermissions(
                                        this,
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                        NOTIFICATION_PERMISSION_REQUEST_CODE
                                    )
                                },
                                onNegative = {
                                    // Allow proceeding without notification permission (reduced UX)
                                    showUserMessage(
                                        "⚠ Background notifications disabled - check app status manually",
                                        isError = false
                                    )
                                    proceedWithCalling()
                                }
                            )
                        }
                        else -> {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST_CODE
                            )
                        }
                    }
                }
            }
        } else {
            // Android 12 and below don't need explicit notification permission
            proceedWithCalling()
        }
    }

    /**
     * Show a user-friendly permission rationale dialog.
     * This helps users understand why permissions are needed and increases grant rates.
     */
    private fun showPermissionRationale(
        title: String,
        message: String,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Grant Permission") { _, _ -> onPositive() }
            .setNegativeButton("Skip") { _, _ -> onNegative?.invoke() }
            .setCancelable(false)
            .show()
    }

    /**
     * Handle permission request results with detailed user feedback.
     * This method is called by the system when the user responds to permission requests.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // Validate that we received results
        if (grantResults.isEmpty()) {
            showUserMessage("Permission request was cancelled", isError = true)
            return
        }

        val isGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            CALL_PERMISSION_REQUEST_CODE -> {
                if (isGranted) {
                    showUserMessage("✓ Phone permission granted", isError = false)
                    // Continue with next permission
                    requestPhoneStatePermission()
                } else {
                    // Critical permission denied - cannot proceed
                    handleCriticalPermissionDenied(Manifest.permission.CALL_PHONE)
                }
            }
            
            PHONE_STATE_PERMISSION_REQUEST_CODE -> {
                if (isGranted) {
                    showUserMessage("✓ Call monitoring permission granted - Auto-redial enabled", isError = false)
                } else {
                    showUserMessage("⚠ Call monitoring denied - Auto-redial disabled", isError = false)
                }
                // Continue regardless (reduced functionality is acceptable)
                requestNotificationPermissionIfNeeded()
            }
            
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (isGranted) {
                    showUserMessage("✓ Notification permission granted", isError = false)
                } else {
                    showUserMessage("⚠ Notification permission denied - Check app status manually", isError = false)
                }
                // Continue regardless
                proceedWithCalling()
            }
        }
    }

    /**
     * Handle critical permission denial with options for the user.
     * For CALL_PHONE permission, the app cannot function without it.
     */
    private fun handleCriticalPermissionDenied(permission: String) {
        val permissionName = when (permission) {
            Manifest.permission.CALL_PHONE -> "Phone"
            else -> "Required"
        }
        
        AlertDialog.Builder(this)
            .setTitle("$permissionName Permission Required")
            .setMessage("This app cannot make calls without the phone permission. You can grant it in Settings > Apps > Auto Caller > Permissions.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel") { _, _ ->
                showUserMessage("Cannot start calling without phone permission", isError = true)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Open app settings for manual permission management.
     * This is the fallback when critical permissions are denied.
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            showUserMessage("Grant permissions in Settings, then return to the app", isError = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
            showUserMessage("Please manually grant permissions in Settings", isError = true)
        }
    }

    /**
     * Proceed with calling after all permission checks are complete.
     * This is called when we have at least the minimum required permissions.
     */
    private fun proceedWithCalling() {
        val phoneNumber = pendingPhoneNumber
        if (phoneNumber.isNullOrEmpty()) {
            showUserMessage("Please enter a phone number", isError = true)
            return
        }

        // Final permission check before starting
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            showUserMessage("Phone permission is required to make calls", isError = true)
            return
        }

        startAutoCalling(phoneNumber)
        pendingPhoneNumber = null // Clear pending number
    }

    /**
     * Start the auto-calling process via the background service.
     * The service handles call state monitoring and automatic redialing.
     */
    private fun startAutoCalling(phoneNumber: String) {
        Log.d(TAG, "Starting auto-calling for: $phoneNumber")
        
        val intent = Intent(this, AutoCallerService::class.java).apply {
            action = AutoCallerService.ACTION_START_CALLING
            putExtra(AutoCallerService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(AutoCallerService.EXTRA_REDIAL_AFTER_DISCONNECT, redialAfterDisconnectSwitch.isChecked)
        }
        
        try {
            startService(intent)
            showUserMessage("Auto-calling started - Maximum $MAX_REDIAL_ATTEMPTS attempts", isError = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start auto-calling service", e)
            showUserMessage("Failed to start calling service", isError = true)
        }
    }

    /**
     * Stop the auto-calling process.
     */
    private fun stopAutoCalling() {
        Log.d(TAG, "Stopping auto-calling")
        
        val intent = Intent(this, AutoCallerService::class.java).apply {
            action = AutoCallerService.ACTION_STOP_CALLING
        }
        
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop auto-calling service", e)
            showUserMessage("Failed to stop calling service", isError = true)
        }
    }

    /**
     * Update UI button states based on calling activity.
     */
    private fun updateButtonStates(isActive: Boolean) {
        startCallButton.isEnabled = !isActive
        stopCallButton.isEnabled = isActive
        redialAfterDisconnectSwitch.isEnabled = !isActive
        phoneNumberInput.isEnabled = !isActive
    }

    /**
     * Show user-friendly messages with appropriate styling.
     */
    private fun showUserMessage(message: String, isError: Boolean) {
        val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(this, message, duration).show()
        
        // Also log for debugging
        if (isError) {
            Log.w(TAG, "User error: $message")
        } else {
            Log.i(TAG, "User info: $message")
        }
    }

    // ===== AutoCallerService.CallStateCallback Implementation =====
    // These methods are called by the background service to update the UI

    override fun onCallCountChanged(count: Int) {
        runOnUiThread {
            callCountText.text = count.toString()
            
            // Show progress messages based on attempt count
            when {
                count >= MAX_REDIAL_ATTEMPTS -> {
                    showUserMessage("Maximum attempts ($MAX_REDIAL_ATTEMPTS) reached", isError = false)
                }
                count > 1 -> {
                    showUserMessage("Attempt $count of $MAX_REDIAL_ATTEMPTS", isError = false)
                }
            }
        }
    }

    override fun onCallStatusChanged(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    override fun onAutoCallingStarted() {
        runOnUiThread {
            updateButtonStates(true)
            statusSection.visibility = View.VISIBLE
        }
    }

    override fun onAutoCallingStopped() {
        runOnUiThread {
            updateButtonStates(false)
        }
    }

    // ===== Activity Lifecycle Management =====

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed - updating UI from service state")
        
        // Update UI in case service state changed while app was in background
        if (serviceBound) {
            updateUIFromService()
        }
        
        // Clear any pending phone number if permissions were granted in settings
        if (pendingPhoneNumber != null && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            showUserMessage("Permissions ready - tap 'Start Auto Calling' to begin", isError = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed - cleaning up service connection")
        
        // Clean up service connection
        if (serviceBound) {
            autoCallerService?.setCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        
        // Clear pending state
        pendingPhoneNumber = null
    }
}