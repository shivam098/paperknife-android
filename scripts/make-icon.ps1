# Draws the PaperKnife icon (a page with a paper knife across it) as an Android adaptive icon: the
# design of PaperKnife for Windows' and Mac's icons without its tile, which the launcher draws
# itself from the background color in its own shape. The drawing fits the 66 dp safe zone of the
# 108 dp foreground. Run on Windows with PowerShell 7 (or Windows PowerShell 5.1); both include
# System.Drawing:
#   pwsh -NoProfile -File scripts\make-icon.ps1
param(
    [string]$Resources = (Join-Path $PSScriptRoot '..\app\src\main\res')
)

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

function New-RoundedRectangle([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2 * $r
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-Foreground([int]$canvas) {
    $bitmap = New-Object System.Drawing.Bitmap $canvas, $canvas, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # The design's unit square (its tile) is 72/108 of the canvas, centered: every point of the page
    # and the knife lies within 0.44 of it from the center, so they stay in the 66 dp safe zone.
    $n = $canvas * 72.0 / 108.0
    $g.TranslateTransform(($canvas - $n) / 2, ($canvas - $n) / 2)

    # Page with a folded corner
    $left = $n * 0.25; $top = $n * 0.16; $right = $n * 0.75; $bottom = $n * 0.84; $fold = $n * 0.15
    $page = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF $left, $top),
        (New-Object System.Drawing.PointF ($right - $fold), $top),
        (New-Object System.Drawing.PointF $right, ($top + $fold)),
        (New-Object System.Drawing.PointF $right, $bottom),
        (New-Object System.Drawing.PointF $left, $bottom))
    $g.FillPolygon([System.Drawing.Brushes]::White, $page)
    $corner = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF ($right - $fold), $top),
        (New-Object System.Drawing.PointF ($right - $fold), ($top + $fold)),
        (New-Object System.Drawing.PointF $right, ($top + $fold)))
    $g.FillPolygon((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 196, 214, 240))), $corner)

    # Lines of text
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 176, 194, 222)), ([Math]::Max(1.0, $n * 0.035))
    foreach ($y in 0.36, 0.46, 0.56) {
        $g.DrawLine($pen, $n * 0.33, $n * $y, $n * 0.62, $n * $y)
    }

    # Paper knife: a slim orange blade with a darker handle, laid diagonally across the page
    $g.TranslateTransform($n * 0.5, $n * 0.56)
    $g.RotateTransform(-45)
    $blade = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF (-$n * 0.02), (-$n * 0.055)),
        (New-Object System.Drawing.PointF ($n * 0.40), (-$n * 0.012)),
        (New-Object System.Drawing.PointF ($n * 0.40), ($n * 0.012)),
        (New-Object System.Drawing.PointF (-$n * 0.02), ($n * 0.055)))
    $g.FillPolygon((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 159, 28))), $blade)
    $handle = New-RoundedRectangle (-$n * 0.34) (-$n * 0.07) ($n * 0.33) ($n * 0.14) ($n * 0.05)
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 196, 92, 0))), $handle)
    $g.Dispose()
    return $bitmap
}

$Resources = (Resolve-Path $Resources).Path
foreach ($density in @{ mdpi = 108; hdpi = 162; xhdpi = 216; xxhdpi = 324; xxxhdpi = 432 }.GetEnumerator()) {
    $folder = Join-Path $Resources "mipmap-$($density.Key)"
    New-Item -ItemType Directory -Force $folder | Out-Null
    $bitmap = New-Foreground $density.Value
    $bitmap.Save((Join-Path $folder 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

Write-Output "Wrote the icon's foreground to $Resources\mipmap-*"
