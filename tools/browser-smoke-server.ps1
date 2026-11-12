param([string]$IdeaHome = $env:IDEA_HOME)
$ErrorActionPreference = 'Stop'
if (!$IdeaHome) { throw '请通过 -IdeaHome 或 IDEA_HOME 指定 IDEA SDK 安装目录。' }
$root=Split-Path $PSScriptRoot
Set-Location $root
$cp=(@(Get-ChildItem "$IdeaHome/lib/*.jar" | ForEach-Object FullName) + @("$root/build/classes","$root/build/test-classes")) -join ';'
& java '-Dfile.encoding=UTF-8' -classpath $cp dev.agentbridge.core.BrowserSmokeServer
if ($LASTEXITCODE) { throw "Browser fixture exited: $LASTEXITCODE" }
