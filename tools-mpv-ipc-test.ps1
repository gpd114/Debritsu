# Proves the phase 2 mechanism: drive mpv over its Windows named pipe, read
# playback position back, and confirm property changes can be pushed rather
# than polled. No media file needed - lavfi generates a real timeline.

$mpv  = "C:\Program Files\MPV Player\mpv.exe"
$pipe = "debritsu-ipc-test"

$args = @(
  "--input-ipc-server=\\.\pipe\$pipe",
  "--vo=null", "--ao=null", "--no-terminal",
  "av://lavfi:testsrc=duration=60:size=320x240:rate=30"
)

Write-Output "launching mpv..."
$proc = Start-Process -FilePath $mpv -ArgumentList $args -PassThru -WindowStyle Hidden

try {
    # The pipe does not exist the instant the process starts.
    $client = $null
    foreach ($attempt in 1..40) {
        try {
            $c = New-Object System.IO.Pipes.NamedPipeClientStream(".", $pipe, [System.IO.Pipes.PipeDirection]::InOut)
            $c.Connect(250)
            $client = $c
            Write-Output "connected on attempt $attempt"
            break
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $client) { Write-Output "FAIL: never connected to \\.\pipe\$pipe"; exit 1 }

    $reader = New-Object System.IO.StreamReader($client)
    $writer = New-Object System.IO.StreamWriter($client)
    $writer.AutoFlush = $true

    function Send-Cmd([string]$json) { $writer.WriteLine($json) }

    # Read lines until one carries the request_id we asked for. Events are
    # interleaved with replies on the same pipe, which is the detail that
    # decides how the client loop has to be written.
    function Get-Reply([int]$id, [int]$timeoutMs = 4000) {
        $deadline = (Get-Date).AddMilliseconds($timeoutMs)
        while ((Get-Date) -lt $deadline) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { continue }
            $obj = $line | ConvertFrom-Json
            if ($obj.PSObject.Properties.Name -contains "request_id" -and $obj.request_id -eq $id) { return $obj }
            if ($obj.PSObject.Properties.Name -contains "event") { Write-Output "  (event: $($obj.event))" }
        }
        return $null
    }

    Send-Cmd '{"command":["get_property","mpv-version"],"request_id":1}'
    $v = Get-Reply 1
    Write-Output "mpv-version : $($v.data)  [error=$($v.error)]"

    Start-Sleep -Seconds 2

    Send-Cmd '{"command":["get_property","duration"],"request_id":2}'
    $d = Get-Reply 2
    Write-Output "duration    : $($d.data)  [error=$($d.error)]"

    Send-Cmd '{"command":["get_property","time-pos"],"request_id":3}'
    $t = Get-Reply 3
    Write-Output "time-pos    : $($t.data)  [error=$($t.error)]"

    # Does it advance? This is the whole basis of pushing progress at 85%.
    Start-Sleep -Seconds 3
    Send-Cmd '{"command":["get_property","time-pos"],"request_id":4}'
    $t2 = Get-Reply 4
    Write-Output "time-pos +3s: $($t2.data)"

    # Push instead of poll.
    Send-Cmd '{"command":["observe_property",7,"time-pos"],"request_id":5}'
    $o = Get-Reply 5
    Write-Output "observe     : [error=$($o.error)]"

    $pushes = 0
    $deadline = (Get-Date).AddSeconds(3)
    while ((Get-Date) -lt $deadline -and $pushes -lt 4) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { continue }
        $obj = $line | ConvertFrom-Json
        if ($obj.event -eq "property-change" -and $obj.id -eq 7) {
            $pushes++
            Write-Output "  push $pushes : time-pos=$([math]::Round($obj.data,2))"
        }
    }
    Write-Output "observed $pushes pushes without polling"

    # Seeking, which the skip-intro equivalent would need.
    Send-Cmd '{"command":["seek",30,"absolute"],"request_id":6}'
    $s = Get-Reply 6
    Start-Sleep -Milliseconds 600
    Send-Cmd '{"command":["get_property","time-pos"],"request_id":7}'
    $t3 = Get-Reply 7
    Write-Output "after seek  : $($t3.data)  [seek error=$($s.error)]"

    $client.Dispose()
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Write-Output "mpv stopped"
}
