# 离线编译 + 运行 harness（不需要 Gradle、不需要 Minecraft）
# 基线：1.20.1 最新 Forge（47.4.x → eventbus 6.2.33）；另外用 eventbus 6.0.5 再跑一遍做兼容验证
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $dir = Join-Path (Join-Path $cache $group) $artifact
    $hit = Get-ChildItem (Join-Path $dir $version) -Recurse -File -Filter "$artifact-$version.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $hit) { throw "missing $group`:$artifact`:$version in gradle cache" }
    $hit.FullName
}

$jars = @(
    (Find-Jar 'net.minecraftforge' 'eventbus' '6.2.33'),
    (Find-Jar 'cpw.mods' 'modlauncher' '10.0.10'),
    (Find-Jar 'org.apache.logging.log4j' 'log4j-api' '2.17.1'),
    (Find-Jar 'org.ow2.asm' 'asm' '9.5'),
    (Find-Jar 'org.ow2.asm' 'asm-tree' '9.5'),
    (Find-Jar 'org.ow2.asm' 'asm-analysis' '9.5'),
    (Find-Jar 'org.jetbrains' 'annotations' '13.0'),
    (Find-Jar 'net.jodah' 'typetools' '0.6.3'),
    (Find-Jar 'net.minecraftforge' 'forgespi' '7.0.1')
)
$cp = $jars -join ';'
Set-Content (Join-Path $PSScriptRoot 'cp.txt') $cp -Encoding ascii

# 兼容用的 classpath：把 eventbus 6.2.33 换成 6.0.5（老版本 Forge 带的是它）
$cpCompat = $cp
try {
    $old = Find-Jar 'net.minecraftforge' 'eventbus' '6.0.5'
    $cpCompat = ($cp -split ';' | ForEach-Object { if ($_ -match 'eventbus-6\.2\.33\.jar') { $old } else { $_ } }) -join ';'
} catch {
    Write-Host "(eventbus 6.0.5 not cached, skip compat run)"
}

$out = Join-Path $PSScriptRoot 'out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "compiling $($sources.Count) sources (eventbus 6.2.33) ..."
& javac --release 17 -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "running harness (eventbus 6.2.33) ..."
& java -cp "$cp;$out" harness.Harness
$rc1 = $LASTEXITCODE

Write-Host ""
Write-Host "running harness (eventbus 6.0.5, compat) ..."
& java -cp "$cpCompat;$out" harness.Harness
$rc2 = $LASTEXITCODE

Write-Host ""
Write-Host "running harness (eventbus 6.2.33, -Deventbus.namelisteners=true) ..."
& java '-Deventbus.namelisteners=true' -cp "$cp;$out" harness.Harness
$rc3 = $LASTEXITCODE

Write-Host ""
Write-Host "bench（一个用例一个 JVM）:"
$cases = @('eight-native','eight-fast','eight-markers','eight-dispatch-only','alloc','asm-native','asm-fast','arg1-native','arg1-fast','generic-native','generic-fast','alt-native','alt-shared','alt-split','alt3-native','alt3-shared','alt3-split','asm-parse','asm-wrapper-gen','lambda-gen','unregister-all','unregister-one')
foreach ($c in $cases) {
    Write-Host ("  [" + $c + "]")
    & java -cp "$cp;$out" harness.Bench $c | Where-Object { $_ -match 'ns/' }
}

Write-Host ("rc1=" + $rc1 + " rc2=" + $rc2 + " rc3=" + $rc3)
exit ($rc1 -bor $rc2 -bor $rc3)
