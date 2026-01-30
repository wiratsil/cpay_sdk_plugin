import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'cpay_sdk_plugin_method_channel.dart';

abstract class CpaySdkPluginPlatform extends PlatformInterface {
  /// Constructs a CpaySdkPluginPlatform.
  CpaySdkPluginPlatform() : super(token: _token);

  static final Object _token = Object();

  static CpaySdkPluginPlatform _instance = MethodChannelCpaySdkPlugin();

  /// The default instance of [CpaySdkPluginPlatform] to use.
  ///
  /// Defaults to [MethodChannelCpaySdkPlugin].
  static CpaySdkPluginPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [CpaySdkPluginPlatform] when
  /// they register themselves.
  static set instance(CpaySdkPluginPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<String?> getSystemInfo() {
    throw UnimplementedError('getSystemInfo() has not been implemented.');
  }

  Future<bool?> printText(String content) {
    throw UnimplementedError('printText() has not been implemented.');
  }

  Future<String?> scan({bool isFrontCamera = false, int timeout = 60000}) {
    throw UnimplementedError('scan() has not been implemented.');
  }

  Future<bool?> beep() {
    throw UnimplementedError('beep() has not been implemented.');
  }

  Future<String?> checkCard() {
    throw UnimplementedError('checkCard() has not been implemented.');
  }

  Future<String?> readCardEmv() {
    throw UnimplementedError('readCardEmv() has not been implemented.');
  }

  Stream<String> get onDebugLog {
    throw UnimplementedError('onDebugLog has not been implemented.');
  }

  Future<String?> getLocation() {
    throw UnimplementedError('getLocation() has not been implemented.');
  }

  Future<bool?> startLocationService() {
    throw UnimplementedError(
      'startLocationService() has not been implemented.',
    );
  }

  Future<bool?> stopLocationService() {
    throw UnimplementedError('stopLocationService() has not been implemented.');
  }

  Future<bool?> isRfCardPresent() {
    throw UnimplementedError('isRfCardPresent() has not been implemented.');
  }
}
