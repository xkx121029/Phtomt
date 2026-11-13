param(
    [string]$Tag = '',
    [string]$SourceRoot = 'D:\mobilegym_test',
    [string]$BuildDir = '/volume1/docker/hpa-trainer/build',
    [string[]]$SyncTrees = @('hpa_training', 'bench_env'),
    [string[]]$StaticTrees = @('dist', 'apps'),
    [string[]]$BuildFiles = @('Dockerfile', 'docker-compose.yml', '.dockerignore'),
    [switch]$WithStatic,
    [switch]$NoBuild,
    [switch]$VerifyOnly,
    [switch]$VerifyContainer
)

# =============================================================================
# 部署 = 整目录同步 + 逐文件 md5 三方对账（本地 / 构建目录 / 容器内）
#
# 旧版维护两份手写文件名清单：新增文件不在清单里 → scp 跳过 → 脚本仍打印 ok，
# 于是「改了不起作用但部署报成功」。这里不再有逐文件清单：
#   1. 同步范围按**目录树**定义，整个目录打包上传并替换远端目录；
#   2. 对范围内每个文件逐个比 md5，任何不一致 / 本地有远端没有 / 远端有本地没有
#      → 列出文件并失败退出，绝不打印 ok；
#   3. 文件数对账：本地磁盘数 == 构建目录数 == 容器内数；
#   4. Dockerfile 的每个 COPY 源必须落在同步范围内（自检，防止新增目录被漏掉）；
#   5. 构建后进容器再比一次 md5 —— 证明「同步了且真的进了镜像」。
#
# 验证方式（不碰生产容器）：
#   -VerifyOnly -BuildDir /volume1/docker/hpa-trainer/_deploy_selftest
#   然后在那个目录里删一个文件/改一个字节，重跑必须 FAIL 并列出文件。
# =============================================================================

$ErrorActionPreference = 'Stop'
# 管道给 ssh 的文本必须是「无 BOM 的 UTF-8」，否则远端第一行会变成 ﻿set: command not found
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $Tag) { $Tag = 'pre-' + (Get-Date -Format 'yyyyMMdd-HHmm') }

$Targets = @($SyncTrees + $BuildFiles)
if ($WithStatic) { $Targets += $StaticTrees }

function Test-Excluded([string]$rel) {
    # 规则三处必须完全一致：本地清单 / 远端清单 / 容器清单
    if ($rel -match '(^|/)(__pycache__|\.pytest_cache)/') { return $true }
    if ($rel -like '*.pyc') { return $true }
    if ($rel -like '*.map') { return $true }
    return $false
}

