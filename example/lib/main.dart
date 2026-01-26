import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:cpay_sdk_plugin/cpay_sdk_plugin.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _systemInfo = 'Unknown';
  String _statusMessage = 'Ready';
  final List<String> _logBuffer = [];
  String _lastResult = '';
  final _cpaySdkPlugin = CpaySdkPlugin();

  @override
  void initState() {
    super.initState();
    initPlatformState();
  }

  Future<void> initPlatformState() async {
    // Listen to debug logs
    _cpaySdkPlugin.onDebugLog.listen((log) {
      debugPrint('App Log: $log');
      if (!mounted) return;

      _logBuffer.add(log);
      if (_logBuffer.length > 5) {
        _logBuffer.removeAt(0);
      }

      setState(() {
        _statusMessage = _logBuffer.join('\n');
      });
    });
  }

  Future<void> _getSystemInfo() async {
    setState(() => _statusMessage = "Getting System Info...");
    String info;
    try {
      info = await _cpaySdkPlugin.getSystemInfo() ?? 'Unknown';
      setState(() {
        _systemInfo = info;
        _statusMessage = "Info Retrieved";
      });
    } catch (e) {
      setState(() => _statusMessage = 'Error: $e');
    }
  }

  Future<void> _printTest() async {
    setState(() => _statusMessage = 'Printing...');
    try {
      bool? result = await _cpaySdkPlugin.printText(
        "Hello Flutter!\n\nExpanded Feature Test.\n\n\n\n",
      );
      setState(
        () =>
            _statusMessage = result == true ? 'Print Success' : 'Print Failed',
      );
    } catch (e) {
      setState(() => _statusMessage = 'Print Error: $e');
    }
  }

  Future<void> _printLastResult() async {
    if (_lastResult.isEmpty) {
      setState(() => _statusMessage = 'No details to print!');
      return;
    }
    setState(() => _statusMessage = 'Printing Details...');
    try {
      // Add some header/footer
      String content = "\n--- DETAIL ---\n\n$_lastResult\n\n\n\n\n";
      await _cpaySdkPlugin.printText(content);
      setState(() => _statusMessage = 'Print Success');
    } catch (e) {
      setState(() => _statusMessage = 'Print Error: $e');
    }
  }

  Future<void> _scanTest() async {
    setState(() => _statusMessage = 'Scanning... (Press scan button)');
    try {
      String? result = await _cpaySdkPlugin.scan();
      setState(() {
        _statusMessage = 'Scan Result: $result';
        _lastResult = "SCAN:\n$result";
      });
    } catch (e) {
      setState(() => _statusMessage = 'Scan Error: $e');
    }
  }

  Future<void> _beepTest() async {
    setState(() => _statusMessage = 'Beeping...');
    try {
      await _cpaySdkPlugin.beep();
      setState(() => _statusMessage = 'Beep Command Sent');
    } catch (e) {
      setState(() => _statusMessage = 'Beep Error: $e');
    }
  }

  Future<void> _cardTest() async {
    setState(() => _statusMessage = 'Please Swipe/Insert/Tap Card...');
    try {
      String? result = await _cpaySdkPlugin.checkCard();
      setState(() {
        _statusMessage = 'Card Result: $result';
        _lastResult = result ?? '';
      });
    } catch (e) {
      setState(() => _statusMessage = 'Card Error: $e');
    }
  }

  Future<void> _readCardEmvTest() async {
    setState(() => _statusMessage = 'Insert/Tap Card for EMV Read...');
    try {
      String? result = await _cpaySdkPlugin.readCardEmv();
      setState(() {
        _statusMessage = 'EMV Result: $result';
        _lastResult = result ?? '';
      });
    } catch (e) {
      setState(() => _statusMessage = 'EMV Error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('CpaySDK Full Test')),
        body: Column(
          children: [
            // Sticky Status Card
            Padding(
              padding: const EdgeInsets.all(16),
              child: Card(
                color: Colors.blue.shade50,
                elevation: 4,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      const Text(
                        "Status",
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 18,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _statusMessage,
                        textAlign: TextAlign.start,
                        style: const TextStyle(
                          color: Colors.blue,
                          fontSize: 14,
                          fontFamily: 'Monospace',
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            // Scrollable Content
            Expanded(
              child: SingleChildScrollView(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _buildActionCard("System", [
                        Text("Serial: $_systemInfo"),
                        ElevatedButton(
                          onPressed: _getSystemInfo,
                          child: const Text("Get Info"),
                        ),
                        ElevatedButton(
                          onPressed: _beepTest,
                          child: const Text("Beep Test"),
                        ),
                      ]),
                      _buildActionCard("Printer", [
                        ElevatedButton(
                          onPressed: _printTest,
                          child: const Text("Print Test Text"),
                        ),
                        const SizedBox(height: 8),
                        ElevatedButton(
                          onPressed: _printLastResult,
                          child: const Text("Print Details (Card/Scan)"),
                        ),
                      ]),
                      _buildActionCard("Scanner", [
                        ElevatedButton(
                          onPressed: _scanTest,
                          child: const Text("Start Scanner"),
                        ),
                      ]),
                      _buildActionCard("Card Reader", [
                        const Text("Supports Mag, IC, RF"),
                        ElevatedButton(
                          onPressed: _cardTest,
                          child: const Text("Check Card (Basic)"),
                        ),
                        const SizedBox(height: 8),
                        ElevatedButton(
                          onPressed: _readCardEmvTest,
                          child: const Text(
                            "Read Chip/NFC Detail (All-in-One)",
                          ),
                        ),
                      ]),
                      const SizedBox(height: 20),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionCard(String title, List<Widget> children) {
    return Card(
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Text(
              title,
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Divider(),
            ...children.map(
              (w) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: w,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
