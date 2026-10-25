# Renders the optional description chart: vanilla vs Fast-Events, one panel per game version.
# Source is pure ASCII on purpose (Windows PowerShell 5.1 reads BOM-less scripts as ANSI);
# typographic characters are built from code points instead of being typed literally.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$times = [string][char]0x00D7
$arrow = [string][char]0x2192
$dash = [string][char]0x2014
$middot = [string][char]0x00B7

$W = 1000
$H = 570
$bmp = New-Object System.Drawing.Bitmap $W, $H, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point 0, 0),
    (New-Object System.Drawing.Point 0, $H),
    [System.Drawing.Color]::FromArgb(255, 22, 28, 41),
    [System.Drawing.Color]::FromArgb(255, 10, 13, 20))
$g.FillRectangle($bg, 0, 0, $W, $H)

$fontTitle = New-Object System.Drawing.Font 'Segoe UI Semibold', 18
$fontPanel = New-Object System.Drawing.Font 'Segoe UI Semibold', 13
$fontLabel = New-Object System.Drawing.Font 'Segoe UI', 11
$fontValue = New-Object System.Drawing.Font 'Segoe UI Semibold', 11
$fontNote = New-Object System.Drawing.Font 'Segoe UI', 9.5
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 236, 243, 255))
$grey = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 150, 166, 190))
$vanillaColour = [System.Drawing.Color]::FromArgb(255, 71, 85, 105)

$g.DrawString(('Fast-Events ' + $dash + ' nanoseconds per event post, 8 listeners, lower is better'), $fontTitle, $white, 34, 20)
$g.DrawString('Java 21, single thread, measured with the repository benchmark harness', $fontNote, $grey, 36, 54)

$barMax = 330.0
$maxScale = 60.0

function Draw-Panel([string]$title, [int]$x, [array]$rows, [string]$note) {
    $g.DrawString($title, $fontPanel, $white, $x, 88)

    # legend
    $sw = New-Object System.Drawing.SolidBrush $vanillaColour
    $g.FillRectangle($sw, $x, 120, 14, 10)
    $sw.Dispose()
    $g.DrawString('vanilla', $fontLabel, $grey, ($x + 20), 115)
    $fastBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 96, 165, 250))
    $g.FillRectangle($fastBrush, ($x + 88), 120, 14, 10)
    $fastBrush.Dispose()
    $g.DrawString('Fast-Events', $fontLabel, $grey, ($x + 108), 115)

    $y = 150
    foreach ($r in $rows) {
        $label, $vanilla, $fast = $r
        $g.DrawString($label, $fontLabel, $white, $x, $y)

        $wv = [double]($vanilla / $maxScale) * $barMax
        $wf = [double]($fast / $maxScale) * $barMax
        $h = 15

        $barBrush = New-Object System.Drawing.SolidBrush $vanillaColour
        $g.FillRectangle($barBrush, $x, ($y + 20), $wv, $h)
        $barBrush.Dispose()

        $fastBar = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.PointF $x, ($y + 20)),
            (New-Object System.Drawing.PointF ($x + [math]::Max($wf, 4)), ($y + 20)),
            [System.Drawing.Color]::FromArgb(255, 125, 211, 252),
            [System.Drawing.Color]::FromArgb(255, 59, 130, 246))
        $g.FillRectangle($fastBar, $x, ($y + 20 + $h + 6), $wf, $h)
        $fastBar.Dispose()

        $g.DrawString(("{0} ns" -f $vanilla), $fontValue, $grey, ($x + $wv + 8), ($y + 19))
        $g.DrawString(("{0} ns" -f $fast), $fontValue, $white, ($x + $wf + 8), ($y + 19 + $h + 6))

        $ratio = [math]::Round($vanilla / $fast, 1)
        $g.DrawString(($times + $ratio), $fontValue, $white, ($x + $barMax + 46), ($y + 20 + $h + 4))

        $y += 82
    }

    $g.DrawString($note, $fontNote, $grey, $x, $y)
}

$rows120 = @(
    ,@('@SubscribeEvent', 49.6, 9.3)
    ,@('addListener(this::x), 8 refs', 46.4, 8.2)
    ,@('addListener(consumer)', 15.7, 5.8)
    ,@('generic listeners', 52.2, 12.4)
)
$rows121 = @(
    ,@('@SubscribeEvent', 31.2, 8.8)
    ,@('addListener(consumer)', 19.0, 8.8)
)

Draw-Panel 'Minecraft 1.20.1 / Forge 47.4' 36 $rows120 'Wider arrays: 128 listeners 201 -> 88 ns, 512 listeners 1090 -> 556 ns'
Draw-Panel 'Minecraft 1.21.1 / NeoForge 21.1' 520 $rows121 'With no listeners the event object is not constructed at all'

$g.DrawString(('Fast-Events ' + $middot + ' LGPL 3.0 ' + $middot + ' github.com/nutant233/Fast-Events'), $fontNote, $grey, 36, ($H - 30))
$g.Dispose()

$bmp.Save((Join-Path $PSScriptRoot 'bench.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "wrote docs/curseforge/bench.png"
