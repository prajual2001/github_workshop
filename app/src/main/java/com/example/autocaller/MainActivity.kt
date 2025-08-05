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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private val CALL_PERMISSION_REQUEST_CODE = 1
    private val READ_PHONE_STATE_PERMISSION_REQUEST_CODE = 2
    private lateinit var phoneNumberInput: EditText
    private lateinit var startCallButton: Button
    private lateinit var stopCallButton: Button
    private var shouldRepeat = false
    private var phoneNumber: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val callDelay = 3000L // 3 seconds delay between calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        startCallButton = findViewById(R.id.startCallButton)
        stopCallButton = findViewById(R.id.stopCallButton)

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
        shouldRepeat = true
        updateButtonStates()
        registerPhoneStateListener()
        makeCall()
        Toast.makeText(this, "Auto-calling started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutoCalling() {
        shouldRepeat = false
        updateButtonStates()
        handler.removeCallbacksAndMessages(null)
        Toast.makeText(this, "Auto-calling stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates() {
        startCallButton.isEnabled = !shouldRepeat
        stopCallButton.isEnabled = shouldRepeat
    }

    private fun makeCall() {
        if (!shouldRepeat) return
        
        val callIntent = Intent(Intent.ACTION_CALL)
        callIntent.data = Uri.parse("tel:$phoneNumber")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(callIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to make call: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun registerPhoneStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        telephonyManager.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                super.onCallStateChanged(state, incomingNumber)
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended or not picked up
                        if (shouldRepeat) {
                            handler.postDelayed({
                                makeCall() // Repeat the call after delay
                            }, callDelay)
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call is active (picked up)
                        stopAutoCalling()
                        Toast.makeText(applicationContext, "Call answered, stopping auto-dialer", Toast.LENGTH_SHORT).show()
                    }

                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Phone is ringing (for incoming calls, usually)
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        shouldRepeat = false
    }
}