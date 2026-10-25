# Renders the CurseForge project icon (400x400) plus smaller sizes.
# Design: one event (lightning bolt) fanning out to three listeners, on a dark rounded square.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$out = $PSScriptRoot

function New-Icon([int]$s) {
    $k = $s / 400.0
    $bmp = New-Object System.Drawing.Bitmap $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $w = $s - 1
    $r = 84 * $k
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $r, $r, 180, 90)
    $path.AddArc($w - $r, 0, $r, $r, 270, 90)
    $path.AddArc($w - $r, $w - $r, $r, $r, 0, 90)
    $path.AddArc(0, $w - $r, $r, $r, 90, 90)
    $path.CloseFigure()

    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point 0, 0),
        (New-Object System.Drawing.Point 0, $s),
        [System.Drawing.Color]::FromArgb(255, 27, 35, 52),
        [System.Drawing.Color]::FromArgb(255, 9, 12, 19))
    $g.FillPath($bg, $path)
    $g.SetClip($path)

    # subtle sheen in the upper-left corner, linear so it cannot band like a radial halo
    $sheen = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF 0, 0),
        (New-Object System.Drawing.PointF $s, $s),
        [System.Drawing.Color]::FromArgb(34, 125, 211, 252),
        [System.Drawing.Color]::FromArgb(0, 125, 211, 252))
    $g.FillRectangle($sheen, 0, 0, $s, $s)
    $sheen.Dispose()
    # fan-out lines from the bolt to the listeners
    $linePen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(115, 96, 165, 250)), (5 * $k)
    $linePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $linePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $from = New-Object System.Drawing.PointF (206 * $k), (200 * $k)
    foreach ($y in 121, 200, 279) {
        $to = New-Object System.Drawing.PointF (288 * $k), ($y * $k)
        $g.DrawLine($linePen, $from, $to)
    }

    # listeners
    foreach ($y in 121, 200, 279) {
        $cx = 306 * $k
        $cy = $y * $k
        $radius = 21 * $k
        $dotBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.PointF ($cx - $radius), ($cy - $radius)),
            (New-Object System.Drawing.PointF ($cx + $radius), ($cy + $radius)),
            [System.Drawing.Color]::FromArgb(255, 199, 226, 255),
            [System.Drawing.Color]::FromArgb(255, 96, 165, 250))
        $g.FillEllipse($dotBrush, ($cx - $radius), ($cy - $radius), (2 * $radius), (2 * $radius))
        $dotBrush.Dispose()
    }

    # lightning bolt
    $points = @(
        (New-Object System.Drawing.PointF (203 * $k), (58 * $k)),
        (New-Object System.Drawing.PointF (116 * $k), (232 * $k)),
        (New-Object System.Drawing.PointF (163 * $k), (232 * $k)),
        (New-Object System.Drawing.PointF (146 * $k), (342 * $k)),
        (New-Object System.Drawing.PointF (244 * $k), (208 * $k)),
        (New-Object System.Drawing.PointF (186 * $k), (208 * $k))
    )
    $boltPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $boltPath.AddPolygon($points)
    $bolt = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF (0, (50 * $k))),
        (New-Object System.Drawing.PointF (0, (350 * $k))),
        [System.Drawing.Color]::FromArgb(255, 178, 246, 255),
        [System.Drawing.Color]::FromArgb(255, 56, 128, 245))
    $g.FillPath($bolt, $boltPath)
    $bolt.Dispose()

    $g.ResetClip()

    # subtle rim so the icon reads on both light and dark backgrounds
    $rim = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(28, 255, 255, 255)), (2 * $k)
    $g.DrawPath($rim, $path)

    $g.Dispose()
    return $bmp
}

foreach ($s in 400, 128, 64) {
    $bmp = New-Icon $s
    $file = Join-Path $out ("icon-$s.png")
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $file"
}
