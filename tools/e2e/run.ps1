# Compiles the e2e test mod, drops it plus this branch's jar and a Login Protection jar into a Forge 1.19.2
# server, starts the server once and prints the [E2E] lines.
# ASCII only on purpose: Windows PowerShell reads BOM-less scripts as ANSI.
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [Parameter(Mandatory = $true)][string]$LoginProtectionJar,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$TimeoutSeconds = 420
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$here = $PSScriptRoot
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java.exe' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { 'javac.exe' }
$jarTool = if ($JavaHome) { Join-Path $JavaHome 'bin\jar.exe' } else { 'jar.exe' }

$libraries = Join-Path $ServerDir 'libraries'
if (-not (Test-Path $libraries)) { throw "no libraries/ under $ServerDir - install the Forge server first" }

$modJar = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'fastevents-1.19-*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $modJar) { throw "build the branch first (gradlew build): build\libs\fastevents-1.19-*.jar is missing" }

$cp = ((Get-ChildItem $libraries -Recurse -Filter *.jar | ForEach-Object { $_.FullName }) -join ';')
$out = Join-Path $here 'out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "compiling $($sources.Count) test mod sources ..."
& $javac --release 17 -encoding UTF-8 -nowarn -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Copy-Item (Join-Path $here 'resources\META-INF') (Join-Path $out 'META-INF') -Recurse -Force

$mods = Join-Path $ServerDir 'mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Remove-Item (Join-Path $mods 'fastevents*.jar'), (Join-Path $mods 'loginprotection*.jar') -Force -ErrorAction SilentlyContinue
Push-Location $out
& $jarTool --create --file (Join-Path $mods 'fastevents-e2e.jar') .
Pop-Location
Copy-Item $modJar.FullName $mods -Force
Copy-Item $LoginProtectionJar (Join-Path $mods 'loginprotection.jar') -Force
Set-Content (Join-Path $ServerDir 'eula.txt') 'eula=true' -Encoding ascii

$log = Join-Path $here 'server.log'
Remove-Item $log -ErrorAction SilentlyContinue
Write-Host "starting the server (one run, it stops itself after the checks) ..."
Push-Location $ServerDir
$arguments = @('-Xmx2G', '@user_jvm_args.txt')
$argsFile = Get-ChildItem (Join-Path $libraries 'net\minecraftforge\forge') -Recurse -Filter 'win_args.txt' |
    Select-Object -First 1
if (-not $argsFile) { Pop-Location; throw "win_args.txt not found under libraries" }
$arguments += '@' + $argsFile.FullName
$arguments += 'nogui'
& $java @arguments *> $log
Pop-Location

Write-Host ""
Select-String -Path $log -Pattern '\[E2E\]|\[FastEvents\]' | ForEach-Object { $_.Line.Trim() }
Write-Host ""
Write-Host "full log: $log"
