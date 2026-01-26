import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:cpay_sdk_plugin/cpay_sdk_plugin_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  MethodChannelCpaySdkPlugin platform = MethodChannelCpaySdkPlugin();
  const MethodChannel channel = MethodChannel('cpay_sdk_plugin');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        return '42';
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    expect(await platform.getPlatformVersion(), '42');
  });
}
