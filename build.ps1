param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [ValidateSet('test','build','sandbox')][string]$Task = 'build'
)
$ErrorActionPreference = 'Stop'
if (!$IdeaHome -or !(Test-Path -LiteralPath (Join-Path $IdeaHome 'product-info.json'))) {
    throw '请通过 -IdeaHome 或 IDEA_HOME 指定完整的 IntelliJ IDEA Ultimate 2024.2 安装目录。'
}
Set-Location $PSScriptRoot
$build = Join-Path $PSScriptRoot 'build'
foreach ($relative in @('classes','test-classes')) {
    $target = [IO.Path]::GetFullPath((Join-Path $build $relative))
    if (!$target.StartsWith([IO.Path]::GetFullPath($build) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid build output path' }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
New-Item -ItemType Directory -Force "$build/classes", "$build/test-classes", "$build/reports" | Out-Null
$jars = @(Get-ChildItem "$IdeaHome/lib/*.jar") + @(Get-ChildItem "$IdeaHome/plugins/java/lib/*.jar") + @(Get-ChildItem "$IdeaHome/plugins/JavaEE/lib/*.jar") + @(Get-ChildItem "$IdeaHome/plugins/javaee-app-servers-impl/lib/*.jar") + @(Get-ChildItem "$IdeaHome/plugins/javaee-appServers-tomcat/lib/*.jar")
$cp = ($jars | ForEach-Object FullName) -join ';'
$sources = @(Get-ChildItem 'src/main/java' -Filter '*.java' -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($sources.Count) {
    $compile = @('--release','21','-encoding','UTF-8','-classpath',$cp,'-d',"$build/classes") + $sources
    $compile | ForEach-Object { '"' + $_.Replace('\','\\') + '"' } | Set-Content "$build/main.args" -Encoding utf8
    & javac '-J-Dfile.encoding=UTF-8' '-J-Duser.language=en' "@$build/main.args"
    if ($LASTEXITCODE) { throw 'Production compilation failed' }
}
Copy-Item 'src/main/resources/*' "$build/classes" -Recurse -Force
$tests = @(Get-ChildItem 'src/test/java' -Filter '*.java' -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($tests.Count) {
    $compile = @('--release','21','-encoding','UTF-8','-classpath',"$build/classes;$cp",'-d',"$build/test-classes") + $tests
    $compile | ForEach-Object { '"' + $_.Replace('\','\\') + '"' } | Set-Content "$build/test.args" -Encoding utf8
    & javac '-J-Dfile.encoding=UTF-8' '-J-Duser.language=en' "@$build/test.args"
    if ($LASTEXITCODE) { throw 'Test compilation failed' }
    & java '-Dfile.encoding=UTF-8' '-ea' '-classpath' "$build/test-classes;$build/classes;$cp" org.junit.runner.JUnitCore dev.agentbridge.core.BridgeServerTest 2>&1 | Tee-Object "$build/reports/unit-tests.txt"
    if ($LASTEXITCODE) { throw 'Tests failed' }
}
if ($Task -eq 'test') { return }
$package = "$build/package/idea-agent-bridge/lib"
$pluginVersion = ([xml](Get-Content -LiteralPath 'src/main/resources/META-INF/plugin.xml' -Raw)).'idea-plugin'.version
New-Item -ItemType Directory -Force $package | Out-Null
Copy-Item 'src/main/resources/*' "$build/classes" -Recurse -Force
& jar --create --file "$package/idea-agent-bridge.jar" -C "$build/classes" .
if ($LASTEXITCODE) { throw 'JAR creation failed' }
$archivePath = "$build/idea-agent-bridge-$pluginVersion.zip"
Compress-Archive -Path "$build/package/idea-agent-bridge" -DestinationPath $archivePath -Force
Get-FileHash $archivePath | Format-List | Out-String | Set-Content "$build/reports/package-sha256.txt"
Write-Host "Built $archivePath"
if ($Task -eq 'sandbox') { & "$PSScriptRoot/tools/sandbox-test.ps1" -IdeaHome $IdeaHome }
