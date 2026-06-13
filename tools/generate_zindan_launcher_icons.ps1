Add-Type -AssemblyName System.Drawing

$SourcePath = "D:\Zindan\zindan_icon_source.png"
$ResRoot = "D:\Zindan5\app\src\main\res"
# Dark red shield background (#75081B), sampled from original Zindan launcher art.
$BgColor = [System.Drawing.Color]::FromArgb(255, 117, 8, 27)

function Save-Png {
    param(
        [System.Drawing.Bitmap]$Bitmap,
        [string]$Path
    )
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function Resize-Bitmap {
    param(
        [System.Drawing.Bitmap]$Source,
        [int]$Width,
        [int]$Height
    )
    return New-LegacyLauncherIcon -Source $Source -Size $Width
}

function New-AdaptiveForeground {
    param(
        [System.Drawing.Bitmap]$Source,
        [int]$Size,
        [double]$Scale = 0.82
    )
    $canvas = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $target = [int][Math]::Round($Size * $Scale)
    $offset = [int][Math]::Round(($Size - $target) / 2.0)
    $g.DrawImage($Source, $offset, $offset, $target, $target)
    $g.Dispose()

    # Drop checkerboard / neutral backdrop pixels exported with the source art.
    for ($y = 0; $y -lt $canvas.Height; $y++) {
        for ($x = 0; $x -lt $canvas.Width; $x++) {
            $pixel = $canvas.GetPixel($x, $y)
            if ($pixel.A -eq 0) { continue }
            $max = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
            $min = [Math]::Min($pixel.R, [Math]::Min($pixel.G, $pixel.B))
            if (($max - $min) -le 12 -and $max -ge 180) {
                $canvas.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
            }
        }
    }
    return $canvas
}

function New-LegacyLauncherIcon {
    param(
        [System.Drawing.Bitmap]$Source,
        [int]$Size,
        [double]$Scale = 0.82
    )
    $foreground = New-AdaptiveForeground -Source $Source -Size $Size -Scale $Scale
    $bmp = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $g.Clear($BgColor)
    $g.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $g.DrawImage($foreground, 0, 0)
    $g.Dispose()
    $foreground.Dispose()

    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $pixel = $bmp.GetPixel($x, $y)
            if ($pixel.A -lt 255) {
                $bmp.SetPixel($x, $y, $BgColor)
            }
        }
    }
    return $bmp
}

function New-SolidBackground {
    param([int]$Size)
    $bmp = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear($BgColor)
    $g.Dispose()
    return $bmp
}

if (-not (Test-Path $SourcePath)) {
    throw "Missing source icon: $SourcePath"
}

$srcImg = [System.Drawing.Bitmap]::FromFile($SourcePath)

$densityMap = @{
    "mipmap-mdpi"    = @{ legacy = 48; adaptive = 108 }
    "mipmap-hdpi"    = @{ legacy = 72; adaptive = 162 }
    "mipmap-xhdpi"   = @{ legacy = 96; adaptive = 216 }
    "mipmap-xxhdpi"  = @{ legacy = 144; adaptive = 324 }
    "mipmap-xxxhdpi" = @{ legacy = 192; adaptive = 432 }
}

foreach ($folder in $densityMap.Keys) {
    $legacy = $densityMap[$folder].legacy
    $adaptive = $densityMap[$folder].adaptive
    $base = Join-Path $ResRoot $folder

    $bg = New-SolidBackground -Size $adaptive
    Save-Png -Bitmap $bg -Path (Join-Path $base "ic_launcher_zindan_background.png")
    $bg.Dispose()

    $fg = New-AdaptiveForeground -Source $srcImg -Size $adaptive -Scale 0.82
    Save-Png -Bitmap $fg -Path (Join-Path $base "ic_launcher_zindan_foreground.png")
    $fg.Dispose()

    $icon = New-LegacyLauncherIcon -Source $srcImg -Size $legacy
    Save-Png -Bitmap $icon -Path (Join-Path $base "ic_launcher_zindan.png")
    Save-Png -Bitmap $icon -Path (Join-Path $base "ic_launcher_zindan_round.png")
    $icon.Dispose()
}

$srcImg.Dispose()
Write-Host "Zindan launcher icons generated in $ResRoot"
