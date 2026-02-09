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

  // Background QR Scanning
  StreamSubscription<String>? _qrSubscription;
  bool _isQrScanning = false;

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

  Future<void> _scanTest({bool isFront = false, int timeout = 10000}) async {
    _logBuffer.clear();
    _appendLog(
      'Scanning (${isFront ? "Front" : "Back"}) Timeout: ${timeout}ms...',
    );
    try {
      String? result = await _cpaySdkPlugin.scan(
        isFrontCamera: isFront,
        timeout: timeout,
      );
      if (result == null) {
        _appendLog('Scan Cancelled/Timeout');
        setState(() => _lastResult = "SCAN: Cancelled/Timeout");
      } else {
        _appendLog('Scan Result: $result');
        setState(() => _lastResult = "SCAN:\n$result");
      }
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

  Future<void> _getLocationTest() async {
    _logBuffer.clear();
    _appendLog('Getting Location (GPS/Network)...');
    try {
      String? result = await _cpaySdkPlugin.getLocation();
      _appendLog('Location: $result');
      setState(() => _lastResult = "GPS:\n$result");
    } catch (e) {
      _appendLog('Location Error: $e');
    }
  }

  Future<void> _checkRfTest() async {
    _appendLog('Checking RF Presence...');
    try {
      bool? exists = await _cpaySdkPlugin.isRfCardPresent();
      _appendLog('RF Card Present: $exists');
      if (exists == true) {
        setState(() => _lastResult = "RF: PRESENT");
      } else {
        setState(() => _lastResult = "RF: NOT FOUND");
      }
    } catch (e) {
      _appendLog('RF Check Error: $e');
    }
  }

  Future<void> _monitorLocTest(bool start) async {
    try {
      if (start) {
        await _cpaySdkPlugin.startLocationService();
        _appendLog('Monitoring Started. GPS Warming up...');
      } else {
        await _cpaySdkPlugin.stopLocationService();
        _appendLog('Monitoring Stopped.');
      }
    } catch (e) {
      _appendLog('Monitor Error: $e');
    }
  }

  // Background QR Scanning
  Future<void> _startQrScan({bool isFront = true}) async {
    if (_isQrScanning) {
      _appendLog('Already scanning...');
      return;
    }
    _appendLog(
      'Starting Background QR Scan (${isFront ? "Front" : "Back"})...',
    );
    try {
      // Start listening to QR events
      _qrSubscription = _cpaySdkPlugin.onQrCodeDetected.listen((qrCode) {
        _appendLog('QR Detected: $qrCode');
        setState(() => _lastResult = "QR: $qrCode");
      });

      bool? started = await _cpaySdkPlugin.startQrScan(isFrontCamera: isFront);
      if (started == true) {
        setState(() => _isQrScanning = true);
        _appendLog('QR Scan Started - Point camera at QR code');
      } else {
        _appendLog('Failed to start QR scan');
        _qrSubscription?.cancel();
      }
    } catch (e) {
      _appendLog('QR Start Error: $e');
      _qrSubscription?.cancel();
    }
  }

  Future<void> _stopQrScan() async {
    _appendLog('Stopping QR Scan...');
    try {
      await _cpaySdkPlugin.stopQrScan();
      _qrSubscription?.cancel();
      _qrSubscription = null;
      setState(() => _isQrScanning = false);
      _appendLog('QR Scan Stopped');
    } catch (e) {
      _appendLog('QR Stop Error: $e');
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
                      Container(
                        height: 150,
                        width: double.infinity,
                        decoration: BoxDecoration(
                          color: Colors.grey.shade100,
                          borderRadius: BorderRadius.circular(4),
                          border: Border.all(color: Colors.grey.shade300),
                        ),
                        child: SingleChildScrollView(
                          reverse: true,
                          padding: const EdgeInsets.all(8),
                          child: Text(
                            _statusMessage,
                            textAlign: TextAlign.start,
                            style: const TextStyle(
                              color: Colors.blue,
                              fontSize: 12,
                              fontFamily: 'Monospace',
                            ),
                          ),
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
                        ElevatedButton(
                          onPressed: () =>
                              _scanTest(isFront: false, timeout: 10000),
                          child: const Text(
                            "Start Scanner (Back, 10s Timeout)",
                          ),
                        ),
                      ]),
                      _buildActionCard("Card Reader", [
                        const Text("Supports Mag, IC, RF"),
                        ElevatedButton(
                          onPressed: _cardTest,
                          child: const Text("Check Card (Basic)"),
                        ),
                        ElevatedButton(
                          onPressed: _checkRfTest,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.orange,
                          ),
                          child: const Text("Check RF Present (Fast)"),
                        ),
                        ElevatedButton(
                          onPressed: _readCardEmvTest,
                          child: const Text(
                            "Read Chip/NFC Detail (All-in-One)",
                          ),
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
                      _buildActionCard("Location", [
                        ElevatedButton(
                          onPressed: _getLocationTest,
                          child: const Text(
                            "Get GPS Location (Instant if Monitored)",
                          ),
                        ),
                        // Row for Start/Stop Monitor
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                          children: [
                            ElevatedButton(
                              onPressed: () => _monitorLocTest(true),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.green,
                              ),
                              child: const Text("Start Monitor"),
                            ),
                            ElevatedButton(
                              onPressed: () => _monitorLocTest(false),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.red,
                              ),
                              child: const Text("Stop Monitor"),
                            ),
                          ],
                        ),
                      ]),
                      _buildActionCard("Background QR Scan", [
                        Text(
                          _isQrScanning
                              ? "Status: SCANNING"
                              : "Status: STOPPED",
                          style: TextStyle(
                            color: _isQrScanning ? Colors.green : Colors.grey,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                          children: [
                            ElevatedButton(
                              onPressed: _isQrScanning
                                  ? null
                                  : () => _startQrScan(isFront: true),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.green,
                              ),
                              child: const Text("Start (Front)"),
                            ),
                            ElevatedButton(
                              onPressed: _isQrScanning ? _stopQrScan : null,
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.red,
                              ),
                              child: const Text("Stop"),
                            ),
                          ],
                        ),
                      ]),
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
