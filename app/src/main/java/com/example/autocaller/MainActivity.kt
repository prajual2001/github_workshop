package com.example.autocaller

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : AppCompatActivity(), AutoCallerService.CallStateCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val CALL_PERMISSION_REQUEST_CODE = 1
    private lateinit var phoneNumberInput: TextInputEditText
    private lateinit var startCallButton: MaterialButton
    private lateinit var stopCallButton: MaterialButton
    private lateinit var statusSection: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var callCountText: TextView
    private lateinit var redialAfterDisconnectSwitch: SwitchMaterial
    
    // Service binding
    private var autoCallerService: AutoCallerService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as AutoCallerService.LocalBinder
            autoCallerService = binder.getService()
            autoCallerService?.setCallback(this@MainActivity)
            serviceBound = true
            
            // Update UI with current service state
            updateUIFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            autoCallerService?.setCallback(null)
            autoCallerService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        updateButtonStates(false)
        
        // Bind to service
        bindToService()
    }

    private fun initializeViews() {
        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        startCallButton = findViewById(R.id.startCallButton)
        stopCallButton = findViewById(R.id.stopCallButton)
        statusSection = findViewById(R.id.statusSection)
        statusText = findViewById(R.id.statusText)
        callCountText = findViewById(R.id.callCountText)
        redialAfterDisconnectSwitch = findViewById(R.id.redialAfterDisconnectSwitch)
    }

    private fun setupClickListeners() {
        startCallButton.setOnClickListener {
            val phoneNumber = phoneNumberInput.text.toString().trim()
            if (phoneNumber.isNotEmpty()) {
                requestPermissions(phoneNumber)
            } else {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
            }
        }

        stopCallButton.setOnClickListener {
            stopAutoCalling()
        }
        
        redialAfterDisconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            val message = if (isChecked) {
                "Will redial even after call disconnection"
            } else {
                "Will stop after call is answered and disconnected"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindToService() {
        Log.d(TAG, "Binding to service")
        val intent = Intent(this, AutoCallerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun updateUIFromService() {
        autoCallerService?.let { service ->
            val isActive = service.isAutoCalling()
            val callCount = service.getCallCount()
            val status = service.getCurrentStatus()
            
            updateButtonStates(isActive)
            callCountText.text = callCount.toString()
            statusText.text = status
            
            if (isActive) {
                statusSection.visibility = View.VISIBLE
            } else if (callCount > 0) {
                statusSection.visibility = View.VISIBLE
                statusText.text = "Auto-calling stopped. Total attempts: $callCount"
            } else {
                statusSection.visibility = View.GONE
            }
        }
    }

    private fun requestPermissions(phoneNumber: String) {
        val permissionsNeeded = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        
        // Android 13+ notification permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            // Store phone number for use after permissions are granted
            phoneNumberInput.tag = phoneNumber
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                CALL_PERMISSION_REQUEST_CODE
            )
        } else {
            startAutoCalling(phoneNumber)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALL_PERMISSION_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            
            if (allPermissionsGranted) {
                val phoneNumber = phoneNumberInput.tag as? String ?: phoneNumberInput.text.toString().trim()
                if (phoneNumber.isNotEmpty()) {
                    startAutoCalling(phoneNumber)
                }
            } else {
                Toast.makeText(this, "Permissions denied. Please grant all permissions.", Toast.LENGTH_LONG).show()
                // Open settings for manual permission grant
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }

    private fun startAutoCalling(phoneNumber: String) {
        Log.d(TAG, "Starting auto calling via service")
        val intent = Intent(this, AutoCallerService::class.java).apply {
            action = AutoCallerService.ACTION_START_CALLING
            putExtra(AutoCallerService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(AutoCallerService.EXTRA_REDIAL_AFTER_DISCONNECT, redialAfterDisconnectSwitch.isChecked)
        }
        
        // Start service (will become foreground service)
        startService(intent)
    }

    private fun stopAutoCalling() {
        Log.d(TAG, "Stopping auto calling via service")
        val intent = Intent(this, AutoCallerService::class.java).apply {
            action = AutoCallerService.ACTION_STOP_CALLING
        }
        startService(intent)
    }

    private fun updateButtonStates(isActive: Boolean) {
        startCallButton.isEnabled = !isActive
        stopCallButton.isEnabled = isActive
        redialAfterDisconnectSwitch.isEnabled = !isActive
        phoneNumberInput.isEnabled = !isActive
    }

    // AutoCallerService.CallStateCallback implementation
    override fun onCallCountChanged(count: Int) {
        runOnUiThread {
            callCountText.text = count.toString()
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
            Toast.makeText(this, "Auto-calling started", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAutoCallingStopped() {
        runOnUiThread {
            updateButtonStates(false)
            Toast.makeText(this, "Auto-calling stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed")
        // Update UI in case service state changed while app was in background
        if (serviceBound) {
            updateUIFromService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
        if (serviceBound) {
            autoCallerService?.setCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}