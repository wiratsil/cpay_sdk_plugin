import 'package:cpay_sdk_plugin/cpay_sdk_plugin_platform_interface.dart';

class CpaySdkPlugin {
  Future<String?> getPlatformVersion() {
    return CpaySdkPluginPlatform.instance.getPlatformVersion();
  }

  Future<String?> getSystemInfo() {
    return CpaySdkPluginPlatform.instance.getSystemInfo();
  }

  Future<bool?> printText(String content) {
    return CpaySdkPluginPlatform.instance.printText(content);
  }

  Future<String?> scan({bool isFrontCamera = false, int timeout = 60000}) {
    return CpaySdkPluginPlatform.instance.scan(
      isFrontCamera: isFrontCamera,
      timeout: timeout,
    );
  }

  Future<bool?> beep() {
    return CpaySdkPluginPlatform.instance.beep();
  }

  Future<String?> checkCard() {
    return CpaySdkPluginPlatform.instance.checkCard();
  }

  Future<String?> readCardEmv() {
    return CpaySdkPluginPlatform.instance.readCardEmv();
  }

  Stream<String> get onDebugLog {
    return CpaySdkPluginPlatform.instance.onDebugLog;
  }

  Future<String?> getLocation() {
    return CpaySdkPluginPlatform.instance.getLocation();
  }

  Future<bool?> startLocationService() {
    return CpaySdkPluginPlatform.instance.startLocationService();
  }

  Future<bool?> stopLocationService() {
    return CpaySdkPluginPlatform.instance.stopLocationService();
  }

  Future<bool?> isRfCardPresent() {
    return CpaySdkPluginPlatform.instance.isRfCardPresent();
  }

  // Background QR Scanning
  Future<bool?> startQrScan({bool isFrontCamera = false}) {
    return CpaySdkPluginPlatform.instance.startQrScan(
      isFrontCamera: isFrontCamera,
    );
  }

  Future<bool?> stopQrScan() {
    return CpaySdkPluginPlatform.instance.stopQrScan();
  }

  Stream<String> get onQrCodeDetected {
    return CpaySdkPluginPlatform.instance.onQrCodeDetected;
  }
}
