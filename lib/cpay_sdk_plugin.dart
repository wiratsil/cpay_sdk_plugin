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
}
