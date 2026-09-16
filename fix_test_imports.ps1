$root = "app\src\test\java\com\phoneagent"

$map = @{
  "agent\EngineRulesTest.kt"                     = @("com.phoneagent.domain.rules.EngineRules")
  "agent\ShellCommandsTest.kt"                   = @("com.phoneagent.domain.rules.ShellCommands")
  "decision\LocalDecisionEngineTest.kt"          = @("com.phoneagent.domain.rules.LocalDecisionEngine")
  "execution\IntentResolverTest.kt"              = @("com.phoneagent.engine.execution.IntentResolver")
  "execution\IntentTranslatorStrategyTest.kt"     = @("com.phoneagent.engine.execution.AppNameResolver","com.phoneagent.engine.execution.CapabilityManager","com.phoneagent.engine.execution.IntentResolver","com.phoneagent.engine.execution.IntentTranslator")
  "execution\IntentTranslatorTest.kt"            = @("com.phoneagent.engine.execution.AppNameResolver","com.phoneagent.engine.execution.CapabilityManager","com.phoneagent.engine.execution.IntentResolver","com.phoneagent.engine.execution.IntentTranslator")
  "execution\LongRunModelTest.kt"                = @("com.phoneagent.engine.execution.AppNameResolver","com.phoneagent.engine.execution.CapabilityManager","com.phoneagent.engine.execution.IntentResolver","com.phoneagent.engine.execution.IntentTranslator")
  "execution\RealModelDecisionTest.kt"           = @("com.phoneagent.engine.execution.AppNameResolver","com.phoneagent.engine.execution.CapabilityManager","com.phoneagent.engine.execution.IntentResolver","com.phoneagent.engine.execution.IntentTranslator")
  "execution\SimulatedTaskRunTest.kt"            = @("com.phoneagent.engine.execution.AppNameResolver","com.phoneagent.engine.execution.CapabilityManager","com.phoneagent.engine.execution.IntentResolver","com.phoneagent.engine.execution.IntentTranslator")
  "mcp\McpClientTest.kt"                         = @("com.phoneagent.feature.mcp.McpClient","com.phoneagent.feature.mcp.McpException","com.phoneagent.feature.mcp.McpServerConfig","com.phoneagent.feature.mcp.McpTransport")
  "perception\ControlTreeBuilderTest.kt"         = @("com.phoneagent.engine.perception.ControlTreeBuilder")
  "perception\PageFingerprintTest.kt"            = @("com.phoneagent.engine.perception.PageFingerprint")
  "prompt\PromptTemplateEngineTest.kt"           = @("com.phoneagent.engine.prompt.PromptTemplate","com.phoneagent.engine.prompt.PromptTemplateEngine","com.phoneagent.engine.prompt.PromptVars","com.phoneagent.data.store.PromptTemplateStore")
  "shizuku\adb\AdbKeyStoreTest.kt"               = @("com.phoneagent.device.shell.AdbKeyStore")
  "shizuku\adb\AdbProtocolTest.kt"               = @("com.phoneagent.device.shell.AdbProtocol")
  "shizuku\adb\AdbTcpSessionTest.kt"             = @("com.phoneagent.device.shell.AdbKeyStore","com.phoneagent.device.shell.AdbProtocol","com.phoneagent.device.shell.AdbSocket","com.phoneagent.device.shell.AdbTcpSession","com.phoneagent.device.shell.AdbTimeouts")
  "shizuku\adb\MdnsAdbResolverTest.kt"           = @("com.phoneagent.device.shell.MdnsAdbResolver")
  "shizuku\adb\ShizukuBootstrapTest.kt"          = @("com.phoneagent.device.shell.AdbBootstrapTransport","com.phoneagent.device.shell.AdbError","com.phoneagent.device.shell.AdbPairOutcome","com.phoneagent.device.shell.AdbPhase","com.phoneagent.device.shell.AdbStartOutcome","com.phoneagent.device.shell.ShizukuBootstrap")
  "shizuku\adb\WirelessAdbStateMachineTest.kt"   = @("com.phoneagent.device.shell.AdbPhase","com.phoneagent.device.shell.WirelessAdbStateMachine")
  "skill\SkillCompatTest.kt"                     = @("com.phoneagent.feature.skill.McpSkillTarget","com.phoneagent.feature.skill.Skill","com.phoneagent.feature.skill.SkillCatalog","com.phoneagent.feature.skill.SkillCompat","com.phoneagent.feature.skill.SkillInvocation","com.phoneagent.feature.skill.SkillParam","com.phoneagent.feature.skill.SkillSource")
  "skill\SkillExecutionGatewayTest.kt"           = @("com.phoneagent.feature.skill.McpSkillTarget","com.phoneagent.feature.skill.Skill","com.phoneagent.feature.skill.SkillCatalog","com.phoneagent.feature.skill.SkillExecutionGateway","com.phoneagent.feature.skill.SkillInvocation","com.phoneagent.feature.skill.SkillParam","com.phoneagent.feature.skill.SkillSource")
  "skill\SkillRegistryTest.kt"                   = @("com.phoneagent.feature.skill.McpSkillTarget","com.phoneagent.feature.skill.Skill","com.phoneagent.feature.skill.SkillCatalog","com.phoneagent.feature.skill.SkillParam","com.phoneagent.feature.skill.SkillSource")
  "task\TemplateMatcherTest.kt"                  = @("com.phoneagent.data.store.TaskTemplate","com.phoneagent.feature.task.TemplateMatcher")
}

$patched = 0
foreach ($rel in $map.Keys) {
  $path = Join-Path $root $rel
  if (-not (Test-Path $path)) { Write-Host "MISSING $path"; continue }
  $lines = New-Object System.Collections.Generic.List[string]
  [System.IO.File]::ReadAllLines($path, [Text.Encoding]::UTF8) | ForEach-Object { $lines.Add($_) }

  $changed = $false
  foreach ($need in ($map[$rel] | Sort-Object)) {
    $exists = $false
    $firstImport = -1; $lastImport = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
      if ($lines[$i] -match '^import\s+(.+)$') {
        if ($firstImport -lt 0) { $firstImport = $i }
        $lastImport = $i
        if ($Matches[1].Trim() -eq $need) { $exists = $true }
      }
    }
    if ($exists) { continue }
    if ($firstImport -lt 0) { Write-Host "NO IMPORT BLOCK in $rel"; continue }
    $insertAt = $lastImport + 1
    for ($i = $firstImport; $i -le $lastImport; $i++) {
      if ($lines[$i] -match '^import\s+(.+)$') {
        if ([string]::CompareOrdinal($Matches[1].Trim(), $need) -gt 0) { $insertAt = $i; break }
      }
    }
    $lines.Insert($insertAt, "import $need")
    $changed = $true
  }
  if ($changed) {
    [System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding($false)))
    $patched++
    Write-Host "patched $rel"
  }
}
Write-Host "files patched = $patched"