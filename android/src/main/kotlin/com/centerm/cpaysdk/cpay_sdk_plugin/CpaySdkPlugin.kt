package com.centerm.cpaysdk.cpay_sdk_plugin

import android.app.Activity
import android.content.Context
import androidx.annotation.NonNull
import android.os.Bundle
import android.os.RemoteException

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

import com.pos.sdk.DevicesFactory
import com.pos.sdk.DeviceManager
import com.pos.sdk.callback.ResultCallback
import com.pos.sdk.printer.PrinterDevice
import com.pos.sdk.printer.param.TextPrintItemParam
import com.pos.sdk.printer.param.PrintItemAlign
import com.pos.sdk.printer.IPrinterResultListener
import com.pos.sdk.sys.SystemDevice

/** CpaySdkPlugin */
class CpaySdkPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var channel : MethodChannel
  private lateinit var context: Context
  private var activity: Activity? = null
  private var mDeviceManager: DeviceManager? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cpay_sdk_plugin")
    channel.setMethodCallHandler(this)
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

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
     if (mDeviceManager == null) {
          result.error("SDK_NOT_READY", "Device Manager is not initialized", null)
          return
     }

    if (call.method == "getSystemInfo") {
        val sys = mDeviceManager!!.getSystemDevice()
        val sn = sys.getSerialNo()
        result.success(sn)
    } else if (call.method == "printText") {
        val content = call.argument<String>("content")
        if (content != null) {
            printText(content, result)
        } else {
             result.error("INVALID_ARGUMENT", "Content cannot be null", null)
        }
    } else {
      result.notImplemented()
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
                   // Ensure UI thread if needed, though simple success is usually fine
                   result.success(true)
              }

              override fun onPrintError(i: Int, s: String) {
                   result.error("PRINT_ERROR", "Code: $i Msg: $s", null)
              }
          })
      } catch (e: Exception) {
          result.error("EXCEPTION", e.message, null)
      }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
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
