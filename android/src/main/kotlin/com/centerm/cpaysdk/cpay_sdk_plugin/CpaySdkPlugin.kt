package com.centerm.cpaysdk.cpay_sdk_plugin

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.NonNull
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.location.GpsStatus
import android.location.GnssStatus
import android.location.OnNmeaMessageListener
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.PluginRegistry
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner

import com.pos.sdk.DevicesFactory
import com.pos.sdk.DeviceManager
import com.pos.sdk.callback.ResultCallback
import com.pos.sdk.printer.PrinterDevice
import com.pos.sdk.printer.param.TextPrintItemParam
import com.pos.sdk.printer.param.PrintItemAlign
import com.pos.sdk.printer.IPrinterResultListener
import com.pos.sdk.sys.SystemDevice
import com.pos.sdk.scan.ScanDevice
import com.pos.sdk.scan.IScanCallback
import com.pos.sdk.scan.IScanner
import com.pos.sdk.emv.EmvKernelDevice
import com.pos.sdk.emv.OnCheckCardResult
import com.pos.sdk.emv.CommonTrackData
import com.pos.sdk.emv.EmvTransParam
import com.pos.sdk.emv.EmvTransType
import com.pos.sdk.emv.IEmvKernelListener
import com.pos.util.HexUtils
import com.pos.sdk.rfcard.RfCardDevice
import java.util.concurrent.Executors

/** CpaySdkPlugin */
class CpaySdkPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener, LocationListener, GpsStatus.Listener {
  private lateinit var channel : MethodChannel
  private lateinit var eventChannel : EventChannel
  private lateinit var context: Context
  private var activity: Activity? = null
  private var mDeviceManager: DeviceManager? = null
  private val executor = Executors.newSingleThreadExecutor()
  private var eventSink: EventChannel.EventSink? = null
  private val uiHandler = Handler(Looper.getMainLooper())
  
  private var pendingLocationResult: Result? = null
  private val GPS_PERMISSION_REQUEST_CODE = 999
  private var cachedLocation: Location? = null
  private var isMonitoring = false
  
  // GnssStatus Callback for API >= 24
  private var gnssStatusCallback: GnssStatus.Callback? = null
  private var nmeaListener: OnNmeaMessageListener? = null
  
  // QR Scanner (Background)
  private var qrScannerManager: QrScannerManager? = null
  private lateinit var qrEventChannel: EventChannel
  private var qrEventSink: EventChannel.EventSink? = null
  
  // NFC Polling (Background)
  private var nfcPollingManager: NfcPollingManager? = null
  private lateinit var nfcEventChannel: EventChannel
  private var nfcEventSink: EventChannel.EventSink? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin")
    channel.setMethodCallHandler(this)
    
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin/events")
    eventChannel.setStreamHandler(this)
    
