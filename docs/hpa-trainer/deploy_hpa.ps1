param(
    [string]$Tag = ''
)

$ErrorActionPreference = 'Stop'
if (-not $Tag) { $Tag = 'pre-' + (Get-Date -Format 'yyyyMMdd-HHmm') }

$srcDir = 'D:\mobilegym_test\hpa_training'
$remoteDir = '/volume1/docker/hpa-trainer/build/hpa_training'
$files = @('orchestrator.py', 'agent.py', 'policy.py', 'config.py', 'runner.py')

foreach ($f in $files) {
    scp -O (Join-Path $srcDir $f) "nas:$remoteDir/$f"
    if ($LASTEXITCODE -ne 0) { throw "scp failed: $f" }
}

foreach ($f in $files) {
    $local = (Get-FileHash (Join-Path $srcDir $f) -Algorithm MD5).Hash.ToLower()
    $remote = (ssh nas "md5sum $remoteDir/$f | cut -d' ' -f1").Trim()
    if ($local -ne $remote) { throw "hash mismatch: $f local=$local remote=$remote" }
    "ok $f $local"
}

# bench_env 侧文件（判分守卫 + 环境崩溃感知重建）：同一条链路进镜像，
# 上传前先留远端 md5 备查
$benchFiles = @('bench_env/runner/base.py', 'bench_env/env/mobile_gym.py')
$benchRoot = 'D:\mobilegym_test'
$benchRemoteRoot = '/volume1/docker/hpa-trainer/build'
foreach ($rel in $benchFiles) {
    $benchLocal = Join-Path $benchRoot $rel
    $benchRemote = "$benchRemoteRoot/$rel"
    scp -O $benchLocal "nas:$benchRemote"
    if ($LASTEXITCODE -ne 0) { throw "scp failed: $rel" }
    $localHash = (Get-FileHash $benchLocal -Algorithm MD5).Hash.ToLower()
    $remoteHash = (ssh nas "md5sum $benchRemote | cut -d' ' -f1").Trim()
    if ($localHash -ne $remoteHash) { throw "hash mismatch: $rel local=$localHash remote=$remoteHash" }
    "ok $rel $localHash"
}

ssh nas "cd /volume1/docker/hpa-trainer/build && docker tag hpa-trainer:latest hpa-trainer:$Tag && docker build -t hpa-trainer:latest . && docker compose up -d --force-recreate"
if ($LASTEXITCODE -ne 0) { throw 'remote build/deploy failed' }

Start-Sleep -Seconds 8
ssh nas "docker ps --filter name=hpa-trainer --format '{{.Status}}'"
