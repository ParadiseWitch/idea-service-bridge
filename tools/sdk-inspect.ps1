param([string[]]$Classes, [switch]$Bytecode, [switch]$Private, [string]$IdeaHome = $env:IDEA_HOME)
$ErrorActionPreference = 'Stop'
$sdk = $IdeaHome
if (!$sdk) { throw '请通过 -IdeaHome 或 IDEA_HOME 指定 IDEA SDK 安装目录。' }
$jars = @(Get-ChildItem "$sdk/lib/*.jar") + @(Get-ChildItem "$sdk/plugins/java/lib/*.jar") + @(Get-ChildItem "$sdk/plugins/javaee-app-servers-impl/lib/*.jar") + @(Get-ChildItem "$sdk/plugins/JavaEE/lib/*.jar") + @(Get-ChildItem "$sdk/plugins/javaee-appServers-tomcat/lib/*.jar")
$cp = ($jars | ForEach-Object FullName) -join ';'
$options = @('-J-Dfile.encoding=UTF-8', '-classpath', $cp)
if ($Bytecode) { $options += '-c' }
if ($Private) { $options += '-p' }
& javap @options @Classes
if ($LASTEXITCODE) { throw "javap failed: $LASTEXITCODE" }
