package com.example.autocaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.switchmaterial.SwitchMaterial
import android.os.Handler
import android.os.Looper
import android.util.Log

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoCaller"
    }

    private val CALL_PERMISSION_REQUEST_CODE = 1
    private val READ_PHONE_STATE_PERMISSION_REQUEST_CODE = 2
    private lateinit var phoneNumberInput: TextInputEditText
    private lateinit var startCallButton: MaterialButton
    private lateinit var stopCallButton: MaterialButton
    private lateinit var statusSection: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var callCountText: TextView
    private lateinit var redialAfterDisconnectSwitch: SwitchMaterial
    private var shouldRepeat = false
    private var phoneNumber: String = ""
    private var callCount = 0
    private val callDelay = 3000L // 3 seconds delay between calls
    private val handler = Handler(Looper.getMainLooper())
    private val maxCallDuration = 15000L // 15 seconds max before considering call failed
    
    // Add these as class variables for proper management
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isCallInProgress = false
    private var wasCallAnswered = false // Track if call was actually answered
    private var redialAfterDisconnect = true // Control redial behavior
    private var callStartTime = 0L
    private var fallbackTimer: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        startCallButton = findViewById(R.id.startCallButton)
        stopCallButton = findViewById(R.id.stopCallButton)
        statusSection = findViewById(R.id.statusSection)
        statusText = findViewById(R.id.statusText)
        callCountText = findViewById(R.id.callCountText)
        redialAfterDisconnectSwitch = findViewById(R.id.redialAfterDisconnectSwitch)
        
        // Initialize telephony manager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        startCallButton.setOnClickListener {
            phoneNumber = phoneNumberInput.text.toString()
            if (phoneNumber.isNotEmpty()) {
                requestPermissions()
            } else {
                Toast.makeText(this, "Enter a valid number", Toast.LENGTH_SHORT).show()
            }
        }

        stopCallButton.setOnClickListener {
            stopAutoCalling()
        }
        
        // Set up the redial switch
        redialAfterDisconnectSwitch.isChecked = redialAfterDisconnect
        redialAfterDisconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            redialAfterDisconnect = isChecked
            val message = if (isChecked) {
                "Will redial even after call disconnection"
            } else {
                "Will stop after call is answered and disconnected"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        updateButtonStates()
    }

    private fun requestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                CALL_PERMISSION_REQUEST_CODE
            )
        } else {
            startAutoCalling()
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
                startAutoCalling()
            } else {
                Toast.makeText(this, "Permissions denied. Please grant all permissions.", Toast.LENGTH_SHORT).show()
                // Optionally, open settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }

    private fun startAutoCalling() {
        Log.d(TAG, "Starting auto calling")
        shouldRepeat = true
        callCount = 0
        isCallInProgress = false
        wasCallAnswered = false
        redialAfterDisconnect = redialAfterDisconnectSwitch.isChecked
        updateButtonStates()
        updateStatusUI()
        registerPhoneStateListener()
        makeCall()
        Toast.makeText(this, "Auto-calling started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutoCalling() {
        Log.d(TAG, "Stopping auto calling")
        shouldRepeat = false
        isCallInProgress = false
        wasCallAnswered = false
        updateButtonStates()
        updateStatusUI()
        handler.removeCallbacksAndMessages(null)
        cancelFallbackTimer()
        unregisterPhoneStateListener()
        Toast.makeText(this, "Auto-calling stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates() {
        startCallButton.isEnabled = !shouldRepeat
        stopCallButton.isEnabled = shouldRepeat
        redialAfterDisconnectSwitch.isEnabled = !shouldRepeat
    }

    private fun updateStatusUI() {
        if (shouldRepeat) {
            statusSection.visibility = View.VISIBLE
            val status = when {
                isCallInProgress && wasCallAnswered -> "Call in progress with $phoneNumber..."
                isCallInProgress -> "Calling $phoneNumber..."
                else -> "Preparing to call $phoneNumber..."
            }
            statusText.text = status
            callCountText.text = callCount.toString()
        } else {
            if (callCount > 0) {
                statusText.text = "Auto-calling stopped. Total attempts: $callCount"
            } else {
                statusSection.visibility = View.GONE
            }
        }
    }

    private fun makeCall() {
        if (!shouldRepeat || isCallInProgress) {
            Log.d(TAG, "Skipping call - shouldRepeat: $shouldRepeat, isCallInProgress: $isCallInProgress")
            return
        }
        
        Log.d(TAG, "Making call #${callCount + 1} to $phoneNumber")
        callCount++
        isCallInProgress = true
        wasCallAnswered = false // Reset for new call
        callStartTime = System.currentTimeMillis()
        updateStatusUI()
        
        // Set up fallback timer in case PhoneStateListener doesn't work
        setupFallbackTimer()
        
        val callIntent = Intent(Intent.ACTION_CALL)
        callIntent.data = Uri.parse("tel:$phoneNumber")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(callIntent)
                Log.d(TAG, "Call intent started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make call", e)
                Toast.makeText(this, "Failed to make call: ${e.message}", Toast.LENGTH_SHORT).show()
                handleCallEnd(false)
            }
        }
    }

    private fun setupFallbackTimer() {
        cancelFallbackTimer()
        fallbackTimer = Runnable {
            Log.d(TAG, "Fallback timer triggered - call may have ended without detection")
            if (isCallInProgress) {
                handleCallEnd(wasCallAnswered)
            }
        }
        handler.postDelayed(fallbackTimer!!, maxCallDuration)
    }

    private fun cancelFallbackTimer() {
        fallbackTimer?.let {
            handler.removeCallbacks(it)
            fallbackTimer = null
        }
    }

    private fun handleCallEnd(callWasAnswered: Boolean) {
        Log.d(TAG, "Handling call end - wasAnswered: $callWasAnswered, current answered state: $wasCallAnswered")
        
        if (!isCallInProgress) {
            Log.d(TAG, "Call already ended, ignoring")
            return
        }
        
        isCallInProgress = false
        cancelFallbackTimer()
        
        if (!shouldRepeat) {
            Log.d(TAG, "Auto calling stopped, not retrying")
            return
        }
        
        // Determine if we should redial based on settings and call history
        val shouldRedial = when {
            !callWasAnswered && !wasCallAnswered -> {
                // Call was never answered - always redial
                Log.d(TAG, "Call never answered, will redial")
                Toast.makeText(this, "Call not answered, redialing in ${callDelay/1000} seconds...", Toast.LENGTH_SHORT).show()
                true
            }
            (callWasAnswered || wasCallAnswered) && redialAfterDisconnect -> {
                // Call was answered but user wants to redial after disconnect
                Log.d(TAG, "Call was answered but redial after disconnect is enabled")
                Toast.makeText(this, "Call disconnected, redialing in ${callDelay/1000} seconds...", Toast.LENGTH_SHORT).show()
                true
            }
            (callWasAnswered || wasCallAnswered) && !redialAfterDisconnect -> {
                // Call was answered and user doesn't want to redial after disconnect
                Log.d(TAG, "Call was answered and redial after disconnect is disabled, stopping")
                Toast.makeText(this, "Call completed, auto-dialer stopped", Toast.LENGTH_SHORT).show()
                stopAutoCalling()
                false
            }
            else -> {
                Log.d(TAG, "Unknown state, will redial to be safe")
                true
            }
        }
        
        if (shouldRedial) {
            Log.d(TAG, "Scheduling next call in ${callDelay}ms")
            handler.postDelayed({
                Log.d(TAG, "Retry timer triggered, making next call")
                makeCall()
            }, callDelay)
        }
    }

    private fun registerPhoneStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted")
            return
        }

        Log.d(TAG, "Registering phone state listener")
        // Unregister existing listener first
        unregisterPhoneStateListener()
        
        // Create and store the listener
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                super.onCallStateChanged(state, incomingNumber)
                val stateName = when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "Call state changed to: $stateName (isCallInProgress: $isCallInProgress, wasCallAnswered: $wasCallAnswered)")
                
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended, not picked up, or disconnected
                        Log.d(TAG, "CALL_STATE_IDLE detected")
                        if (isCallInProgress) {
                            handleCallEnd(wasCallAnswered)
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call is active (picked up)
                        Log.d(TAG, "CALL_STATE_OFFHOOK detected - call answered")
                        wasCallAnswered = true
                        updateStatusUI()
                        
                        // Don't stop auto-calling here anymore - let it continue based on user preference
                        if (!redialAfterDisconnect) {
                            Toast.makeText(applicationContext, 
                                "Call answered! Will stop after disconnect (redial disabled)", 
                                Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(applicationContext, 
                                "Call answered! Will redial after disconnect", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }

                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Phone is ringing (outgoing call is connecting)
                        Log.d(TAG, "CALL_STATE_RINGING detected")
                        updateStatusUI()
                    }
                }
            }
        }
        
        // Register the listener
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "Phone state listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register phone state listener", e)
        }
    }
    
    private fun unregisterPhoneStateListener() {
        phoneStateListener?.let { listener ->
            try {
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
                Log.d(TAG, "Phone state listener unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister phone state listener", e)
            }
            phoneStateListener = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
        handler.removeCallbacksAndMessages(null)
        cancelFallbackTimer()
        unregisterPhoneStateListener()
        shouldRepeat = false
        isCallInProgress = false
        wasCallAnswered = false
    }
}