<#
.SYNOPSIS
Build helper for the BafangCon Android app.

.DESCRIPTION
Runs common Gradle build actions from the project root. By default it opens
an interactive menu. You can also call it directly, for example:

  powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Mode debug
  powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Mode clean-log
  powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Mode install
  powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Mode release
  powershell -ExecutionPolicy Bypass -File .\build-app.ps1 -Mode check
#>

[CmdletBinding()]
param(
    [ValidateSet("menu", "debug", "clean", "log", "clean-log", "install", "release", "check")]
    [string]$Mode = "release"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$DebugApkDir = Join-Path $ProjectRoot "app\build\outputs\apk\debug"
$ReleaseApkDir = Join-Path $ProjectRoot "app\build\outputs\apk\release"

function Resolve-Apk {
    param([string]$Dir)
    if (-not (Test-Path $Dir)) { return $null }
    $apks = Get-ChildItem -LiteralPath $Dir -Filter "*.apk"
    if (-not $apks) { return $null }
    $universal = $apks | Where-Object { $_.Name -match "-universal-" } | Select-Object -First 1
    if ($universal) { return $universal.FullName }
    return $apks[0].FullName
}

$VersionPropsPath = Join-Path $ProjectRoot "version.properties"

function Write-Section {
    param([string]$Text)
    Write-Host ""
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function Convert-LocalPropertiesPath {
    param([string]$PathValue)

    $path = $PathValue.Trim()
    $path = $path -replace "\\:", ":"
    $path = $path -replace "\\\\", "\"
    return $path
}

function Resolve-AndroidSdk {
    $localProperties = Join-Path $ProjectRoot "local.properties"

    if (Test-Path $localProperties) {
        foreach ($line in Get-Content $localProperties) {
            if ($line -match "^\s*sdk\.dir\s*=\s*(.+)\s*$") {
                $sdkPath = Convert-LocalPropertiesPath $Matches[1]
                if (Test-Path $sdkPath) {
                    return $sdkPath
                }
            }
        }
    }

    foreach ($name in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value -and (Test-Path $value)) {
            return $value
        }
    }

    return $null
}

function Test-CommandAvailable {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-BuildEnvironment {
    Write-Section "Environment"

    if (-not (Test-Path $GradleWrapper)) {
        throw "Gradle wrapper not found: $GradleWrapper"
    }
    Write-Host "Project: $ProjectRoot"
    Write-Host "Gradle wrapper: OK"

    if (Test-CommandAvailable "java") {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $javaVersionOutput = & java -version 2>&1
            $javaExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }

        $javaVersion = $javaVersionOutput | Select-Object -First 1
        if ($javaExitCode -eq 0 -and $javaVersion) {
            Write-Host "Java: $javaVersion"
        } else {
            Write-Warning "Java command exists, but version detection failed."
        }
    } else {
        Write-Warning "Java was not found in PATH. Android Studio usually installs a JDK, but Gradle may fail if it cannot find it."
    }

    $sdkPath = Resolve-AndroidSdk
    if ($sdkPath) {
        Write-Host "Android SDK: $sdkPath"
    } else {
        Write-Warning "Android SDK not detected from local.properties, ANDROID_HOME, or ANDROID_SDK_ROOT."
    }
}

function Update-VersionProperties {
    if (-not (Test-Path $VersionPropsPath)) {
        throw "version.properties not found: $VersionPropsPath"
    }

    $props = @{}
    Get-Content $VersionPropsPath | ForEach-Object {
        if ($_ -match "^(versionCode|versionName)=(.+)$") {
            $props[$Matches[1]] = $Matches[2].Trim()
        }
    }

    $newCode = ([int]$props["versionCode"]) + 1
    $props["versionCode"] = $newCode
    $props["versionName"] = "0.$($newCode.ToString("D3"))"

    @("versionCode=$($props['versionCode'])", "versionName=$($props['versionName'])") | Set-Content -Path $VersionPropsPath -Encoding ASCII

    Write-Host "Version bumped to $($props['versionName']) (code $($props['versionCode']))" -ForegroundColor Yellow
    return $props["versionName"]
}

function New-BuildLogPath {
    $logsDir = Join-Path $ProjectRoot "build-logs"
    New-Item -ItemType Directory -Path $logsDir -Force | Out-Null

    $stamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
    return Join-Path $logsDir "build-$stamp.txt"
}

function Invoke-GradleTasks {
    param(
        [string[]]$Tasks,
        [bool]$SaveLog = $false
    )

    $versionName = Update-VersionProperties

    Push-Location $ProjectRoot
    try {
        $args = @()
        $args += $Tasks
        $args += "--stacktrace"

        Write-Section "Gradle"
        Write-Host ".\gradlew.bat $($args -join ' ')"

        if ($SaveLog) {
            $logPath = New-BuildLogPath
            Write-Host "Log file: $logPath"
            & $GradleWrapper @args 2>&1 | Tee-Object -FilePath $logPath
            $exitCode = $LASTEXITCODE
        } else {
            & $GradleWrapper @args
            $exitCode = $LASTEXITCODE
        }

        if ($exitCode -ne 0) {
            throw "Gradle failed with exit code $exitCode."
        }

        Write-Section "Result"
        # Re-resolve APK after build (if it was just created)
        if ($Mode -eq "release") {
            $builtApk = Resolve-Apk $ReleaseApkDir
            if (-not $builtApk) { $builtApk = Resolve-Apk $DebugApkDir }
        } else {
            $builtApk = Resolve-Apk $DebugApkDir
            if (-not $builtApk) { $builtApk = Resolve-Apk $ReleaseApkDir }
        }

        if ($builtApk) {
            Write-Host "APK ready: $builtApk" -ForegroundColor Green
        } else {
            Write-Warning "Build finished, but APK was not found."
        }

        if ($builtApk) {
            $outputName = "Bafang FT M820 v$versionName.apk"
            $outputPath = Join-Path $ProjectRoot $outputName
            Copy-Item -Path $builtApk -Destination $outputPath -Force
            Write-Host "Copied to: $outputPath" -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
    }
}

function Ensure-ReleaseKeystore {
    $keystorePropsPath = Join-Path $ProjectRoot "keystore.properties"
    $keystoreFilePath = Join-Path $ProjectRoot "bafangcon.keystore"

    if (Test-Path $keystorePropsPath) {
        Write-Host "Keystore properties found: $keystorePropsPath" -ForegroundColor Green
        return
    }

    Write-Section "Release Keystore"
    Write-Host "No keystore.properties found. Generating a new release keystore..."

    if (-not (Test-CommandAvailable "keytool")) {
        throw "keytool not found in PATH. Install JDK or build with debug mode instead."
    }

    $password = "bafangcon"

    & keytool -genkey -v -keystore $keystoreFilePath `
        -alias "bafangcon" `
        -keyalg "RSA" `
        -keysize "2048" `
        -validity "10000" `
        -storepass $password `
        -keypass $password `
        -dname "CN=BafangCon, OU=Dev, O=BafangCon, L=Unknown, ST=Unknown, C=UN" `
        2>&1

    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }

    @"
storeFile=bafangcon.keystore
storePassword=$password
keyAlias=bafangcon
keyPassword=$password
"@ | Set-Content -Path $keystorePropsPath -Encoding ASCII

    Write-Host "Keystore generated: $keystoreFilePath" -ForegroundColor Green
    Write-Host "Properties written: $keystorePropsPath" -ForegroundColor Green
}

function Install-DebugApk {
    if (-not (Test-CommandAvailable "adb")) {
        throw "adb was not found in PATH. Start Android Studio once or add platform-tools to PATH."
    }

    $apk = Resolve-Apk $DebugApkDir
    if (-not $apk) {
        Write-Warning "Debug APK does not exist yet. Building it first."
        Invoke-GradleTasks -Tasks @(":app:assembleDebug") -SaveLog $false
        $apk = Resolve-Apk $DebugApkDir
        if (-not $apk) { throw "Build finished but APK not found." }
    }

    Write-Section "ADB"
    & adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed."
    }

    Write-Host "Installing: $apk"
    & adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed."
    }
}

