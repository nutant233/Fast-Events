# Build and verify the 1.16.5 branch.
#   1) main sources compile to Java 8 bytecode (1.16.5 runs on Java 8)
#   2) the harness (Java 17, development only) checks rewriting and dispatch semantics
#   3) tools/src8 runs on a real Java 8 runtime: it proves the ClassLoader-based dispatcher
#      definition works there, and measures the numbers used in the README
# ASCII only on purpose: Windows PowerShell reads BOM-less scripts as ANSI.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$local = Join-Path $PSScriptRoot 'deps'

function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $candidates = @(
        (Join-Path $cache (Join-Path $group $artifact)),
        (Join-Path $local (Join-Path $group $artifact))
    )
    foreach ($dir in $candidates) {
        $hit = Get-ChildItem (Join-Path $dir $version) -Recurse -File -Filter "$artifact-$version.jar" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    throw "missing $group`:$artifact`:$version (gradle cache and tools\deps)"
}

function Find-Java8 {
    if ($env:JAVA8_HOME) { return (Join-Path $env:JAVA8_HOME 'bin\java.exe') }
    foreach ($dir in 'C:\Program Files\Zulu\zulu-8', 'C:\Program Files\Java\jdk1.8.0_202') {
        $exe = Join-Path $dir 'bin\java.exe'
        if (Test-Path $exe) { return $exe }
    }
    return $null
}

$jars = @(
    (Find-Jar 'net.minecraftforge' 'eventbus' '4.0.0'),
    (Find-Jar 'cpw.mods' 'modlauncher' '8.1.3'),
    (Find-Jar 'org.apache.logging.log4j' 'log4j-api' '2.15.0'),
    (Find-Jar 'org.ow2.asm' 'asm' '9.6'),
    (Find-Jar 'org.ow2.asm' 'asm-tree' '9.6'),
    (Find-Jar 'org.ow2.asm' 'asm-analysis' '9.6'),
    (Find-Jar 'org.jetbrains' 'annotations' '13.0'),
    (Find-Jar 'net.jodah' 'typetools' '0.6.3'),
    (Find-Jar 'net.minecraftforge' 'forgespi' '3.2.0')
)
$cp = $jars -join ';'
Set-Content (Join-Path $PSScriptRoot 'cp.txt') $cp -Encoding ascii

$out = Join-Path $PSScriptRoot 'out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null

# 1) main sources: Java 8 bytecode. -source/-target instead of --release, because --release 8 hides
#    sun.misc.Unsafe and the field offsets it provides are on the hot path.
$main = @(Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "compiling $($main.Count) main sources (java 8 bytecode, eventbus 4.0.0) ..."
& javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -cp $cp -d $out $main
if ($LASTEXITCODE -ne 0) { throw "javac (main) failed" }

# 2) harness: Java 17, development only, compiled against the main classes
$harnessOut = Join-Path $PSScriptRoot 'harness-out'
Remove-Item $harnessOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $harnessOut | Out-Null
$harness = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "compiling $($harness.Count) harness sources (--release 17) ..."
& javac --release 17 -encoding UTF-8 -nowarn -cp "$cp;$out" -d $harnessOut $harness
if ($LASTEXITCODE -ne 0) { throw "javac (harness) failed" }

Write-Host "running harness (eventbus 4.0.0) ..."
& java -cp "$cp;$out;$harnessOut" harness.Harness
$rc1 = $LASTEXITCODE

Write-Host ""
Write-Host "running harness (eventbus 4.0.0, -Deventbus.namelisteners=true) ..."
& java '-Deventbus.namelisteners=true' -cp "$cp;$out;$harnessOut" harness.Harness
$rc2 = $LASTEXITCODE

# 3) Java 8 smoke test and timing
$java8 = Find-Java8
$rc3 = 0
if ($java8) {
    $smokeOut = Join-Path $PSScriptRoot 'smoke-out'
    Remove-Item $smokeOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $smokeOut | Out-Null
    $smoke = @(Get-ChildItem (Join-Path $PSScriptRoot 'src8') -Recurse -Filter *.java | Where-Object { $_.FullName -notmatch '\\probe\\' } | ForEach-Object { $_.FullName })
    Write-Host ""
    Write-Host "compiling $($smoke.Count) smoke sources (java 8) ..."
    & javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -cp "$cp;$out" -d $smokeOut $smoke (Join-Path $PSScriptRoot 'src\net\minecraftforge\common\MinecraftForge.java')
    if ($LASTEXITCODE -ne 0) { throw "javac (smoke) failed" }
    # probe class for the class loader check: compiled but deliberately NOT on the runtime classpath
    $probeOut = Join-Path $PSScriptRoot 'probe-out'
    Remove-Item $probeOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $probeOut | Out-Null
    $probe = @(Get-ChildItem (Join-Path $PSScriptRoot 'src8') -Recurse -Filter Probe.java | ForEach-Object { $_.FullName })
    & javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -d $probeOut $probe
    if ($LASTEXITCODE -ne 0) { throw "javac (probe) failed" }

    Write-Host "running smoke on Java 8 ($java8) ..."
    & $java8 "-Dfastevents.probe.dir=$probeOut" -cp "$cp;$out;$smokeOut" smoke.Smoke
    $rc3 = $LASTEXITCODE

} else {
    Write-Host ""
    Write-Host "(Java 8 not found - set JAVA8_HOME to also run the smoke test; skipped)"
}

Write-Host ("rc1=" + $rc1 + " rc2=" + $rc2 + " rc3=" + $rc3)
exit ($rc1 -bor $rc2 -bor $rc3)
