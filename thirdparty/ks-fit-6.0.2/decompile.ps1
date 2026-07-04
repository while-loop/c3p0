param(
    [string]$ApkPath = "$env:USERPROFILE\Downloads\KS+Fit_6.0.2_APKPure.apk"
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$extractDir = Join-Path $root 'extracted'
$decompiledDir = Join-Path $root 'decompiled'
$toolsDir = Join-Path $root 'tools'
$jadxVersion = '1.5.5'
$jadxZip = Join-Path $toolsDir "jadx-$jadxVersion.zip"
$jadxDir = Join-Path $toolsDir "jadx-$jadxVersion"
$jadxBat = Join-Path $jadxDir 'bin\jadx.bat'

if (!(Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath"
}

New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

if (!(Test-Path $jadxBat)) {
    $url = "https://github.com/skylot/jadx/releases/download/v$jadxVersion/jadx-$jadxVersion.zip"
    Write-Host "Downloading JADX $jadxVersion..."
    Invoke-WebRequest -Uri $url -OutFile $jadxZip

    $tmpJadx = Join-Path $toolsDir 'jadx-unpack'
    if (Test-Path $tmpJadx) {
        Remove-Item -Recurse -Force $tmpJadx
    }
    New-Item -ItemType Directory -Force -Path $tmpJadx | Out-Null
    Expand-Archive -Force -Path $jadxZip -DestinationPath $tmpJadx

    if (Test-Path $jadxDir) {
        Remove-Item -Recurse -Force $jadxDir
    }
    $unpackedRoot = Get-ChildItem -Path $tmpJadx -Directory | Select-Object -First 1
    if ($unpackedRoot -and (Test-Path (Join-Path $unpackedRoot.FullName 'bin\jadx.bat'))) {
        Move-Item -Path $unpackedRoot.FullName -Destination $jadxDir
    } else {
        Move-Item -Path $tmpJadx -Destination $jadxDir
    }
    if (Test-Path $tmpJadx) {
        Remove-Item -Recurse -Force $tmpJadx
    }
}

if (Test-Path $extractDir) {
    Remove-Item -Recurse -Force $extractDir
}
if (Test-Path $decompiledDir) {
    Remove-Item -Recurse -Force $decompiledDir
}

New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
New-Item -ItemType Directory -Force -Path $decompiledDir | Out-Null

Write-Host "Extracting APK..."
tar.exe -xf $ApkPath -C $extractDir

Write-Host "Decompiling APK with JADX..."
$env:JAVA_HOME = 'C:\Users\anthony\AppData\Local\Programs\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& $jadxBat -d $decompiledDir $ApkPath

Write-Host "Done."
Write-Host "Extracted:  $extractDir"
Write-Host "Decompiled: $decompiledDir"
