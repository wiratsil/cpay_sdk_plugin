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

  void _appendLog(String message) {
    debugPrint('App Log: $message');
    if (!mounted) return;

    _logBuffer.add(message);
    if (_logBuffer.length > 10) {
      _logBuffer.removeAt(0);
    }

    setState(() {
      _statusMessage = _logBuffer.join('\n');
    });
  }

  Future<void> initPlatformState() async {
    // Listen to debug logs
    _cpaySdkPlugin.onDebugLog.listen((log) {
      _appendLog(log);
    });
  }

  Future<void> _getSystemInfo() async {
    _logBuffer.clear();
    _appendLog("Getting System Info...");
    try {
      String info = await _cpaySdkPlugin.getSystemInfo() ?? 'Unknown';
      _appendLog("Info: $info");
      setState(() => _systemInfo = info);
    } catch (e) {
      _appendLog('Error: $e');
    }
  }

  Future<void> _printTest() async {
    _logBuffer.clear();
    _appendLog('Printing...');
    try {
      bool? result = await _cpaySdkPlugin.printText(
        "Hello Flutter!\n\nExpanded Feature Test.\n\n\n\n",
      );
      _appendLog(result == true ? 'Print Success' : 'Print Failed');
    } catch (e) {
      _appendLog('Print Error: $e');
    }
  }

  Future<void> _printLastResult() async {
    if (_lastResult.isEmpty) {
      _appendLog('No details to print!');
      return;
    }
    _logBuffer.clear();
    _appendLog('Printing Details...');
    try {
      // Add some header/footer
      String content = "\n--- DETAIL ---\n\n$_lastResult\n\n\n\n\n";
      await _cpaySdkPlugin.printText(content);
      _appendLog('Print Success');
    } catch (e) {
      _appendLog('Print Error: $e');
    }
  }

  Future<void> _scanTest({bool isFront = false}) async {
    _logBuffer.clear();
    _appendLog('Scanning (${isFront ? "Front" : "Back"})...');
    try {
      String? result = await _cpaySdkPlugin.scan(isFrontCamera: isFront);
      _appendLog('Scan Result: $result');
      setState(() => _lastResult = "SCAN:\n$result");
    } catch (e) {
      _appendLog('Scan Error: $e');
    }
  }

  Future<void> _beepTest() async {
    _logBuffer.clear();
    _appendLog('Beeping...');
    try {
      await _cpaySdkPlugin.beep();
      _appendLog('Beep Command Sent');
    } catch (e) {
      _appendLog('Beep Error: $e');
    }
  }

  Future<void> _cardTest() async {
    _logBuffer.clear();
    _appendLog('Please Swipe/Insert/Tap Card...');
    try {
      String? result = await _cpaySdkPlugin.checkCard();
      _appendLog('Card Result: $result');
      setState(() => _lastResult = result ?? '');
    } catch (e) {
      _appendLog('Card Error: $e');
    }
  }

  Future<void> _readCardEmvTest() async {
    _logBuffer.clear();
    _appendLog('Insert/Tap Card for EMV Read...');
    try {
      String? result = await _cpaySdkPlugin.readCardEmv();
      _appendLog('Result Matches Logs.');
      // Update last result for storage
      setState(() => _lastResult = result ?? '');
    } catch (e) {
      _appendLog('EMV Error: $e');
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
                          onPressed: () => _scanTest(isFront: false),
                          child: const Text("Start Scanner (Back)"),
                        ),
                        ElevatedButton(
                          onPressed: () => _scanTest(isFront: true),
                          child: const Text("Start Scanner (Front)"),
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
