param(
 [Parameter(Mandatory=$true)][string]$UnsignedApk,
 [Parameter(Mandatory=$true)][string]$OutputApk,
 [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
 [string]$KeyStore = "$env:USERPROFILE\.android\debug.keystore",
 [string]$ExpectedSignature = 'D7dAFIHlMCL0tzHTRGVYfaUmmic='
)
$ErrorActionPreference = 'Stop'
$inputPath = (Resolve-Path -LiteralPath $UnsignedApk).Path
$outputPath = [IO.Path]::GetFullPath($OutputApk)
if ($inputPath -eq $outputPath) { throw 'Input and output APK paths must differ' }
if (Test-Path -LiteralPath $outputPath) { throw 'Output already exists; choose a new filename' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$tools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $tools) { throw 'Android build-tools are missing' }
$signer = Join-Path $tools.FullName 'lib\apksigner.jar'
$certificate = & $keytool -exportcert -alias androiddebugkey -keystore $KeyStore -storepass android -rfc 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Could not read development certificate' }
$base64 = ($certificate | Where-Object { $_ -notmatch '^-----' }) -join ''
$sha1 = [Security.Cryptography.SHA1]::Create()
try { $signature = [Convert]::ToBase64String($sha1.ComputeHash([Convert]::FromBase64String($base64))) } finally { $sha1.Dispose() }
if ($signature -cne $ExpectedSignature) { throw 'This certificate does not match the registered Microsoft redirect URI' }
& $java -jar $signer sign --ks $KeyStore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $outputPath $inputPath
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
$verification = & $java -jar $signer verify --verbose --print-certs $outputPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$expectedHex = [BitConverter]::ToString([Convert]::FromBase64String($ExpectedSignature)).Replace('-','').ToLowerInvariant()
if (-not (($verification -join "`n") -match ('certificate SHA-1 digest: ' + [regex]::Escape($expectedHex)))) { throw 'Final APK certificate mismatch' }
$verification | Write-Output
Get-FileHash -LiteralPath $outputPath -Algorithm SHA256
Write-Output 'Local development APK verified. This is not a production signing key.'

