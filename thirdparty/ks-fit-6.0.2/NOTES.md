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
- Dart strings include action names such as:
  - `WilinkDeviceActionExt|setStart`
  - `WilinkDeviceActionExt|setPause`
  - `WilinkDeviceActionExt|setStop`
  - `WilinkDeviceActionExt|setSpeed`
  - `WilinkDeviceActionExt|setMode`
  - `WilinkDeviceActionExt|setAutoStop`
  - `setNoloadStop`

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

- Encrypted KS polling should request the motion/state properties above and parse `BurnCalories`, `RunningSteps`, `RunningTotalTime`, `RunningDistance`, `ControlMode`, and `runState`.
- KS Fit exposes no-load stop UI and strings, but the exact BLE payload/key was not recovered from strings alone. Keep no-load stop hidden until packet captures identify the command.
- KS Fit behavior matches the pause-then-stop flow: pause while the belt is moving, then stop only after the belt has coasted to zero.