function Get-LocalManifest {
    $h = @{}
    foreach ($item in $Targets) {
        $p = Join-Path $SourceRoot $item
        if (-not (Test-Path $p)) { throw "本地缺少构建输入：$item（Dockerfile COPY 需要它）" }
        if (Test-Path $p -PathType Leaf) {
            $h[$item] = (Get-FileHash $p -Algorithm MD5).Hash.ToLower()
            continue
        }
        Get-ChildItem $p -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($SourceRoot.Length + 1).Replace('\', '/')
            if (-not (Test-Excluded $rel)) {
                $h[$rel] = (Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower()
            }
        }
    }
    return $h
}

function ConvertFrom-Md5Lines($lines, [string]$label) {
    $h = @{}
    foreach ($line in $lines) {
        $s = [string]$line
        if (-not $s.Trim()) { continue }
        if ($s -match '^([0-9a-fA-F]{32})\s+\*?(.+)$') {
            $h[$Matches[2].Trim()] = $Matches[1].ToLower()
        }
        else {
            throw "$label 的 md5 清单里有无法解析的行：$s"
        }
    }
    return $h
}

function Invoke-RemoteScript([string]$script) {
    # 不用管道喂 stdin（Windows PowerShell 会带 BOM，远端第一行变成 ﻿cd: not found）。
    # 改为：本地写 ASCII 脚本 → scp → bash 执行，引号/编码都不再是变量。
    $sid = [guid]::NewGuid().ToString('N')
    $tmp = Join-Path $env:TEMP "hpa_remote_$sid.sh"
    $remote = "/tmp/hpa_remote_$sid.sh"
    [IO.File]::WriteAllText($tmp, ($script -replace "`r`n", "`n"), (New-Object Text.ASCIIEncoding))
    scp -O $tmp "nas:$remote" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
        throw "远端脚本上传失败：$remote"
    }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = ssh nas "bash $remote" 2>&1
        $code = $LASTEXITCODE
        ssh nas "rm -f $remote" 2>&1 | Out-Null
    }
    finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) {
        throw "远端命令失败（exit $code）：`n$script`n---`n$($out -join "`n")"
    }
    return @($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
}

function Compare-Manifest($local, $remote, [string]$label) {
    $missing = @()
    $extra = @()
    $diff = @()
    foreach ($k in $local.Keys) {
        if (-not $remote.ContainsKey($k)) { $missing += $k }
        elseif ($remote[$k] -ne $local[$k]) {
            $diff += ("{0}  local={1} remote={2}" -f $k, $local[$k], $remote[$k])
        }
    }
    foreach ($k in $remote.Keys) {
        if (-not $local.ContainsKey($k)) { $extra += $k }
    }
    $bad = $missing.Count + $extra.Count + $diff.Count
    if ($local.Count -ne $remote.Count -or $bad -gt 0) {
        Write-Host "[FAIL] $label 与本地不一致：本地 $($local.Count) 个 / 远端 $($remote.Count) 个 / 异常 $bad 处" -ForegroundColor Red
        if ($missing.Count) {
            Write-Host "  本地有、$label 没有（= 改了不会生效）：" -ForegroundColor Red
            $missing | Sort-Object | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
        }
        if ($extra.Count) {
            Write-Host "  $label 有、本地没有（= 旧文件仍会进镜像）：" -ForegroundColor Red
            $extra | Sort-Object | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
        }
        if ($diff.Count) {
            Write-Host "  内容不一致：" -ForegroundColor Red
            $diff | Sort-Object | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
        }
        throw "部署中止：$label 与本地不一致（未构建、未重启容器）"
    }
    Write-Host "[OK] $label 与本地逐文件一致：$($local.Count) 个文件" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 0) Dockerfile COPY 自检：同步范围必须覆盖构建集合
# ---------------------------------------------------------------------------
function Get-DockerfileCopyRoots([string]$dockerfile) {
    $roots = @()
    foreach ($line in Get-Content $dockerfile) {
        $t = $line.Trim()
        if ($t -notmatch '^(?i)COPY\s') { continue }
        $parts = @($t -split '\s+' | Where-Object { $_ })
        # COPY [--flag] src [src...] dest
        $srcs = @()
        for ($i = 1; $i -lt $parts.Count - 1; $i++) {
            if ($parts[$i].StartsWith('--')) { continue }
            $srcs += $parts[$i]
        }
        foreach ($s in $srcs) { $roots += ($s -split '/')[0] }
    }
    return $roots | Sort-Object -Unique
}

$dockerfileLocal = Join-Path $SourceRoot 'Dockerfile'
if (-not (Test-Path $dockerfileLocal)) { throw "本地缺少 Dockerfile：$dockerfileLocal" }
$knownRoots = @($SyncTrees) + @($StaticTrees) + @($BuildFiles)
$copyRoots = Get-DockerfileCopyRoots $dockerfileLocal
Write-Host ("COPY 源：{0}" -f ($copyRoots -join ', '))
foreach ($r in $copyRoots) {
    if ($knownRoots -notcontains $r) {
        throw "Dockerfile COPY 了未纳入同步范围的路径「$r」：请把它加入 -SyncTrees / -StaticTrees，否则会出现「同步了但没进镜像」"
    }
    if (-not $WithStatic -and ($StaticTrees -contains $r)) {
        Write-Host "[WARN] $r 由 Dockerfile COPY 但本次未同步（-WithStatic 可启用全量同步）；镜像内沿用远端既有版本" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# 1) 本地清单（= 本次要同步的准确集合）
# ---------------------------------------------------------------------------
$localManifest = Get-LocalManifest
Write-Host "本地待同步：$($localManifest.Count) 个文件（范围：$($Targets -join ' ')）"

$remoteTar = '/volume1/docker/hpa-trainer/_deploy.tar.gz'
$stageDir = '/volume1/docker/hpa-trainer/_deploy_stage'

# ---------------------------------------------------------------------------
# 2) 整目录同步（打包 → 上传 → 暂存目录解包 → 整目录替换）
#    Dockerfile 是整目录 COPY，所以这里也必须整目录替换，避免残留旧文件
# ---------------------------------------------------------------------------
if (-not $VerifyOnly) {
    $tmpTar = Join-Path $env:TEMP ("hpa_deploy_{0}.tar.gz" -f (Get-Date -Format 'yyyyMMddHHmmss'))
    $tarArgs = @('-czf', $tmpTar, '-C', $SourceRoot,
        '--exclude=__pycache__', '--exclude=*.pyc', '--exclude=.pytest_cache')
    if ($WithStatic) { $tarArgs += '--exclude=*.map' }
    $tarArgs += $Targets
    & tar @tarArgs
    if ($LASTEXITCODE -ne 0) { throw "本地打包失败（tar exit $LASTEXITCODE）" }

    scp -O $tmpTar "nas:$remoteTar"
    if ($LASTEXITCODE -ne 0) { throw '上传失败（scp）' }
    Remove-Item $tmpTar -Force

    $parentDir = $BuildDir.Substring(0, $BuildDir.LastIndexOf('/'))
    $replaceList = ($Targets -join ' ')
    $extract = @"
set -e
rm -rf $stageDir && mkdir -p $stageDir
tar -xzf $remoteTar -C $stageDir
cd $BuildDir
for t in $replaceList; do
  rm -rf "`$t"
  mv "$stageDir/`$t" .
done
rm -rf $stageDir $remoteTar
"@
    Invoke-RemoteScript $extract | Out-Null
    Write-Host "已整目录替换远端构建目录：$BuildDir"
}