function Show-Menu {
    do {
        Write-Section "BafangCon build menu"
        Write-Host "1. Build debug APK"
        Write-Host "2. Clean + build debug APK"
        Write-Host "3. Build debug APK and save log"
        Write-Host "4. Clean + build debug APK and save log"
        Write-Host "5. Install debug APK on connected phone"
        Write-Host "6. Build release APK"
        Write-Host "7. Check build environment"
        Write-Host "0. Exit"
        $choice = Read-Host "Choose option"

        switch ($choice) {
            "1" { Invoke-GradleTasks -Tasks @(":app:assembleDebug") -SaveLog $false }
            "2" { Invoke-GradleTasks -Tasks @("clean", ":app:assembleDebug") -SaveLog $false }
            "3" { Invoke-GradleTasks -Tasks @(":app:assembleDebug") -SaveLog $true }
            "4" { Invoke-GradleTasks -Tasks @("clean", ":app:assembleDebug") -SaveLog $true }
            "5" { Install-DebugApk }
            "6" { Ensure-ReleaseKeystore; Invoke-GradleTasks -Tasks @(":app:assembleRelease") -SaveLog $false }
            "7" { Test-BuildEnvironment }
            "0" { return }
            default { Write-Warning "Unknown option: $choice" }
        }
    } while ($true)
}

try {
    Test-BuildEnvironment

    switch ($Mode) {
        "menu" { Show-Menu }
        "debug" { Invoke-GradleTasks -Tasks @(":app:assembleDebug") -SaveLog $false }
        "clean" { Invoke-GradleTasks -Tasks @("clean", ":app:assembleDebug") -SaveLog $false }
        "log" { Invoke-GradleTasks -Tasks @(":app:assembleDebug") -SaveLog $true }
        "clean-log" { Invoke-GradleTasks -Tasks @("clean", ":app:assembleDebug") -SaveLog $true }
        "install" { Install-DebugApk }
        "release" { Ensure-ReleaseKeystore; Invoke-GradleTasks -Tasks @(":app:assembleRelease") -SaveLog $false }
        "check" { Write-Host ""; Write-Host "Build environment check completed." -ForegroundColor Green }
    }
} catch {
    Write-Host ""
    Write-Host "Build helper failed:" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
