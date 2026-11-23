# Android Compatibility & Non-Guaranteed Platform Behaviors

## 1. Target & SDK Matrix
- `minSdk = 29` (Android 10)
- `compileSdk = 36` (Android 16)
- `targetSdk = 36` (Android 16)

| OS Level | Relevant Platform Rule | Architecture Adaptation |
|---|---|---|
| Android 10 (29) | `isClientSilenced()`, `PowerManager.getCurrentThermalStatus()`, BLE L2CAP CoC | AudioRecordingCallback & L2CAP CoC available across all supported devices. |
| Android 12 (31) | Runtime `BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`; FGS background launch limits | `neverForLocation` on `BLUETOOTH_SCAN`; visible Device B interaction required to arm mic FGS. |
| Android 13 (33) | `POST_NOTIFICATIONS` runtime permission; byte-array GATT APIs | Guided onboarding requests `POST_NOTIFICATIONS`; SDK-gated BLE API wrappers. |
| Android 14 (34) | Mandatory `foregroundServiceType` + while-in-use permission check; `RECEIVER_EXPORTED/NOT_EXPORTED` | `RecorderService` uses `microphone` FGS type only; explicit receiver export flags. |
| Android 15 (35) | `BOOT_COMPLETED` cannot start `microphone` or `dataSync` FGS; `dataSync` capped at 6h/24h | Never use `dataSync` for continuous recorder/BLE; `BootCheckReceiver` only inspects interruption & posts non-FGS reminder. |
| Android 16 (36) | Concurrent FGS JobScheduler quotas; `BluetoothDevice.ACTION_KEY_MISSING` | Short chunked background workers; clean bond-loss detection without aggressive reconnect loops. |

## 2. Explicit Non-Guaranteed Behaviors
1. Background BLE commands cannot force-start a stopped `microphone` FGS on Android 12–16.
2. OEM task killers (Xiaomi/HyperOS, Oppo/OnePlus ColorOS/OxygenOS, Vivo, Samsung Sleeping Apps) may terminate persistent services despite `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
3. Cellular/VoIP calls or privileged system mic clients silence `AudioRecord` (`isClientSilenced() == true`); call audio is never captured.
4. Force-stop or device reboot halts execution; reboot never silently restarts microphone capture.
5. BLE background auto-reconnection latency and GATT/L2CAP throughput depend on radio conditions, Doze state, and chipset firmware.
