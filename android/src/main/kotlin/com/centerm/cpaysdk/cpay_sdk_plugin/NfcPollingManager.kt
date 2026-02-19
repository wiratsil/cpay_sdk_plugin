package com.centerm.cpaysdk.cpay_sdk_plugin

// import android.util.Log
import com.pos.sdk.DeviceManager
import com.pos.sdk.rfcard.RfCardDevice
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Manages background NFC card polling using a scheduled executor.
 * Polls isRfCardPresent() at a configurable interval.
 */
class NfcPollingManager(private val deviceManager: DeviceManager?) {

    companion object {
        // private const val TAG = "NfcPollingManager"
    }

    private var scheduler: ScheduledExecutorService? = null
    private var pollingTask: ScheduledFuture<*>? = null
    
    private var cardDetectedListener: ((Boolean) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    
    private var lastCardState: Boolean = false
    private var isPolling = false

    fun setCardDetectedListener(listener: (Boolean) -> Unit) {
        cardDetectedListener = listener
    }

    fun setDebugListener(listener: (String) -> Unit) {
        debugListener = listener
    }

    private fun logDebug(msg: String) {
        // Log.d(TAG, msg)
        debugListener?.invoke("[NFC] $msg")
    }

    fun startPolling(intervalMs: Long = 500) {
        if (isPolling) {
            logDebug("Already polling")
            return
        }

        if (deviceManager == null) {
            logDebug("DeviceManager is null, cannot poll")
            return
        }

        scheduler = Executors.newSingleThreadScheduledExecutor()
        
        pollingTask = scheduler?.scheduleAtFixedRate({
            try {
                val rf = deviceManager.getRfDevice()
                val isPresent = rf?.exists() ?: false
                
                // Only notify on state change (edge detection)
                if (isPresent != lastCardState) {
                    lastCardState = isPresent
                    if (isPresent) {
                        logDebug("Card DETECTED")
                    } else {
                        logDebug("Card REMOVED")
                    }
                    cardDetectedListener?.invoke(isPresent)
                }
            } catch (_: Exception) {
                // Log.e(TAG, "Polling error", e)
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)

        isPolling = true
        logDebug("Polling started (interval: ${intervalMs}ms)")
    }

    fun stopPolling() {
        pollingTask?.cancel(false)
        pollingTask = null
        scheduler?.shutdown()
        scheduler = null
        isPolling = false
        lastCardState = false
        logDebug("Polling stopped")
    }

    fun isCurrentlyPolling(): Boolean = isPolling
}
