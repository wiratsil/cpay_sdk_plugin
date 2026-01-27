# CPaySDK Flutter Plugin

A Flutter plugin for integrating with Centerm POS devices, providing access to hardware features like card readers, printers, scanners, and more.

## Features

- 📋 **System Info** - Get device serial number and info
- 🖨️ **Printer** - Print text receipts
- 📷 **Scanner** - Barcode/QR code scanning
- 🔊 **Beep** - Audio feedback
- 💳 **Card Reader** - Read Mag stripe, IC chip, and NFC/RF cards
- 📊 **EMV Processing** - Full card data extraction (PAN, Expiry, Name, AID, etc.)
- 🔄 **Real-time Logs** - Debug stream for monitoring operations

## Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  cpay_sdk_plugin:
    path: ../cpay_sdk_plugin  # or git URL
```

## Platform Support

| Platform | Support |
|----------|---------|
| Android  | ✅ (Centerm devices) |
| iOS      | ❌ |

## Usage

### Initialize

```dart
import 'package:cpay_sdk_plugin/cpay_sdk_plugin.dart';

final _plugin = CpaySdkPlugin();
```

### Listen to Debug Logs

```dart
_plugin.onDebugLog.listen((log) {
  print('SDK Log: $log');
});
```

### Get System Info

```dart
String? sn = await _plugin.getSystemInfo();
print('Serial Number: $sn');
```

### Print Text

```dart
bool? success = await _plugin.printText("Hello World!\n\n\n");
```

### Scan Barcode/QR

```dart
// Scan with Back Camera (Default)
String? result = await _plugin.scan();

// Scan with Front Camera
String? resultFront = await _plugin.scan(isFrontCamera: true);

print('Scanned: $result');
```

### Beep

```dart
await _plugin.beep();
```

### Basic Card Check

Detects card type only (Mag/IC/RF):

```dart
String? result = await _plugin.checkCard();
// Returns: "Mag Swipe Detected" / "IC Card Detected" / "RF Card Detected"
```

### Read Card Details (Recommended)

Full card reading with EMV processing:

```dart
String? result = await _plugin.readCardEmv();
// Returns detailed info:
// - Card No
// - Expiry (YYMM)
// - Cardholder Name
// - AID
// - Label
// - Country Code
```

## EMV Data Available

| Tag | Description | Example |
|-----|-------------|---------|
| 57 | Track 2 (PAN + Expiry) | `4732...D2512...` |
| 5F20 | Cardholder Name | `SOMCHAI` |
| 5F24 | Expiration Date | `251231` |
| 9F06 | AID | `A0000000031010` |
| 50 | Application Label | `VISA CREDIT` |
| 5F28 | Country Code | `0764` |

## Audio Feedback

The plugin automatically beeps on:
- ✅ Successful scan
- ✅ Card detected (Swipe/Insert/Tap)

## Example App

See `/example` folder for a complete demo application.

## Troubleshooting

### SDK Not Ready Error
Make sure the app is running on a compatible Centerm device with the SDK service installed.

### Card Read Timeout
- Ensure card is properly inserted/tapped
- For IC cards, keep the card inserted until process completes
- For NFC, hold card steady for 2-3 seconds

### Build Errors
Ensure `CpaySDKLibV4.0.9_20230420.jar` is in `android/libs/` folder.

## License

Proprietary - For use with Centerm devices only.