    // QR Event Channel
    qrEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin/qr_events")
    qrEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            qrEventSink = events
        }
        override fun onCancel(arguments: Any?) {
            qrEventSink = null
        }
    })
    
    // NFC Event Channel
    nfcEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin/nfc_events")
    nfcEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            nfcEventSink = events
        }
        override fun onCancel(arguments: Any?) {
            nfcEventSink = null
        }
    })

    context = flutterPluginBinding.applicationContext
    initSdk()
    
    // Crash protection: release camera if app crashes
    setupCrashHandler()
  }

  private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

  private fun setupCrashHandler() {
    defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      Log.e("CpaySdkPlugin", "App crash detected! Releasing camera...", throwable)
      try {
        qrScannerManager?.forceStopAll()
        nfcPollingManager?.stopPolling()
      } catch (e: Exception) {
        Log.e("CpaySdkPlugin", "Error releasing resources on crash", e)
      }
      // Pass to default handler
      defaultExceptionHandler?.uncaughtException(thread, throwable)
    }
  }

  private fun initSdk() {
    DevicesFactory.create(context, object : ResultCallback<DeviceManager> {
        override fun onFinish(deviceManager: DeviceManager) {
            mDeviceManager = deviceManager
        }

        override fun onError(i: Int, s: String) {
            println("CpaySDK Init Failed: $i, $s")
        }
    })
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
      eventSink = events
  }

  override fun onCancel(arguments: Any?) {
      eventSink = null
  }
  
  private fun sendDebug(msg: String) {
      uiHandler.post { eventSink?.success(msg) }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
     if (mDeviceManager == null) {
          result.error("SDK_NOT_READY", "Device Manager is not initialized", null)
          return
     }

    when (call.method) {
        "getSystemInfo" -> {
            val sys = mDeviceManager!!.getSystemDevice()
            val sn = sys.getSystemInfo(SystemDevice.SystemInfoType.SN)
            result.success(sn)
        }
        "printText" -> {
            val content = call.argument<String>("content")
            if (content != null) {
                printText(content, result)
            } else {
                 result.error("INVALID_ARGUMENT", "Content cannot be null", null)
            }
        }
        "scan" -> {
            scan(call, result)
        }
        "beep" -> {
            beep(result)
        }
        "checkCard" -> {
            checkCard(result)
        }
        "readCardEmv" -> {
            readCardEmv(result)
        }
        "getLocation" -> {
            getLocation(result)
        }
        "startLocationService" -> {
            startLocationService(result)
        }
        "stopLocationService" -> {
            stopLocationService(result)
        }
        "isRfCardPresent" -> {
            isRfCardPresent(result)
        }
        "startQrScan" -> {
            startQrScan(call, result)
        }
        "stopQrScan" -> {
            stopQrScan(result)
        }
        "startNfcPolling" -> {
            startNfcPolling(call, result)
        }
        "stopNfcPolling" -> {
            stopNfcPolling(result)
        }
        else -> {
            result.notImplemented()
        }
    }
  }

  private fun printText(content: String, result: Result) {
      try {
          val printer = mDeviceManager!!.getPrintDevice()
          printer.clearBufferArea()

          val param = TextPrintItemParam()
          param.content = content
          param.textSize = 24
          param.itemAlign = PrintItemAlign.CENTER
          printer.addTextPrintItem(param)

          val bundle = Bundle()
          printer.print(bundle, object : IPrinterResultListener.Stub() {
              override fun onPrintFinish() {
                   activity?.runOnUiThread { result.success(true) } ?: result.success(true)
              }

              override fun onPrintError(i: Int, s: String) {
                   activity?.runOnUiThread { result.error("PRINT_ERROR", "Code: $i Msg: $s", null) } ?: result.error("PRINT_ERROR", "Code: $i Msg: $s", null)
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }
  
  private fun beepSuccess() {
      try {
          // Fire and forget beep
          executor.execute {
              try {
                  mDeviceManager!!.getBeepDevice().beep(0)
              } catch (e: Exception) {}
          }
      } catch (e: Exception) {}
  }

  private fun scan(call: MethodCall, result: Result) {
      try {
          val isFront = call.argument<Boolean>("isFrontCamera") ?: false
          val timeout = call.argument<Int>("timeout") ?: 60000
          val scanner = mDeviceManager!!.getScanDevice()
          val params = Bundle()
          params.putInt(IScanner.CAMERA_ID, if (isFront) 0 else 1) // 0: Front, 1: Back
          params.putInt(IScanner.TIMEOUT, timeout)
          
          scanner.scan(params, object : IScanCallback.Stub() {
              override fun onSuccess(bytes: ByteArray?) {
                  beepSuccess()
                  val scanResult = if (bytes != null) String(bytes) else ""
                  activity?.runOnUiThread { result.success(scanResult) } ?: result.success(scanResult)
              }

              override fun onFailed(i: Int, s: String?) {
                  // Handle Timeout (Code 200) gracefully
                  if (i == 200 || i == -1) { // 200 is timeout/cancel usually
                       activity?.runOnUiThread { result.success(null) } ?: result.success(null)
                  } else {
                       activity?.runOnUiThread { result.error("SCAN_ERROR", "Code: $i Msg: $s", null) } ?: result.error("SCAN_ERROR", "Code: $i Msg: $s", null)
                  }
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }
  
  private fun beep(result: Result) {
      try {
           val beeper = mDeviceManager!!.getBeepDevice()
           beeper.beep(0) // 0=Success/Normal beep
           result.success(true)
      } catch (e: Exception) {
          result.success(false)
      }
  }

  private fun checkCard(result: Result) {
      try {
          val emv = mDeviceManager!!.getEmvKernelDevice()
          
          emv.stopCheckCard() 

          emv.checkCard(true, true, true, 30000, object : OnCheckCardResult {
              override fun onFindMagCard(trackData: CommonTrackData?) {
                  beepSuccess()
                  val sb = StringBuilder()
                  sb.append("Mag Swipe Detected:\n")
                  sb.append("Card No: ").append(trackData?.cardNo?.let { String(it) } ?: "Unknown").append("\n")
                  // Use 'expire' property instead of binary date
                  sb.append("Expiry: ").append(trackData?.expire?.let { String(it) } ?: "Unknown")
                  
                  activity?.runOnUiThread { result.success(sb.toString()) } ?: result.success(sb.toString())
                  emv.stopCheckCard()
              }

              override fun onFindICCard() {
                   beepSuccess()
                   activity?.runOnUiThread { result.success("IC Card Detected.\nUse 'Read Detail' for data.") } ?: result.success("IC Card Detected.\nUse 'Read Detail' for data.")
                   emv.stopCheckCard()
              }

              override fun onFindRFCard() {
                   beepSuccess()
                   activity?.runOnUiThread { result.success("RF Card Detected.\nUse 'Read Detail' for data.") } ?: result.success("RF Card Detected.\nUse 'Read Detail' for data.")
                   emv.stopCheckCard()
              }

              override fun onError(i: Int, s: String?) {
                   activity?.runOnUiThread { result.error("CARD_ERROR", "Code: $i Msg: $s", null) } ?: result.error("CARD_ERROR", "Code: $i Msg: $s", null)
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  // --- GPS Location ---
  private fun getLocation(result: Result) {
      val context = activity?.applicationContext ?: return result.error("NO_CONTEXT", "Context is null", null)

      if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
          
          if (activity != null) {
              pendingLocationResult = result
              ActivityCompat.requestPermissions(activity!!, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), GPS_PERMISSION_REQUEST_CODE)
          } else {
              result.error("PERMISSION_DENIED", "Location permission not granted and activity is null", null)
          }
          return
      }

      try {
          // 1. Try Cache First
          if (cachedLocation != null && (System.currentTimeMillis() - cachedLocation!!.time) < 120000) { // 2 mins valid
               sendDebug("Location from cache")
               result.success("${cachedLocation!!.latitude},${cachedLocation!!.longitude}")
               return
          }

          val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
          val providers = locationManager.getProviders(true)
          var bestLocation: Location? = null

          for (provider in providers) {
              val l = locationManager.getLastKnownLocation(provider) ?: continue
              if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                  bestLocation = l
              }
          }

          if (bestLocation != null) {
              result.success("${bestLocation.latitude},${bestLocation.longitude}")
          } else {
              // Request async update
              sendDebug("No last known location. Requesting update...")
              pendingLocationResult = result
              requestLocationUpdates(context, locationManager)
          }
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  private fun requestLocationUpdates(context: Context, locationManager: LocationManager) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val providers = locationManager.getProviders(true)
            sendDebug("Enabled Providers: $providers")
            var requestCount = 0
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 0L, 0f, this)
                requestCount++
            }
            
            if (requestCount > 0) {
                 // Timeout handler - 60 seconds
                 uiHandler.postDelayed({
                     if (pendingLocationResult != null) {
                         sendDebug("Location request timed out after 60s.")
                         locationManager.removeUpdates(this)
                         pendingLocationResult?.error("TIMEOUT", "Location request timed out (60s). Providers: $providers. Please check if GPS is really working.", null)
                         pendingLocationResult = null
                     }
                 }, 60000) 
            } else {
                pendingLocationResult?.error("NO_PROVIDER", "No location provider enabled", null)
                pendingLocationResult = null
            }
        }
  }
 
  private fun startLocationService(result: Result) {
      if (isMonitoring) {
          result.success(true)
          return
      }
      val context = activity?.applicationContext ?: return result.error("NO_CONTEXT", "Context is null", null)
      if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
           // Should request permission, but assuming granted for now or handle appropriately
           result.error("PERMISSION_DENIED", "Permission denied", null)
           return
      }
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
      
      val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
      val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
      sendDebug("Start Monitor: SDK=${Build.VERSION.SDK_INT}, GPS=$isGpsEnabled, Network=$isNetworkEnabled")

      if (!isGpsEnabled && !isNetworkEnabled) {
          result.error("LOCATION_OFF", "Location services are disabled", null)
          return
      }

      try {
          val providers = locationManager.getProviders(true)
          sendDebug("Active Providers: $providers")
          
          for (provider in providers) {
              locationManager.requestLocationUpdates(provider, 1000L, 0f, this) // Aggressive: Update every 1s, 0m change
          }
          
          // Register Status Listener
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
              gnssStatusCallback = object : GnssStatus.Callback() {
                  override fun onSatelliteStatusChanged(status: GnssStatus) {
                      val count = status.satelliteCount
                      var fixed = 0
                      for (i in 0 until count) {
                          if (status.usedInFix(i)) fixed++
                      }
                      uiHandler.post {
                          sendDebug("GNSS Status: Visible=$count, Used=$fixed")
                      }
                  }
                  override fun onStarted() { sendDebug("GNSS Started") }
                  override fun onStopped() { sendDebug("GNSS Stopped") }
              }
              locationManager.registerGnssStatusCallback(gnssStatusCallback!!, uiHandler)
              
              // Register NMEA listener
              nmeaListener = OnNmeaMessageListener { message, timestamp ->
                   // Log only GPGGA or first few chars to show life
                   if (message.startsWith("\$GPGGA") || message.startsWith("\$GNGGA")) {
                       uiHandler.post { sendDebug("NMEA: $message") }
                   }
              }
              locationManager.addNmeaListener(nmeaListener!!, uiHandler)
          } else {
              locationManager.addGpsStatusListener(this)
          }

          isMonitoring = true
          sendDebug("Location Monitoring Started (with Satellite Status)")
          result.success(true)
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  private fun stopLocationService(result: Result) {
      if (!isMonitoring) {
          result.success(true)
          return
      }
      val context = activity?.applicationContext
      if (context != null) {
          val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
          locationManager.removeUpdates(this)
          
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
              locationManager.unregisterGnssStatusCallback(gnssStatusCallback!!)
              gnssStatusCallback = null
              
              if (nmeaListener != null) {
                  locationManager.removeNmeaListener(nmeaListener!!)
                  nmeaListener = null
              }
          } else {
              locationManager.removeGpsStatusListener(this)
          }
      }
      isMonitoring = false
      sendDebug("Location Monitoring Stopped")
      result.success(true)
  }
 
  override fun onLocationChanged(location: Location) {
      cachedLocation = location
      if (pendingLocationResult != null) {
          pendingLocationResult?.success("${location.latitude},${location.longitude}")
          pendingLocationResult = null
          // If we were just waiting for one update and not monitoring, stop? 
          // But here requestLocationUpdates vs startLocationService might conflict if we are not careful.
          // Ideally if isMonitoring is false, we remove updates.
          if (!isMonitoring) {
             val context = activity?.applicationContext
             if (context != null) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locationManager.removeUpdates(this)
             }
          }
      }
  }

  override fun onGpsStatusChanged(event: Int) {
      val context = activity?.applicationContext ?: return
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
      val status = locationManager.getGpsStatus(null)
      when (event) {
          GpsStatus.GPS_EVENT_SATELLITE_STATUS -> {
              var sats = 0
              var fixed = 0
              if (status != null) {
                  for (sat in status.satellites) {
                      sats++
                      if (sat.usedInFix()) {
                          fixed++
                      }
                  }
              }
              // Only log periodically to avoid spamming, or if count changes significantly?
              // For debugging now, let's log every time it changes or just debug
              uiHandler.post {
                   sendDebug("GPS Status: Visible=$sats, Used=$fixed")
              }
          }
          GpsStatus.GPS_EVENT_STARTED -> sendDebug("GPS Engine Started")
          GpsStatus.GPS_EVENT_STOPPED -> sendDebug("GPS Engine Stopped")
      }
  }

  override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
  override fun onProviderEnabled(provider: String) {}
  override fun onProviderDisabled(provider: String) {}

  // --- Unified Card Reader Implementation ---

  private fun readCardEmv(result: Result) {
      try {
          sendDebug("Waiting for Card (Swipe / Insert / Tap)...")
          val emv = mDeviceManager!!.getEmvKernelDevice()
          
          emv.stopCheckCard() // Reset

          // 1. Check Card Type first
          emv.checkCard(true, true, true, 60000, object : OnCheckCardResult {
              
              override fun onFindMagCard(trackData: CommonTrackData?) {
                  beepSuccess()
                  sendDebug("Mag Swipe Detected")
                  val sb = StringBuilder()
                  sb.append("Mag Read Success:\n")
                  
                  val cardNo = trackData?.cardNo?.let { String(it) } ?: "Unknown"
                  sb.append("Card No: ").append(cardNo).append("\n")
                  sendDebug("Card No: $cardNo")
                  
                  val expire = trackData?.expire?.let { String(it) } ?: "Unknown"
                  sb.append("Expiry: ").append(expire)

                  activity?.runOnUiThread { result.success(sb.toString()) } ?: result.success(sb.toString())
                  emv.stopCheckCard()
              }

              override fun onFindICCard() {
                   beepSuccess()
                   sendDebug("IC Card Detected - Starting EMV...")
                   emv.stopCheckCard()
                   startEmvProcess(EmvTransParam.EmvFlow.IC, result)
              }

              override fun onFindRFCard() {
                   beepSuccess()
                   sendDebug("RF Card Detected - Starting EMV...")
                   emv.stopCheckCard()
                   startEmvProcess(EmvTransParam.EmvFlow.RF, result)
              }

              override fun onError(i: Int, s: String?) {
                   sendDebug("Check Card Error: $i $s")
                   activity?.runOnUiThread { result.error("CHECK_ERROR", "Code: $i Msg: $s", null) } ?: result.error("CHECK_ERROR", "Code: $i Msg: $s", null)
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  private fun startEmvProcess(flow: EmvTransParam.EmvFlow, result: Result) {
      try {
          val emv = mDeviceManager!!.getEmvKernelDevice()
          
          // Need to post on executor or main? SDK is usually thread-safe for start
          val transParam = EmvTransParam.Builder()
             .setEmvFlow(flow)
             .setAmount("000000000001")
             .create()
          
          sendDebug("EMV Flow Started ($flow)")
          
          emv.processEmv(EmvTransType.SALE_GOODS, transParam, object : IEmvKernelListener.Stub() {
              
              override fun onRequestAmount(type: Int) {
                   sendDebug("EMV: Request Amount")
                   emv.importAmount("000000000001")
              }
              
              override fun onRequestAccount() {
                   sendDebug("EMV: Request Account")
                   emv.importAccountType(1)
              }
              
              override fun onRequestAidSelect(i: Int, aids: Array<String>?) {
                   sendDebug("EMV: Select AID")
                   emv.importAidSelectRes(0)
              }
              
              override fun onConfirmCardInfo(cardNo: String?) {
                   sendDebug("EMV: Card Info Confirmed")
                   sendDebug("Card No: $cardNo")
                   
                   val sb = StringBuilder()
                   sb.append(if (flow == EmvTransParam.EmvFlow.RF) "Tap (RF) " else "Insert (IC) ").append("Read Success:\n")
                   
                   // 1. Card Number (Masked/Unmasked handled by Kernel, usually clear here)
                   sb.append("Card No: ").append(cardNo).append("\n")

                   try {
                       // Define Tags to Read
                       // 57: Track 2
                       // 5F20: Cardholder Name
                       // 5F24: App Expiration Date
                       // 9F06: AID
                       // 50: App Label
                       // 9F12: App Preferred Name
                       // 5F30: Service Code
                       // 5F28: Issuer Country Code
                       val tags = arrayOf("57", "5F20", "5F24", "9F06", "50", "9F12", "5F30", "5F28")
                       
                       val tagData = emv.readEmvKernelData(tags) 
                       // Note: readEmvKernelData returns concatenated bytes found. 
                       // It's safer to read one by one if the SDK blindly concatenates, 
                       // BUT standard Centerm behavior: if array passed, it returns TLV or concatenated values?
                       // Actually readEmvKernelData(String[]) usually returns stream of values.
                       // Let's read individually to be safe and label them.
                       
                       // Track 2 (57) for Expiry
                       val track2 = emv.readEmvKernelData(arrayOf("57"))
                       if (track2 != null) {
                           val t2Str = HexUtils.bcd2str(track2)
                           if (t2Str.contains("D")) {
                               val parts = t2Str.split("D")
                               if (parts.size > 1 && parts[1].length >= 4) {
                                   val expiry = parts[1].substring(0, 4)
                                   sb.append("Expiry (YYMM): ").append(expiry).append("\n")
                                   sendDebug("Expiry: $expiry")
                               }
                           }
                       }
                       
                       // Cardholder Name (5F20)
                       val nameBytes = emv.readEmvKernelData(arrayOf("5F20"))
                       if (nameBytes != null) {
                           val name = String(nameBytes)
                           sb.append("Name: ").append(name).append("\n")
                           sendDebug("Name: $name")
                       }
                       
                       // AID (9F06)
                       val aidBytes = emv.readEmvKernelData(arrayOf("9F06"))
                       if (aidBytes != null) {
                           val aid = HexUtils.bcd2str(aidBytes)
                           sb.append("AID: ").append(aid).append("\n")
                           sendDebug("AID: $aid")
                       }

                       // Label (50)
                       val labelBytes = emv.readEmvKernelData(arrayOf("50"))
                       if (labelBytes != null) {
                           val label = String(labelBytes)
                           sb.append("Label: ").append(label).append("\n")
                           sendDebug("Label: $label")
                       }
                       
                       // Country Code (5F28)
                       val countryBytes = emv.readEmvKernelData(arrayOf("5F28"))
                       if (countryBytes != null) {
                           val country = HexUtils.bcd2str(countryBytes)
                           sb.append("Country Code: ").append(country)
                           sendDebug("Country: $country")
                       }
                       
                   } catch (e: Exception) {
                       sb.append("Data Read Error: ${e.message}")
                   }

                   activity?.runOnUiThread { 
                       result.success(sb.toString()) 
                       sendDebug("EMV: Finishing... (Aborting)")
                       executor.execute {
                           emv.abortEmv()
                       }
                   }
              }
              
              override fun onTransResult(res: Byte, msg: String?) {
                   sendDebug("EMV: Trans Result $res $msg")
              }

              override fun onError(i: Int, s: String?) {
                   sendDebug("EMV: Error $i $s")
              }

              override fun onRequestTipsConfirm(title: ByteArray?, content: ByteArray?) { 
                  emv.importMsgConfirmRes(true) 
              }
              override fun onRequestEcashTipsConfirm() { 
                  emv.importECashTipConfirmRes(false) 
              }
              override fun onRequestPin(isOnline: Boolean, amt: String?, count: Int) { 
                  sendDebug("EMV: Request PIN (Bypass)")
                  emv.importPin(true, null) 
              }
              override fun onRequestUserAuth(type: Int, content: ByteArray?) { 
                  emv.importUserAuthRes(true) 
              }
              override fun onRequestVoiceTipConfirm() { emv.importIssuerVoiceReference(1.toByte()) }
              override fun onConfirmFinalSelect(aid: ByteArray?) { 
                  emv.importFinalAidConfigsSelect(false, aid) 
              }
              override fun onSignatureRequest() { emv.importResultOfSignatureRequest(true) }
              override fun onCvmFlagVerify() { emv.importResultOfCvmFlagVerify(true) }
              override fun onRequestOnline() { 
                   sendDebug("EMV: Request Online")
                   emv.importOnlineResp(true, "00", null)
              }
              override fun onCommonConfirm(bytes: ByteArray?) {
                   sendDebug("EMV: Common Confirm")
                   emv.importCommonEventConfirm(1.toByte(), bytes)
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  private fun isRfCardPresent(result: Result) {
      try {
          // Correction: Use getRfDevice() as found in SDK
          val rf = mDeviceManager!!.getRfDevice()
          val isPresent = rf.exists()
          result.success(isPresent)
      } catch (e: Exception) {
          // Attempt fallbacks or detailed error
          result.error("RF_CHECK_ERROR", e.message, null)
      }
  }

  // --- Background QR Scanning ---
  private val CAMERA_PERMISSION_REQUEST_CODE = 998
  private var pendingQrScanResult: Result? = null
  private var pendingQrUseFrontCamera: Boolean = false
  private var pendingQrPeriodicRestartMs: Long = 60000L
  
  private fun startQrScan(call: MethodCall, result: Result) {
      try {
          val useFrontCamera = call.argument<Boolean>("isFrontCamera") ?: false
          val periodicRestartMs = call.argument<Int>("periodicRestartIntervalMs")?.toLong() ?: 60000L
          
          val currentActivity = activity
          if (currentActivity == null || currentActivity !is LifecycleOwner) {
              result.error("NO_LIFECYCLE", "Activity is not a LifecycleOwner", null)
              return
          }
          
          // Check camera permission
          if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                  != PackageManager.PERMISSION_GRANTED) {
              // Request permission
              pendingQrScanResult = result
              pendingQrUseFrontCamera = useFrontCamera
              pendingQrPeriodicRestartMs = periodicRestartMs
              ActivityCompat.requestPermissions(
                  currentActivity,
                  arrayOf(android.Manifest.permission.CAMERA),
                  CAMERA_PERMISSION_REQUEST_CODE
              )
              return
          }
          
          // Permission granted, proceed
          startQrScanning(currentActivity as LifecycleOwner, useFrontCamera, periodicRestartMs, result)
          
      } catch (e: Exception) {
          result.error("QR_START_ERROR", e.message, null)
      }
  }
  
  private fun startQrScanning(
    lifecycleOwner: LifecycleOwner, 
    useFrontCamera: Boolean,
    periodicRestartMs: Long,
    result: Result
) {
      if (qrScannerManager == null) {
          qrScannerManager = QrScannerManager(context)
      }
      
      qrScannerManager?.setResultListener { qrCode ->
          uiHandler.post {
              qrEventSink?.success(qrCode)
          }
      }
      
      // Connect debug listener to send logs to Flutter
      qrScannerManager?.setDebugListener { debugMsg ->
          uiHandler.post {
              eventSink?.success(debugMsg)
          }
      }
      
      qrScannerManager?.startScanning(lifecycleOwner, useFrontCamera, periodicRestartMs)
      result.success(true)
  }
  
  private fun stopQrScan(result: Result) {
      try {
          qrScannerManager?.stopScanning()
          result.success(true)
      } catch (e: Exception) {
          result.error("QR_STOP_ERROR", e.message, null)
      }
  }

  // --- Background NFC Polling ---
  
  private fun startNfcPolling(call: MethodCall, result: Result) {
      try {
          val intervalMs = call.argument<Int>("intervalMs")?.toLong() ?: 500L
          
          if (nfcPollingManager == null) {
              nfcPollingManager = NfcPollingManager(mDeviceManager)
          }
          
          nfcPollingManager?.setCardDetectedListener { isPresent ->
              uiHandler.post {
                  nfcEventSink?.success(isPresent)
              }
          }
          
          nfcPollingManager?.setDebugListener { debugMsg ->
              uiHandler.post {
                  eventSink?.success(debugMsg)
              }
          }
          
          nfcPollingManager?.startPolling(intervalMs)
          result.success(true)
          
      } catch (e: Exception) {
          result.error("NFC_START_ERROR", e.message, null)
      }
  }
  
  private fun stopNfcPolling(result: Result) {
      try {
          nfcPollingManager?.stopPolling()
          result.success(true)
      } catch (e: Exception) {
          result.error("NFC_STOP_ERROR", e.message, null)
      }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    // Force release all resources on dispose
    qrScannerManager?.forceStopAll()
    nfcPollingManager?.stopPolling()
    
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
      this.activity = binding.activity
      binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
      // Force release camera on config change
      qrScannerManager?.forceStopAll()
      this.activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
      this.activity = binding.activity
      binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivity() {
      // Force release all resources when activity is destroyed
      qrScannerManager?.forceStopAll()
      nfcPollingManager?.stopPolling()
      this.activity = null
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
      if (requestCode == GPS_PERMISSION_REQUEST_CODE) {
          if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
              pendingLocationResult?.let { getLocation(it) }
          } else {
              pendingLocationResult?.error("PERMISSION_DENIED", "Location permission denied by user", null)
          }
          pendingLocationResult = null
          return true
      }
      if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
          val result = pendingQrScanResult
          if (result != null) {
              if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                  // Permission granted, start scanning
                  val currentActivity = activity
                  if (currentActivity != null && currentActivity is LifecycleOwner) {
                      startQrScanning(currentActivity as LifecycleOwner, pendingQrUseFrontCamera, result)
                  } else {
                      result.error("NO_ACTIVITY", "Activity not available", null)
                  }
              } else {
                  result.error("PERMISSION_DENIED", "Camera permission denied by user", null)
              }
              pendingQrScanResult = null
          }
          return true
      }
      return false
  }
}
