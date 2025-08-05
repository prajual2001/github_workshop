package com.example.autocaller

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class AutoCallerService : Service() {

    companion object {
        private const val TAG = "AutoCallerService"
        private const val CHANNEL_ID = "AUTO_CALLER_CHANNEL"
        private const val NOTIFICATION_ID = 1
        
        // Actions
        const val ACTION_START_CALLING = "START_CALLING"
        const val ACTION_STOP_CALLING = "STOP_CALLING"
        
        // Extras
        const val EXTRA_PHONE_NUMBER = "PHONE_NUMBER"
        const val EXTRA_REDIAL_AFTER_DISCONNECT = "REDIAL_AFTER_DISCONNECT"
        
        // Maximum redial attempts before stopping automatically
        private const val MAX_REDIAL_ATTEMPTS = 3
    }

    // Binder for local service binding
    inner class LocalBinder : Binder() {
        fun getService(): AutoCallerService = this@AutoCallerService
    }

    private val binder = LocalBinder()
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Call state variables
    private var shouldRepeat = false
    private var phoneNumber: String = ""
    private var callCount = 0
    private var isCallInProgress = false
    private var wasCallAnswered = false
    private var redialAfterDisconnect = true
    private val callDelay = 3000L
    private val maxCallDuration = 15000L
    private var fallbackTimer: Runnable? = null
    
    // Callback interface for communicating with activity
    interface CallStateCallback {
        fun onCallCountChanged(count: Int)
        fun onCallStatusChanged(status: String)
        fun onAutoCallingStarted()
        fun onAutoCallingStopped()
    }
    
    private var callback: CallStateCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_CALLING -> {
                phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                redialAfterDisconnect = intent.getBooleanExtra(EXTRA_REDIAL_AFTER_DISCONNECT, true)
                startAutoCalling()
            }
            ACTION_STOP_CALLING -> {
                stopAutoCalling()
            }
        }
        
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Auto Caller Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps auto calling running in background"
            setSound(null, null)
            enableVibration(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AutoCallerService::class.java).apply {
            action = ACTION_STOP_CALLING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_phone_call)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    private fun updateNotification() {
        val title = if (shouldRepeat) "Auto Caller Active" else "Auto Caller Stopped"
        val text = if (shouldRepeat) {
            when {
                isCallInProgress && wasCallAnswered -> "Call in progress with $phoneNumber (Attempt: $callCount)"
                isCallInProgress -> "Calling $phoneNumber (Attempt: $callCount)"
                else -> "Preparing to call $phoneNumber (Attempt: $callCount)"
            }
        } else {
            "Auto calling stopped. Total attempts: $callCount"
        }
        
        val notification = createNotification(title, text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun setCallback(callback: CallStateCallback?) {
        this.callback = callback
    }

    fun startAutoCalling() {
        if (phoneNumber.isEmpty()) {
            Log.e(TAG, "Phone number is empty")
            return
        }
        
        Log.d(TAG, "Starting auto calling to $phoneNumber")
        shouldRepeat = true
        callCount = 0
        isCallInProgress = false
        wasCallAnswered = false
        
        // Start as foreground service
        val notification = createNotification("Auto Caller Starting", "Preparing to call $phoneNumber")
        startForeground(NOTIFICATION_ID, notification)
        
        registerPhoneStateListener()
        makeCall()
        
        callback?.onAutoCallingStarted()
        updateNotification()
    }

    fun stopAutoCalling() {
        Log.d(TAG, "Stopping auto calling")
        shouldRepeat = false
        isCallInProgress = false
        wasCallAnswered = false
        
        handler.removeCallbacksAndMessages(null)
        cancelFallbackTimer()
        unregisterPhoneStateListener()
        
        callback?.onAutoCallingStopped()
        updateNotification()
        
        // Stop foreground service after a delay to show final status
        handler.postDelayed({
            stopForeground(true)
            stopSelf()
        }, 3000)
    }

    private fun makeCall() {
        if (!shouldRepeat || isCallInProgress) {
            Log.d(TAG, "Skipping call - shouldRepeat: $shouldRepeat, isCallInProgress: $isCallInProgress")
            return
        }
        
        Log.d(TAG, "Making call #${callCount + 1} to $phoneNumber")
        callCount++
        isCallInProgress = true
        wasCallAnswered = false
        
        callback?.onCallCountChanged(callCount)
        callback?.onCallStatusChanged("Calling $phoneNumber...")
        updateNotification()
        
        // Set up fallback timer
        setupFallbackTimer()
        
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) 
            == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(callIntent)
                Log.d(TAG, "Call intent started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make call", e)
                handleCallEnd(false)
            }
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted")
            handleCallEnd(false)
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
        
        // Check if we've reached maximum attempts
        if (callCount >= MAX_REDIAL_ATTEMPTS) {
            Log.d(TAG, "Maximum redial attempts ($MAX_REDIAL_ATTEMPTS) reached, stopping")
            callback?.onCallStatusChanged("Maximum attempts ($MAX_REDIAL_ATTEMPTS) reached - Auto-calling stopped")
            showToast("Maximum attempts ($MAX_REDIAL_ATTEMPTS) reached. Auto-calling stopped.")
            stopAutoCalling()
            return
        }
        
        // Determine if we should redial
        val shouldRedial = when {
            !callWasAnswered && !wasCallAnswered -> {
                Log.d(TAG, "Call never answered, will redial (attempt ${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                callback?.onCallStatusChanged("Call not answered, redialing in ${callDelay/1000} seconds... (${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                showToast("Call not answered, redialing in ${callDelay/1000} seconds... (${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                true
            }
            (callWasAnswered || wasCallAnswered) && redialAfterDisconnect -> {
                Log.d(TAG, "Call was answered but redial after disconnect is enabled (attempt ${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                callback?.onCallStatusChanged("Call disconnected, redialing in ${callDelay/1000} seconds... (${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                showToast("Call disconnected, redialing in ${callDelay/1000} seconds... (${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                true
            }
            (callWasAnswered || wasCallAnswered) && !redialAfterDisconnect -> {
                Log.d(TAG, "Call was answered and redial after disconnect is disabled, stopping")
                callback?.onCallStatusChanged("Call completed, auto-dialer stopped")
                showToast("Call completed, auto-dialer stopped")
                stopAutoCalling()
                false
            }
            else -> {
                Log.d(TAG, "Unknown state, will redial to be safe (attempt ${callCount + 1}/$MAX_REDIAL_ATTEMPTS)")
                true
            }
        }
        
        if (shouldRedial) {
            Log.d(TAG, "Scheduling next call in ${callDelay}ms")
            callback?.onCallStatusChanged("Waiting to redial...")
            updateNotification()
            
            handler.postDelayed({
                Log.d(TAG, "Retry timer triggered, making next call")
                makeCall()
            }, callDelay)
        }
    }

    private fun registerPhoneStateListener() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted")
            return
        }

        Log.d(TAG, "Registering phone state listener in service")
        unregisterPhoneStateListener()
        
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
                        Log.d(TAG, "CALL_STATE_IDLE detected in service")
                        if (isCallInProgress) {
                            handleCallEnd(wasCallAnswered)
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "CALL_STATE_OFFHOOK detected in service - call answered")
                        wasCallAnswered = true
                        callback?.onCallStatusChanged("Call in progress with $phoneNumber...")
                        updateNotification()
                        
                        val message = if (!redialAfterDisconnect) {
                            "Call answered! Will stop after disconnect (redial disabled)"
                        } else {
                            "Call answered! Will redial after disconnect"
                        }
                        showToast(message)
                    }

                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "CALL_STATE_RINGING detected in service")
                        callback?.onCallStatusChanged("Calling $phoneNumber...")
                        updateNotification()
                    }
                }
            }
        }
        
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "Phone state listener registered successfully in service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register phone state listener in service", e)
        }
    }
    
    private fun unregisterPhoneStateListener() {
        phoneStateListener?.let { listener ->
            try {
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
                Log.d(TAG, "Phone state listener unregistered from service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister phone state listener from service", e)
            }
            phoneStateListener = null
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Getters for current state
    fun isAutoCalling(): Boolean = shouldRepeat
    fun getCallCount(): Int = callCount
    fun getCurrentStatus(): String {
        return when {
            !shouldRepeat -> "Stopped"
            isCallInProgress && wasCallAnswered -> "Call in progress with $phoneNumber..."
            isCallInProgress -> "Calling $phoneNumber..."
            else -> "Preparing to call $phoneNumber..."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        handler.removeCallbacksAndMessages(null)
        cancelFallbackTimer()
        unregisterPhoneStateListener()
    }
}