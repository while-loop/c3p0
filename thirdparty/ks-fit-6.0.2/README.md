# KS Fit 6.0.2 Reference

This folder keeps local reverse-engineering references for the WalkingPad-compatible KS Fit Android app:

- APK: `C:\Users\anthony\Downloads\KS+Fit_6.0.2_APKPure.apk`
- Package: `com.kingsmith.xiaojin`
- Version: `6.0.2`

The extracted APK files and JADX decompiled output are intentionally gitignored because they are third-party proprietary app contents. Keep the local copies here for inspection, but do not commit or push those generated folders.

## Regenerate

From the repo root:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\thirdparty\ks-fit-6.0.2\decompile.ps1
```

Outputs:

- `thirdparty/ks-fit-6.0.2/extracted/`
- `thirdparty/ks-fit-6.0.2/decompiled/`
- `thirdparty/ks-fit-6.0.2/tools/`

JADX can report decompile errors on obfuscated Android apps while still producing useful output.
