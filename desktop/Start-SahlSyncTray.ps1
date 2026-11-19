param([switch]$Minimized)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

$state = Join-Path $env:LOCALAPPDATA 'SahlSync\Tray'
New-Item -ItemType Directory -Path $state -Force | Out-Null
$settingsPath = Join-Path $state 'shares.json'
$pairingPath = Join-Path $state 'pairing.json'
$logPath = Join-Path $state 'companion-error.log'
$companion = Join-Path $PSScriptRoot 'sahl_sync_lan.py'
$trayScript = $PSCommandPath
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Sahl Sync.lnk'
$script:worker = $null
$script:exiting = $false

function Quote-Argument([string]$value) {
    return '"' + [regex]::Replace($value, '(\\+)$', '$1$1') + '"'
}

$form = [Windows.Forms.Form]::new()
$form.Text = 'Sahl Sync for Windows'
$form.ClientSize = [Drawing.Size]::new(590, 398)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.BackColor = [Drawing.Color]::FromArgb(25, 42, 39)
$form.ForeColor = [Drawing.Color]::White
function Add-Label($text, $x, $y, $width) {
    $control = [Windows.Forms.Label]::new()
    $control.Text = $text
    $control.SetBounds($x, $y, $width, 24)
    $form.Controls.Add($control)
    return $control
}
function Add-Button($text, $x, $y, $width) {
    $control = [Windows.Forms.Button]::new()
    $control.Text = $text
    $control.SetBounds($x, $y, $width, 30)
    $control.BackColor = [Drawing.Color]::FromArgb(234, 190, 91)
    $control.ForeColor = $form.BackColor
    $control.FlatStyle = 'Flat'
    $form.Controls.Add($control)
    return $control
}
function Show-Message([string]$message) {
    [Windows.Forms.MessageBox]::Show($form, $message, 'Sahl Sync') | Out-Null
}

