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
- A real C2 service dump also exposed the `24e2521c` family as service `...d00fdf7` with `...e00fdf7` update properties and `...f00fdf7` write properties, plus a `5833ff01` service family with `5833ff02`/`5833ff03` read/write-style characteristics. C3P0 treats those as encrypted KS characteristic candidates alongside the `FED7/FED8` pairs.
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

- Encrypted KS polling should request the motion/state properties above and parse `BurnCalories`, `RunningSteps`, `RunningTotalTime`, `RunningDistance`, `ControlMode`, and `runState`.
- KS Fit exposes no-load stop UI and the likely encrypted KS property keys. C3P0 sends them over the same text-property path as speed/mode: `props AuToStop 0/1` and `props NoloadStop <seconds>`. The numeric `servers getProp` IDs for proactive polling were not recovered, so C3P0 reads these values only if the pad echoes named props in encrypted notifications.
- KS Fit behavior matches the pause-then-stop flow: pause while the belt is moving, then stop only after the belt has coasted to zero.
