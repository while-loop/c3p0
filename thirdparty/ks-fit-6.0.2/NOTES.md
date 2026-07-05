# KS Fit 6.0.2 Findings

These are the WalkingPad-relevant findings from the KS Fit 6.0.2 APK.

## App Identity

- Package: `com.kingsmith.xiaojin`
- App label: `KS Fit`
- Version: `6.0.2`
- Version code: `5167`
- Target SDK: `35`

## Bluetooth / Protocol Clues

- The app is Flutter-based and includes `flutter_blue_plus`.
- The native/Dart strings include both legacy and newer WalkingPad UUID families:
  - `0000fe00-0000-1000-8000-00805f9b34fb`
  - `0000fed7-0000-1000-8000-00805f9b34fb`
  - `0000fed8-0000-1000-8000-00805f9b34fb`
  - `24e2521c-f63b-48ed-85be-c5330d00fdf7`
  - `24e2521c-f63b-48ed-85be-c5330e00fdf7`
  - `24e2521c-f63b-48ed-85be-c5330f00fdf70`
- A real C2 service dump also exposed the `24e2521c` family as service `...d00fdf7` with `...e00fdf7` update properties and `...f00fdf7` write properties, plus a `5833ff01` service family. Live Android properties showed `5833ff02` is write-only (`8`), so C3P0 treats `5833ff03` as the read/update characteristic and `5833ff02` as the write characteristic alongside the `FED7/FED8` pairs. KS Fit's decompiled `GattCommandPlugin` and BLE infrastructure plugin identify this family as Alibaba AIS transport: command-out writes are AIS command type `2`, responses arrive as command types such as `3` or `1`, and BLE sub-version `18` prepends a one-byte bus prefix to the command payload. C3P0 therefore starts the `5833ff02` AIS handshake with that bus prefix and only toggles to unprefixed AIS if the prefixed handshake does not answer. KS Fit's BLE stack also starts GATT characteristic writes through the legacy `setValue`/`writeCharacteristic(characteristic)` API, so C3P0 mirrors that path instead of the Android 13 write overload.
- Follow-up GitHub research found `mcdax/walkingpad-controller`, which decompiled KS Fit's Flutter/Dart layer and validated behavior with HCI snoops. The important correction: newer KingSmith pads with FTMS (`0x1826`) and the `24e2521c` supplement service should use the FTMS Control Point (`0x2ad9`) for basic start/pause/speed commands, with the KS property-list preamble `01 00 0d 00 06 0b 0f 0d` sent through the ODM/supplement write channel. The `5833ff01` family appears on many unrelated BLE modules and should not be preferred for treadmill control when the KS FTMS+supplement route is available.
- The same research confirms FTMS stop/pause use the same `StopOrPause` opcode (`0x08`) with different params: stop is `08 01`, pause is `08 02`.
- Dart strings include action names such as:
  - `WilinkDeviceActionExt|setStart`
  - `WilinkDeviceActionExt|setPause`
  - `WilinkDeviceActionExt|setStop`
  - `WilinkDeviceActionExt|setSpeed`
  - `WilinkDeviceActionExt|setMode`
  - `WilinkDeviceActionExt|setAutoStop`
  - `setNoloadStop`
- KS Fit 6.0.2's Flutter AOT strings expose the no-load stop setting keys:
  - `AuToStop` for the enable/disable switch
  - `NoloadStop` for the idle timeout value
  - `idleTimeout` as the UI/controller-side timeout field

## WalkingPad Properties

The Java-side Ali IoT models expose these official property names:

- `RunningSteps`
- `BurnCalories`
- `CurrentSpeed`
- `RunningDistance`
- `RunningTotalTime`
- `ControlMode`
- `runState`
- `StartSpeed`
- `ChildLockSwitch`
- `PanelDisplay`
- `VelocitySensitivity`

The Xiaomi MiOT plugin also maps older WalkingPad property/action names:

- Motion: `speed`, `dist`, `step`, `time`, `cal`, `lock`, `power`, `state`, `mode`, `button_id`
- Actions: `set_speed`, `set_state`, `set_mode`, `set_start_speed`, `set_sensitivity`

## Product Catalog

The bundled `assets/flutter_assets/assets/mine/allProducts.json` identifies C2-like devices as:

- Product: `C2`
- Names/models: `KS-BLC2`, `S1`, `c2`, `KS-BDC2`
- Chip: `2`
- Max speed: `6` km/h
- Sensitivity: `2`
- Device type: `1`
- Connect type: `0`

## C3P0 Impact

- Encrypted KS polling should request the motion/state properties above and parse `BurnCalories`, `RunningSteps`, `RunningTotalTime`, `RunningDistance`, `ControlMode`, and `runState`. On `5833ff01` AIS transport, encrypted KS text payloads must be AIS-framed before writing and AIS-unwrapped before decoding.
- KS Fit exposes no-load stop UI and the likely encrypted KS property keys. On encrypted KS text transports, C3P0 sends them over the same text-property path as speed/mode: `props AuToStop 0/1` and `props NoloadStop <seconds>`. On FTMS+supplement pads, C3P0 uses the best-known supplement property id for `autoStop` (`0x02`) and writes `0` for off or the selected timeout seconds for on. The exact FTMS supplement set-property wrapper is still tentative; if the pad rejects it, the app reports the attempted bytes so a packet sniff can confirm the final command shape.
- KS Fit behavior matches the pause-then-stop flow: pause while the belt is moving, then stop only after the belt has coasted to zero.
