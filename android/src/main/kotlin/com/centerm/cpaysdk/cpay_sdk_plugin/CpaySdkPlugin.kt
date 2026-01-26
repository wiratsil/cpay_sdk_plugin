package com.centerm.cpaysdk.cpay_sdk_plugin

import android.app.Activity
import android.content.Context
import androidx.annotation.NonNull
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel

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
import java.util.concurrent.Executors

/** CpaySdkPlugin */
class CpaySdkPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler {
  private lateinit var channel : MethodChannel
  private lateinit var eventChannel : EventChannel
  private lateinit var context: Context
  private var activity: Activity? = null
  private var mDeviceManager: DeviceManager? = null
  private val executor = Executors.newSingleThreadExecutor()
  private var eventSink: EventChannel.EventSink? = null
  private val uiHandler = Handler(Looper.getMainLooper())

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin")
    channel.setMethodCallHandler(this)
    
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin/events")
    eventChannel.setStreamHandler(this)

    context = flutterPluginBinding.applicationContext
    initSdk()
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
            scan(result)
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

  private fun scan(result: Result) {
      try {
          val scanner = mDeviceManager!!.getScanDevice()
          val params = Bundle()
          params.putInt(IScanner.CAMERA_ID, 1) // Back camera
          params.putInt(IScanner.TIMEOUT, 60000)
          
          scanner.scan(params, object : IScanCallback.Stub() {
              override fun onSuccess(bytes: ByteArray?) {
                  beepSuccess()
                  val scanResult = if (bytes != null) String(bytes) else ""
                  activity?.runOnUiThread { result.success(scanResult) } ?: result.success(scanResult)
              }

              override fun onFailed(i: Int, s: String?) {
                  activity?.runOnUiThread { result.error("SCAN_ERROR", "Code: $i Msg: $s", null) } ?: result.error("SCAN_ERROR", "Code: $i Msg: $s", null)
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

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
      this.activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
      this.activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
      this.activity = binding.activity
  }

  override fun onDetachedFromActivity() {
      this.activity = null
  }
}
