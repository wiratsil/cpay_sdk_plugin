import 'package:flutter_test/flutter_test.dart';
import 'package:cpay_sdk_plugin/cpay_sdk_plugin.dart';
import 'package:cpay_sdk_plugin/cpay_sdk_plugin_platform_interface.dart';
import 'package:cpay_sdk_plugin/cpay_sdk_plugin_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockCpaySdkPluginPlatform
    with MockPlatformInterfaceMixin
    implements CpaySdkPluginPlatform {
  @override
  @override
  Future<String?> getPlatformVersion() => Future.value('42');

  @override
  Future<String?> getSystemInfo() => Future.value('Serial123');

  @override
  Future<bool?> printText(String content) => Future.value(true);

  @override
  Future<String?> scan({bool isFrontCamera = false}) =>
      Future.value('ScanResult123');

  @override
  Future<bool?> beep() => Future.value(true);

  @override
  Future<String?> checkCard() => Future.value('Card1234');

  @override
  Future<String?> readCardEmv() => Future.value('EmvData123');

  @override
  Stream<String> get onDebugLog => const Stream.empty();
}

void main() {
  final CpaySdkPluginPlatform initialPlatform = CpaySdkPluginPlatform.instance;

  test('$MethodChannelCpaySdkPlugin is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelCpaySdkPlugin>());
  });

  test('getPlatformVersion', () async {
    CpaySdkPlugin cpaySdkPlugin = CpaySdkPlugin();
    MockCpaySdkPluginPlatform fakePlatform = MockCpaySdkPluginPlatform();
    CpaySdkPluginPlatform.instance = fakePlatform;

    expect(await cpaySdkPlugin.getPlatformVersion(), '42');
  });

  test('getSystemInfo', () async {
    CpaySdkPlugin cpaySdkPlugin = CpaySdkPlugin();
    MockCpaySdkPluginPlatform fakePlatform = MockCpaySdkPluginPlatform();
    CpaySdkPluginPlatform.instance = fakePlatform;

    expect(await cpaySdkPlugin.getSystemInfo(), 'Serial123');
  });
}
