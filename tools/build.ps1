# 离线编译 + 运行 harness（不需要 Gradle、不需要 Minecraft，只用本地 gradle 缓存里的 NeoForge bus 8.x）
# 基线：NeoForge 1.21.1 → net.neoforged:bus:8.0.5
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $dir = Join-Path (Join-Path $cache $group) $artifact
    $hit = Get-ChildItem (Join-Path $dir $version) -Recurse -File -Filter "$artifact-$version.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
        Select-Object -First 1
    if (-not $hit) { throw "missing $group`:$artifact`:$version in gradle cache" }
    $hit.FullName
}

$core = @(
    (Find-Jar 'net.neoforged' 'bus' '8.0.5'),
    (Find-Jar 'cpw.mods' 'modlauncher' '11.0.5'),
    (Find-Jar 'net.neoforged.fancymodloader' 'loader' '4.0.42'),
    (Find-Jar 'cpw.mods' 'securejarhandler' '3.0.8'),
    (Find-Jar 'org.ow2.asm' 'asm' '9.7.1'),
    (Find-Jar 'org.ow2.asm' 'asm-tree' '9.7.1'),
    (Find-Jar 'org.ow2.asm' 'asm-analysis' '9.7.1'),
    (Find-Jar 'org.apache.logging.log4j' 'log4j-api' '2.17.1'),
    (Find-Jar 'org.jetbrains' 'annotations' '13.0')
)
# 运行时需要 bus 的传递依赖
$runtime = @((Find-Jar 'net.jodah' 'typetools' '0.8.3'))
$cp = ($core -join ';')
$cpRun = (($core + $runtime) -join ';')
Set-Content (Join-Path $PSScriptRoot 'cp.txt') $cp -Encoding ascii

$out = Join-Path $PSScriptRoot 'out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "compiling $($sources.Count) sources (bus 8.0.5) ..."
& javac --release 21 -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "running harness (bus 8.0.5) ..."
& java -cp "$cpRun;$out" harness.Harness
$rc1 = $LASTEXITCODE

Write-Host ""
Write-Host "running harness (bus 8.0.5, -Dfastevents.audit=true) ..."
& java '-Dfastevents.audit=true' -cp "$cpRun;$out" harness.Harness
$rc2 = $LASTEXITCODE

Write-Host ""
Write-Host "running harness (bus 8.0.5, -Dfastevents.guard=false) ..."
& java '-Dfastevents.guard=false' -cp "$cpRun;$out" harness.Harness
$rc3 = $LASTEXITCODE

Write-Host ""
Write-Host "bench（一个用例一个 JVM）:"
$cases = @('post-native','post-fast','subscribe-native','subscribe-fast','mixed-shared','mixed-split','alloc','rewrite')
foreach ($case in $cases) {
    Write-Host ("  [" + $case + "]")
    & java -cp "$cpRun;$out" harness.Bench $case | Where-Object { $_ -match 'ns/post' }
}

Write-Host ("rc1=" + $rc1 + " rc2=" + $rc2 + " rc3=" + $rc3)
exit ($rc1 -bor $rc2 -bor $rc3)
