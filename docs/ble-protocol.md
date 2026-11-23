# BLE Communication Protocol Specification (`shared-protocol`)

## 1. GATT Service & Characteristics
- **Service UUID:** `7D2E0001-8F3A-4C9B-B4E1-9A60C30F0000`
  1. `COMMAND` (`7D2E0002-8F3A-4C9B-B4E1-9A60C30F0000`): Write / Write Without Response
  2. `STATUS` (`7D2E0003-8F3A-4C9B-B4E1-9A60C30F0000`): Read + Notify (CCCD `0x2902`)
  3. `CONFIG` (`7D2E0004-8F3A-4C9B-B4E1-9A60C30F0000`): Read + Write
  4. `ACKNOWLEDGEMENT` (`7D2E0005-8F3A-4C9B-B4E1-9A60C30F0000`): Read + Notify (CCCD `0x2902`)
  5. `SYNC` (`7D2E0006-8F3A-4C9B-B4E1-9A60C30F0000`): Read + Notify (CCCD `0x2902`)
  6. `DEVICE_INFO` (`7D2E0007-8F3A-4C9B-B4E1-9A60C30F0000`): Read

## 2. Versioned Envelope & Security
- Every message uses a versioned binary envelope (`magic = 0x504D`, `protocolVersion = 1`, `flags`, `msgType`, `opCode`, `commandId (UUID)`, `sequenceNumber (Long)`, `timestampMs (Long)`, `ttlMs (Int)`, `payloadLength (Int)`, `payload`, `hmacTag (32B HMAC-SHA256)`).
- ATT MTU fragmentation wraps large frames with `(frameMsgId, chunkIndex, totalChunks, sliceBytes)`.
- Pairing uses ECDH P-256 + out-of-band SAS/QR confirmation + Android Bluetooth bonding (`createBond()`).
- Per-connection session auth uses challenge-response nonces (`STATUS_AUTH_CHALLENGE` → `AUTH_VERIFY`) deriving ephemeral session HMAC + AES-GCM keys.

## 3. Commands & Error Codes
- Commands: `START_RECORDING`, `STOP_RECORDING`, `GET_STATUS`, `GET_BATTERY`, `GET_STORAGE`, `GET_DEVICE_INFO`, `SYNC_DATA`, `SET_CONFIG`, `PING`.
- Explicit Errors: `ERR_SERVICE_NOT_RUNNING`, `ERR_USER_INTERACTION_REQUIRED`, `ERR_PERMISSION_MISSING`, `ERR_AUTH_FAILED`, `ERR_REPLAY_DETECTED`, `ERR_EXPIRED`, `ERR_STORAGE_FULL`, `ERR_BUSY`, `ERR_INVALID_ARGUMENT`, `ERR_UNSUPPORTED_VERSION`.

## 4. High-Throughput Audio Sync
- **Primary:** BLE L2CAP Connection-Oriented Channel (`BluetoothAdapter.listenUsingL2capChannel()` / `BluetoothDevice.createL2capChannel(psm)`, API 29+) negotiated via `SYNC_DATA`.
- **Fallback:** GATT `SYNC` chunked notifications with byte-offset resume and SHA-256 integrity verification before `SYNC_ACK`.
