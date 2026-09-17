# Usage: cdp.ps1 -Url <url> -Out <file.png> [-Width 400] [-Height 1700] [-Dark] [-Wait 3] [-Js "<expression>"]
param([string]$Url, [string]$Out, [int]$Width = 400, [int]$Height = 1700, [switch]$Dark, [double]$Wait = 3, [string]$Js = "", [switch]$Reuse, [string]$Init = "")
$ErrorActionPreference = 'Stop'
$targets = Invoke-RestMethod "http://127.0.0.1:9333/json"
$page = $targets | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.Options.SetBuffer(65536, 65536)
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait()
$script:id = 0
function Send($method, $params) {
  $script:id++
  $msg = @{ id = $script:id; method = $method; params = $params } | ConvertTo-Json -Compress -Depth 5
  $bytes = [Text.Encoding]::UTF8.GetBytes($msg)
  $ws.SendAsync([ArraySegment[byte]]$bytes, 'Text', $true, [Threading.CancellationToken]::None).Wait()
  $want = $script:id
  while ($true) {
    $buf = New-Object byte[] (65536); $sb = New-Object Text.StringBuilder
    do { $seg = [ArraySegment[byte]]$buf; $r = $ws.ReceiveAsync($seg, [Threading.CancellationToken]::None).Result; [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $r.Count)) } while (-not $r.EndOfMessage)
    $obj = $sb.ToString() | ConvertFrom-Json
    if ($obj.id -eq $want) { return $obj }
  }
}
[void](Send 'Page.enable' @{})
[void](Send 'Runtime.enable' @{})
[void](Send 'Emulation.setDeviceMetricsOverride' @{ width = $Width; height = $Height; deviceScaleFactor = 1; mobile = $false })
if ($Dark) { [void](Send 'Emulation.setEmulatedMedia' @{ features = @(@{ name = 'prefers-color-scheme'; value = 'dark' }) }) }
else { [void](Send 'Emulation.setEmulatedMedia' @{ features = @(@{ name = 'prefers-color-scheme'; value = 'light' }) }) }
if ($Init) { [void](Send 'Page.addScriptToEvaluateOnNewDocument' @{ source = $Init }) }
if (-not $Reuse) {
[void](Send 'Page.navigate' @{ url = 'about:blank' })
Start-Sleep -Milliseconds 300
[void](Send 'Page.navigate' @{ url = $Url })
}
Start-Sleep -Seconds $Wait
if ($Js) { $r = Send 'Runtime.evaluate' @{ expression = $Js; awaitPromise = $true; returnByValue = $true }; Write-Output ('JS: ' + ($r.result.result.value | ConvertTo-Json -Compress)); Start-Sleep -Seconds 1.5 }
$shot = Send 'Page.captureScreenshot' @{ format = 'png'; captureBeyondViewport = $true }
[IO.File]::WriteAllBytes($Out, [Convert]::FromBase64String($shot.result.data))
$ws.Dispose()
Write-Output "wrote $Out"
