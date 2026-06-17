Add-Type -AssemblyName System.Drawing

$RepoRoot = Split-Path $PSScriptRoot -Parent
$ResRoot = Join-Path $RepoRoot "app\src\main\res"
$FreezeSource = if ($env:ZINDAN_FREEZE_ICON_SOURCE) {
    $env:ZINDAN_FREEZE_ICON_SOURCE
} else {
    Join-Path $RepoRoot "assets\zindan_icon_freeze_source.png"
}
$UnfreezeSource = if ($env:ZINDAN_UNFREEZE_ICON_SOURCE) {
    $env:ZINDAN_UNFREEZE_ICON_SOURCE
} else {
    Join-Path $RepoRoot "assets\zindan_icon_unfreeze_source.png"
}

# Sampled from sketch corner pixels (#223D2C).
$BgR = 34
$BgG = 61
$BgB = 44
$BgTolerance = 28

function Save-Png {
    param(
        [System.Drawing.Bitmap]$Bitmap,
        [string]$Path
    )
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function Test-BackgroundPixel {
    param([System.Drawing.Color]$Pixel)
    if ($Pixel.A -lt 16) { return $true }
    $dr = [Math]::Abs($Pixel.R - $BgR)
    $dg = [Math]::Abs($Pixel.G - $BgG)
    $db = [Math]::Abs($Pixel.B - $BgB)
    return ($dr -le $BgTolerance -and $dg -le $BgTolerance -and $db -le $BgTolerance)
}

function Test-ShadowPixel {
    param([System.Drawing.Color]$Pixel)
    if ($Pixel.A -lt 16) { return $true }
    $max = [Math]::Max($Pixel.R, [Math]::Max($Pixel.G, $Pixel.B))
    $min = [Math]::Min($Pixel.R, [Math]::Min($Pixel.G, $Pixel.B))
    if ($max -gt 95) { return $false }
    # Drop dark neutral halos exported with legacy icons.
    if (($max - $min) -le 18) { return $true }
    # Very dark warm/cool fringe from old drop shadow.
    if ($max -le 72 -and $Pixel.A -lt 240) { return $true }
    return $false
}

function New-TransparentForeground {
    param([System.Drawing.Bitmap]$Source)
    $canvas = New-Object System.Drawing.Bitmap $Source.Width, $Source.Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $Source.Height; $y++) {
        for ($x = 0; $x -lt $Source.Width; $x++) {
            $pixel = $Source.GetPixel($x, $y)
            if ((Test-BackgroundPixel $pixel) -or (Test-ShadowPixel $pixel)) {
                $canvas.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
            } else {
                $canvas.SetPixel($x, $y, $pixel)
            }
        }
    }
    return $canvas
}

function New-ToolbarIcon {
    param(
        [System.Drawing.Bitmap]$Foreground,
        [int]$Size,
        [double]$Scale = 0.86
    )
    $canvas = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $target = [int][Math]::Round($Size * $Scale)
    $offset = [int][Math]::Round(($Size - $target) / 2.0)
    $g.DrawImage($Foreground, $offset, $offset, $target, $target)
    $g.Dispose()
    return $canvas
}

function Write-IconSet {
    param(
        [System.Drawing.Bitmap]$Foreground,
        [string]$ToolbarName,
        [string]$ShortcutName
    )
    $densityMap = @{
        "drawable-mdpi"    = 24
        "drawable-hdpi"    = 36
        "drawable-xhdpi"   = 48
        "drawable-xxhdpi"  = 72
        "drawable-xxxhdpi" = 96
    }
    foreach ($folder in $densityMap.Keys) {
        $size = $densityMap[$folder]
        $icon = New-ToolbarIcon -Foreground $Foreground -Size $size
        $base = Join-Path $ResRoot $folder
        Save-Png -Bitmap $icon -Path (Join-Path $base "$ToolbarName.png")
        Save-Png -Bitmap $icon -Path (Join-Path $base "$ShortcutName.png")
        $icon.Dispose()
    }
}

foreach ($path in @($FreezeSource, $UnfreezeSource)) {
    if (-not (Test-Path $path)) {
        throw "Missing source icon: $path"
    }
}

$freezeSrc = [System.Drawing.Bitmap]::FromFile($FreezeSource)
$unfreezeSrc = [System.Drawing.Bitmap]::FromFile($UnfreezeSource)
$freezeFg = New-TransparentForeground -Source $freezeSrc
$unfreezeFg = New-TransparentForeground -Source $unfreezeSrc

Write-IconSet -Foreground $freezeFg -ToolbarName "ic_toolbar_freeze" -ShortcutName "ic_shortcut_freeze"
Write-IconSet -Foreground $unfreezeFg -ToolbarName "ic_toolbar_unfreeze" -ShortcutName "ic_shortcut_unfreeze"

$freezeFg.Dispose()
$unfreezeFg.Dispose()
$freezeSrc.Dispose()
$unfreezeSrc.Dispose()

Write-Host "Freeze/unfreeze toolbar + shortcut icons generated in $ResRoot"