# ---------------------------------------------------------------------------
# 3) 构建目录逐文件 md5 对账（含文件数）
# ---------------------------------------------------------------------------
$findList = ($Targets -join ' ')
$remoteManifestOut = Invoke-RemoteScript @"
cd $BuildDir
find $findList -type f ! -path '*__pycache__*' ! -name '*.pyc' ! -name '*.map' -print0 | xargs -0 md5sum
"@
$remoteManifest = ConvertFrom-Md5Lines $remoteManifestOut "构建目录 $BuildDir"
Compare-Manifest $localManifest $remoteManifest "构建目录 $BuildDir"

# ---------------------------------------------------------------------------
# 4) 构建镜像 + 重建容器
# ---------------------------------------------------------------------------
if ($NoBuild -and -not $VerifyContainer) {
    Write-Host "[SKIP] -NoBuild：跳过构建与容器校验（只验证了本地/构建目录）" -ForegroundColor Yellow
    Write-Host "VERIFIED: 本地=$($localManifest.Count) 构建目录=$($remoteManifest.Count)"
    exit 0
}

if (-not $NoBuild) {
    $buildScript = @"
set -e
cd $BuildDir
docker tag hpa-trainer:latest hpa-trainer:$Tag 2>/dev/null || true
docker build -t hpa-trainer:latest .
docker compose up -d --force-recreate
"@
    Invoke-RemoteScript $buildScript | Out-Null
    Write-Host "镜像已构建并重建容器（回滚 tag：hpa-trainer:$Tag）"
}

# 等容器进入 running（最多 90s）
$container = 'hpa-trainer'
for ($i = 0; $i -lt 45; $i++) {
    $nameOut = ssh nas "docker ps --filter name=hpa-trainer --format '{{.Names}}'" 2>$null
    if ($LASTEXITCODE -eq 0 -and "$nameOut".Trim()) {
        $container = ("$nameOut".Trim() -split "`n")[0].Trim()
        break
    }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------------------
# 5) 容器内逐文件 md5 对账 —— 证明「同步了且真的进了镜像」
#    只比代码树：dist/apps 是静态资源，若未 -WithStatic 则不在本地清单里
# ---------------------------------------------------------------------------
$verifyTrees = ($SyncTrees -join ' ')
$containerManifestOut = Invoke-RemoteScript @"
set -e
docker exec $container sh -c 'cd /app && find $verifyTrees -type f ! -path "*__pycache__*" ! -name "*.pyc" ! -name "*.map" -print0 | xargs -0 md5sum'
"@
$containerManifest = ConvertFrom-Md5Lines $containerManifestOut "容器 $container 内 /app"

$localCode = @{}
foreach ($k in $localManifest.Keys) {
    foreach ($t in $SyncTrees) {
        if ($k -eq $t -or $k.StartsWith("$t/")) { $localCode[$k] = $localManifest[$k]; break }
    }
}
Compare-Manifest $localCode $containerManifest "容器 $container 内 /app"

Write-Host ""
Write-Host "VERIFIED: 本地 = 构建目录 = 容器，三处逐文件 md5 一致" -ForegroundColor Green
Write-Host ("  文件数对账：本地 {0} = 构建目录 {1} = 容器 {2}" -f $localManifest.Count, $remoteManifest.Count, $containerManifest.Count) -ForegroundColor Green
Write-Host ("  回滚：docker tag hpa-trainer:{0} hpa-trainer:latest && docker compose up -d --force-recreate" -f $Tag)
