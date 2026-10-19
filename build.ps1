# Build the APK without Gradle. Requires JDK 17 and the local Android SDK in .android-sdk.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$sdk = Join-Path $root "..\.android-sdk"
$buildTools = Join-Path $sdk "buildtools\android-15"
$androidJar = Join-Path $sdk "platform\android-35\android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"

if (-not (Test-Path $androidJar)) { throw "Android SDK not found at $sdk" }

$jdkBin = Split-Path (Get-Command javac).Source -Parent
$env:JAVA_HOME = Split-Path $jdkBin -Parent

$build = Join-Path $root "build"
$resZip = Join-Path $build "res.zip"
$baseApk = Join-Path $build "base.apk"
$gen = Join-Path $build "gen"
$classes = Join-Path $build "classes"
$dexDir = Join-Path $build "dex"
$unsigned = Join-Path $build "unsigned.apk"
$aligned = Join-Path $build "aligned.apk"
$final = Join-Path $build "openbuds-battery-2.0.1.apk"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force $build, $gen, $classes, $dexDir | Out-Null

Write-Host "[1/7] aapt2 compile"
& $aapt2 compile --dir (Join-Path $root "res") -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link"
& $aapt2 link -o $baseApk -I $androidJar `
    --manifest (Join-Path $root "AndroidManifest.xml") `
    --java $gen --min-sdk-version 26 --target-sdk-version 34 `
    --version-code 39 --version-name "2.0.1" $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$javaFiles = @(Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem -Path $gen -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar `
    -d $classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8"
$classesJar = Join-Path $build "classes.jar"
& jar cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& $d8 --release --min-api 26 --lib $androidJar --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] package"
Copy-Item $baseApk $unsigned -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir "classes.dex"), "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

& $zipalign -p -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystoreDir = Join-Path $root "keystore"
$keystore = Join-Path $keystoreDir "openbuds-dev.jks"
$devPass = $env:OPENBUDS_KEYSTORE_PASSWORD
if ([string]::IsNullOrEmpty($devPass)) { $devPass = "openbuds-dev-only" }
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force $keystoreDir | Out-Null
    Write-Host "[6/7] create local development key"
    & keytool -genkeypair -v -keystore $keystore -alias openbuds -keyalg RSA `
        -keysize 2048 -validity 10000 -storepass $devPass -keypass $devPass `
        -dname "CN=OpenBuds Battery Dock, OU=Personal, O=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
} else {
    Write-Host "[6/7] reuse local development key"
}

Write-Host "[7/7] sign"
& $apksigner sign --ks $keystore --ks-key-alias openbuds `
    --ks-pass "pass:$devPass" --key-pass "pass:$devPass" `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --verbose --print-certs $final
if ($LASTEXITCODE -ne 0) { throw "signature verification failed" }

Write-Host ""
Write-Host "APK: $final"
Write-Host "Size: $([math]::Round((Get-Item $final).Length / 1MB, 2)) MB"


