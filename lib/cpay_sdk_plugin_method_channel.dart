import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'cpay_sdk_plugin_platform_interface.dart';

/// An implementation of [CpaySdkPluginPlatform] that uses method channels.
class MethodChannelCpaySdkPlugin extends CpaySdkPluginPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('cpay_sdk_plugin');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }

  @override
  Future<String?> getSystemInfo() async {
    final info = await methodChannel.invokeMethod<String>('getSystemInfo');
    return info;
  }

  @override
  Future<bool?> printText(String content) async {
    final result = await methodChannel.invokeMethod<bool>('printText', {
      'content': content,
    });
    return result;
  }

  @override
  Future<String?> scan({bool isFrontCamera = false}) async {
    final result = await methodChannel.invokeMethod<String>('scan', {
      'isFrontCamera': isFrontCamera,
    });
    return result;
  }

  @override
  Future<bool?> beep() async {
    final result = await methodChannel.invokeMethod<bool>('beep');
    return result;
  }

  @override
  Future<String?> checkCard() async {
    final result = await methodChannel.invokeMethod<String>('checkCard');
    return result;
  }

  @override
  Future<String?> readCardEmv() async {
    final result = await methodChannel.invokeMethod<String>('readCardEmv');
    return result;
  }

  @override
  Stream<String> get onDebugLog {
    return const EventChannel(
      'cpay_sdk_plugin/events',
    ).receiveBroadcastStream().cast<String>();
  }
}