$null = Add-Label 'PC Wi-Fi IPv4 address' 16 18 200
$address = [Windows.Forms.ComboBox]::new()
$address.SetBounds(16, 43, 225, 26)
$form.Controls.Add($address)
try {
    [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and
            $_.IPAddressToString -match '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } |
        ForEach-Object { $address.Items.Add($_.IPAddressToString) | Out-Null }
} catch {}
if ($address.Items.Count -eq 1) { $address.SelectedIndex = 0 }

$null = Add-Label 'Port' 258 18 100
$port = [Windows.Forms.NumericUpDown]::new()
$port.Minimum = 1
$port.Maximum = 65535
$port.Value = 8765
$port.SetBounds(258, 43, 105, 26)
$form.Controls.Add($port)
$auto = [Windows.Forms.CheckBox]::new()
$auto.Text = 'Start with Windows'
$auto.SetBounds(383, 43, 175, 26)
$form.Controls.Add($auto)
$auto.Checked = Test-Path -LiteralPath $startup
$null = Add-Label 'Shared folders' 16 91 230
$shares = [Windows.Forms.ListBox]::new()
$shares.SetBounds(16, 116, 560, 154)
$form.Controls.Add($shares)

if (Test-Path -LiteralPath $settingsPath) {
    try {
        $saved = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
        $address.Text = $saved.address
        $port.Value = $saved.port
        foreach ($folder in $saved.folders) { $shares.Items.Add([string]$folder) | Out-Null }
    } catch {}
}
$add = Add-Button 'Add folder' 16 281 105
$remove = Add-Button 'Remove folder' 129 281 114
$copy = Add-Button 'Copy pairing code' 251 281 145
$qr = Add-Button 'Show QR' 404 281 94
$launch = Add-Button 'Start sharing' 16 326 140
$status = Add-Label 'Sharing is off.' 173 331 401
$hint = Add-Label 'Select a shared folder to pair it with the phone.' 16 370 560

$icon = [Windows.Forms.NotifyIcon]::new()
$icon.Text = 'Sahl Sync'
$icon.Icon = [Drawing.SystemIcons]::Application
$icon.ContextMenuStrip = [Windows.Forms.ContextMenuStrip]::new()
$openItem = $icon.ContextMenuStrip.Items.Add('Open Sahl Sync')
$exitItem = $icon.ContextMenuStrip.Items.Add('Exit')
$icon.Visible = $true
$context = [Windows.Forms.ApplicationContext]::new()

function Save-Settings {
    @{ address = $address.Text.Trim(); port = [int]$port.Value; folders = @($shares.Items | ForEach-Object { [string]$_ }) } |
        ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding UTF8
}
function Set-SharingUi([bool]$sharing) {
    $add.Enabled = !$sharing
    $remove.Enabled = !$sharing
    $address.Enabled = !$sharing
    $port.Enabled = !$sharing
    $copy.Enabled = $sharing
    $qr.Enabled = $sharing
    if ($sharing) { $launch.Text = 'Stop sharing' }
    else { $launch.Text = 'Start sharing' }
}
function Stop-Sharing {
    if ($script:worker -and !$script:worker.HasExited) { Stop-Process -Id $script:worker.Id }
    $script:worker = $null
    Set-SharingUi $false
    $status.Text = 'Sharing is off.'
}
function Start-Sharing {
    if ($shares.Items.Count -eq 0 -or $address.Text.Trim() -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
        if (!$Minimized) { Show-Message 'Add a folder and enter the PC Wi-Fi IPv4 address.' }
        return
    }
    try {
        Save-Settings
        $python = (Get-Command python -ErrorAction Stop).Source
        if (Test-Path -LiteralPath $pairingPath) { Remove-Item -LiteralPath $pairingPath }
        $argsList = @((Quote-Argument $companion), (Quote-Argument ([string]$shares.Items[0])),
            '--listen', $address.Text.Trim(), '--port', $port.Value.ToString(),
            '--pairing-file', (Quote-Argument $pairingPath))
        for ($i = 1; $i -lt $shares.Items.Count; $i++) {
            $argsList += @('--share', (Quote-Argument ([string]$shares.Items[$i])))
        }
        $script:worker = Start-Process -FilePath $python -ArgumentList $argsList -WindowStyle Hidden -RedirectStandardError $logPath -PassThru -ErrorAction Stop
        Set-SharingUi $true
        $status.Text = 'Starting Wi-Fi sharing...'
    } catch { if (!$Minimized) { Show-Message 'Could not start sharing. Check Python and OpenSSL.' } }
}
function Read-Pairing([string]$folder) {
    try {
        $entries = Get-Content -LiteralPath $pairingPath -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json
        return [string]$entries.PSObject.Properties[$folder].Value
    } catch { return '' }
}

$add.Add_Click({
    $dialog = [Windows.Forms.FolderBrowserDialog]::new()
    try {
        if ($dialog.ShowDialog($form) -eq 'OK' -and !$shares.Items.Contains($dialog.SelectedPath)) {
            $shares.Items.Add($dialog.SelectedPath) | Out-Null
            Save-Settings
        }
    } finally { $dialog.Dispose() }
})
$remove.Add_Click({
    if ($shares.SelectedIndex -ge 0) { $shares.Items.RemoveAt($shares.SelectedIndex); Save-Settings }
})
$launch.Add_Click({ if ($script:worker) { Stop-Sharing } else { Start-Sharing } })
$copy.Add_Click({
    $code = Read-Pairing ([string]$shares.SelectedItem)
    if ($code) { [Windows.Forms.Clipboard]::SetText($code); $hint.Text = 'Pairing code copied.' }
    else { Show-Message 'Select a folder after sharing starts.' }
})
$qr.Add_Click({
    $folder = [string]$shares.SelectedItem
    if (!(Read-Pairing $folder)) { Show-Message 'Select a folder after sharing starts.'; return }
    $image = Join-Path $state ('pair-' + [guid]::NewGuid().ToString('N') + '.png')
    try {
        python -c 'import json,sys,qrcode; qrcode.make(json.load(open(sys.argv[1],encoding="utf-8"))[sys.argv[2]]).save(sys.argv[3])' $pairingPath $folder $image 2>$null
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $image)) {
            Show-Message 'Install qrcode[pil] with Python to show QR codes. You can copy the pairing code now.'
            return
        }
        $window = [Windows.Forms.Form]::new()
        $window.Text = 'Private pairing QR'
        $window.ClientSize = [Drawing.Size]::new(390, 390)
        $window.StartPosition = 'CenterParent'
        $picture = [Windows.Forms.PictureBox]::new()
        $picture.Dock = 'Fill'
        $picture.SizeMode = 'Zoom'
        $picture.Image = [Drawing.Image]::FromFile($image)
        $window.Controls.Add($picture)
        try { $window.ShowDialog($form) | Out-Null }
        finally { $picture.Image.Dispose(); $window.Dispose() }
    } finally { if (Test-Path -LiteralPath $image) { Remove-Item -LiteralPath $image } }
})
$auto.Add_CheckedChanged({
    if (!$auto.Checked) {
        if (Test-Path -LiteralPath $startup) { Remove-Item -LiteralPath $startup }
        return
    }
    Save-Settings
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($startup)
    $link.TargetPath = (Get-Process -Id $PID).Path
    $link.Arguments = '-NoProfile -STA -File ' + (Quote-Argument $trayScript) + ' -Minimized'
    $link.WorkingDirectory = Split-Path -Parent $trayScript
    $link.Save()
})
$timer = [Windows.Forms.Timer]::new()
$timer.Interval = 750
$timer.Add_Tick({
    if (!$script:worker) { return }
    if ($script:worker.HasExited) {
        Stop-Sharing
        $status.Text = 'Could not start. Check the address and port.'
    } elseif (Test-Path -LiteralPath $pairingPath) { $status.Text = 'Sharing ' + $shares.Items.Count + ' folder(s).' }
})
$timer.Start()
$openItem.Add_Click({ $form.Show(); $form.Activate() })
$icon.Add_DoubleClick({ $form.Show(); $form.Activate() })
$form.Add_FormClosing({
    param($sender, $eventArgs)
    if (!$script:exiting) { $eventArgs.Cancel = $true; $form.Hide() }
})
$exitItem.Add_Click({
    $script:exiting = $true
    Stop-Sharing
    $form.Close()
    $context.ExitThread()
})
try {
    Set-SharingUi $false
    if ($Minimized) { Start-Sharing } else { $form.Show() }
    [Windows.Forms.Application]::Run($context)
} finally {
    $timer.Dispose()
    Stop-Sharing
    $icon.Dispose()
    $form.Dispose()
    $context.Dispose()
}
