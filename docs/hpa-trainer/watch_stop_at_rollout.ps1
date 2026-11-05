$ErrorActionPreference = 'SilentlyContinue'
$base = 'http://192.168.2.16:9817'
$log = 'd:\xkx\xkx_appproj\happy_phone agent\docs\hpa-trainer\snapshots\stop-at-rollout-log.txt'
"start $(Get-Date -Format o)" | Out-File $log -Encoding utf8
$deadline = (Get-Date).AddMinutes(150)

while ((Get-Date) -lt $deadline) {
    $h = Invoke-RestMethod "$base/api/health" -TimeoutSec 15
    $st = $h.state
    $line = "$(Get-Date -Format 'HH:mm:ss') gen=$($st.generation) phase=$($st.current_phase) task=$($st.current_task_id) step=$($st.current_step) eps=$($st.episodes_done) running=$($st.running)"
    $line | Out-File $log -Encoding utf8 -Append
    if ($st.current_phase -eq 'rollout' -and [int]$st.current_step -le 1 -and [bool]$st.running) {
        $r = Invoke-RestMethod "$base/api/control" -Method Post -ContentType 'application/json' -Body '{"action":"stop"}' -TimeoutSec 30
        "STOP sent, running=$($r.state.running)" | Out-File $log -Encoding utf8 -Append
        break
    }
    Start-Sleep -Seconds 20
}
"end $(Get-Date -Format o)" | Out-File $log -Encoding utf8 -Append
