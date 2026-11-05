$ErrorActionPreference = 'Continue'
$base = 'http://192.168.2.16:9817'
$outDir = 'd:\xkx\xkx_appproj\happy_phone agent\docs\hpa-trainer\snapshots'
$log = Join-Path $outDir 'gen17-watch-log.txt'
"watch start $(Get-Date -Format o)" | Out-File -FilePath $log -Encoding utf8

$startGen = 17
$deadline = (Get-Date).AddMinutes(90)

while ((Get-Date) -lt $deadline) {
    try {
        $ev = Invoke-RestMethod "$base/api/events" -TimeoutSec 20
        $h  = Invoke-RestMethod "$base/api/health" -TimeoutSec 20
        $st = $h.state
        $stamp = Get-Date -Format 'HH:mm:ss'
        $lastKind = if ($ev.events.Count -gt 0) { $ev.events[-1].kind } else { 'none' }
        "$stamp gen=$($st.generation) phase=$($st.current_phase) task=$($st.current_task_id) step=$($st.current_step) eps=$($st.episodes_done) pv=$($st.policy_version) last=$lastKind" |
            Out-File -FilePath $log -Encoding utf8 -Append

        # durable snapshot of the ring buffer each poll
        ($ev | ConvertTo-Json -Depth 8 -Compress) |
            Out-File -FilePath (Join-Path $outDir 'gen17-latest-events.json') -Encoding utf8

        $genEnd = $ev.events | Where-Object { $_.kind -eq 'generation_end' -and $_.generation -eq $startGen }
        if ($genEnd) {
            "$stamp generation_end detected for gen $startGen" | Out-File -FilePath $log -Encoding utf8 -Append
            ($ev | ConvertTo-Json -Depth 8) |
                Out-File -FilePath (Join-Path $outDir "$(Get-Date -Format 'yyyyMMddTHHmm')-gen17-end-events.json") -Encoding utf8
            (Invoke-RestMethod "$base/api/summary" | ConvertTo-Json -Depth 8) |
                Out-File -FilePath (Join-Path $outDir "$(Get-Date -Format 'yyyyMMddTHHmm')-gen17-end-summary.json") -Encoding utf8
            break
        }
        if ($st.generation -ne $startGen) {
            "$stamp generation moved to $($st.generation) (gen17 done)" | Out-File -FilePath $log -Encoding utf8 -Append
            (Invoke-RestMethod "$base/api/summary" | ConvertTo-Json -Depth 8) |
                Out-File -FilePath (Join-Path $outDir "$(Get-Date -Format 'yyyyMMddTHHmm')-gen17-end-summary.json") -Encoding utf8
            break
        }
    } catch {
        "$(Get-Date -Format 'HH:mm:ss') poll error: $($_.Exception.Message)" | Out-File -FilePath $log -Encoding utf8 -Append
    }
    Start-Sleep -Seconds 20
}
"watch stop $(Get-Date -Format o)" | Out-File -FilePath $log -Encoding utf8 -Append
