param([string]$IdeaHome = $env:IDEA_HOME)
$ErrorActionPreference = 'Stop'
if (!$IdeaHome) { throw '请通过 -IdeaHome 或 IDEA_HOME 指定 IDEA SDK 安装目录。' }
$root = Split-Path $PSScriptRoot
Set-Location $root
$sandbox = Join-Path $root '.sandbox'
$build = Join-Path $root 'build'
$jars = @(Get-ChildItem "$IdeaHome/lib" -Filter '*.jar' -Recurse) + @(Get-ChildItem "$IdeaHome/plugins" -Filter '*.jar' -Recurse)
$jars = @($jars | Where-Object { $_.FullName -notmatch '[\\/]lib[\\/]frontend[\\/]' })
$cp = ($jars | ForEach-Object FullName) -join ';'
New-Item -ItemType Directory -Force "$build/sandbox-classes", "$sandbox/config", "$sandbox/system", "$sandbox/plugins/harness/lib", "$sandbox/log" | Out-Null
# Pick an independent port: the user's installed bridge may already be using 17891.
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
try { $bridgeTestPort = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
New-Item -ItemType Directory -Force "$sandbox/config/agent-service-bridge" | Out-Null
@{ port=$bridgeTestPort; token='legacy-test-field-must-be-removed'; allowedConfigurations=@('portal','file-agent') } | ConvertTo-Json | Set-Content "$sandbox/config/agent-service-bridge/settings.json" -Encoding utf8
New-Item -ItemType Directory -Force "$sandbox/probe-classes" | Out-Null
& javac --release 21 -encoding UTF-8 -d "$sandbox/probe-classes" src/fixture/ProbeMain.java
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
$sources = Get-ChildItem src/sandbox/java -Filter '*.java' -Recurse | ForEach-Object FullName
$compile = @('--release','21','-encoding','UTF-8','-classpath',$cp,'-d',"$build/sandbox-classes") + $sources
$compile | ForEach-Object { '"' + $_.Replace('\','\\') + '"' } | Set-Content "$build/sandbox.args" -Encoding utf8
& javac "@$build/sandbox.args"
if ($LASTEXITCODE) { throw 'Sandbox harness compilation failed' }
Copy-Item 'src/sandbox/resources/*' "$build/sandbox-classes" -Recurse -Force
& jar --create --file "$sandbox/plugins/harness/lib/harness.jar" -C "$build/sandbox-classes" .
if ($LASTEXITCODE) { throw 'Sandbox harness packaging failed' }
# Separate paths and custom command prevent forwarding into the user's existing IDEA instance.
$launch = @(
    '-Xmx1024m','-Djava.awt.headless=true',"-Didea.home.path=$IdeaHome",
    "-Didea.config.path=$sandbox/config", "-Didea.system.path=$sandbox/system",
    "-Didea.plugins.path=$sandbox/plugins", "-Didea.log.path=$sandbox/log",
    '-Didea.is.internal=true','-Didea.trust.all.projects=true','-Didea.initially.ask.config=false',
    '-Didea.load.plugins.id=com.intellij.java,com.intellij.javaee.app.servers.integration',
    '-Didea.no.launcher=true',
    '-Didea.vendor.name=JetBrains','-Didea.platform.prefix=Idea','-Didea.paths.selector=AgentBridgeSandbox',
    '-Didea.updates.enabled=false','-Didea.check.signature=false',
    "-Dagentbridge.plugin=$build/package/idea-agent-bridge", "-Dagentbridge.report=$build/reports/sandbox.txt",
    "-Dagentbridge.root=$root", "-Didea.test.temp.path=$sandbox/test-temp",
    "-Dagentbridge.javac=$((Get-Command javac).Source)",
    '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader',
    '--add-opens=java.base/java.lang=ALL-UNNAMED','--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED','--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED','--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.awt=ALL-UNNAMED','--add-opens=java.desktop/sun.font=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    '-classpath',"$build/sandbox-classes;$cp",'dev.agentbridge.sandbox.SandboxStarter'
)
$product = Get-Content -LiteralPath "$IdeaHome/product-info.json" -Raw | ConvertFrom-Json
$launch = @($product.launch[0].additionalJvmArguments | Where-Object { $_ -like '--add-opens=*' -or $_ -like '-Dintellij.platform.runtime.repository.path=*' -or $_ -like '-Djna.*' -or $_ -like '-Dpty4j.*' } | ForEach-Object { $_.Replace('%IDE_HOME%', $IdeaHome) }) + $launch
$launch | ForEach-Object { '"' + $_.Replace('\','\\') + '"' } | Set-Content "$build/sandbox-launch.args" -Encoding utf8
& "$IdeaHome/jbr/bin/java.exe" "@$build/sandbox-launch.args" > "$build/reports/sandbox-console.txt" 2>&1
if ($LASTEXITCODE) {
    Get-Content "$build/reports/sandbox-console.txt" -Tail 70
    throw "Sandbox failed: exit $LASTEXITCODE"
}
Get-Content "$build/reports/sandbox.txt"
