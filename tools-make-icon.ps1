# Builds the Windows icon from assets/icon-desktop.png.
#
# That PNG is the icon master (assets/icon.svg) rendered at 1024: the central 72
# units of the 108-unit adaptive canvas — the part a phone launcher shows — as a
# rounded tile with transparent corners. Windows shows the whole square, so it
# arrives already framed and there is nothing left to do here but resize. The
# SVG itself can't be read from PowerShell, which is why the render is kept.
#
# Kept as a script rather than done once by hand so the icon can be regenerated
# when the artwork changes, and so it is obvious where the .ico came from.

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root "assets\icon-desktop.png"
$target = Join-Path $root "desktop\icon.ico"
$png = Join-Path $root "desktop\src\main\resources\icon.png"

$sizes = @(16, 24, 32, 48, 64, 128, 256)

$src = [System.Drawing.Image]::FromFile($source)
$crop = New-Object System.Drawing.Rectangle(0, 0, $src.Width, $src.Height)

function Resize-To([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $size, $size)), $crop, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    return $bmp
}

# A window icon for the running application, separate from the executable's.
$null = New-Item -ItemType Directory -Force (Split-Path $png)
$big = Resize-To 256
$big.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)

# Each entry is stored as a PNG, which every Windows since Vista reads and which
# avoids hand-rolling the AND/XOR bitmap masks the old BMP format wants.
$entries = @()
foreach ($size in $sizes) {
    $bmp = Resize-To $size
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $entries += [pscustomobject]@{ Size = $size; Bytes = $ms.ToArray() }
    $ms.Dispose(); $bmp.Dispose()
}
$big.Dispose(); $src.Dispose()

$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($out)

# ICONDIR: reserved, type 1 (icon), count
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$entries.Count)

# Directory entries come first, so every offset counts the whole directory.
$offset = 6 + (16 * $entries.Count)
foreach ($e in $entries) {
    # 256 is written as 0: the field is one byte and 256 does not fit.
    $dim = if ($e.Size -ge 256) { 0 } else { $e.Size }
    $w.Write([byte]$dim); $w.Write([byte]$dim)
    $w.Write([byte]0)            # palette entries, none for true colour
    $w.Write([byte]0)            # reserved
    $w.Write([uint16]1)          # colour planes
    $w.Write([uint16]32)         # bits per pixel
    $w.Write([uint32]$e.Bytes.Length)
    $w.Write([uint32]$offset)
    $offset += $e.Bytes.Length
}
foreach ($e in $entries) { $w.Write($e.Bytes) }

$w.Flush()
[System.IO.File]::WriteAllBytes($target, $out.ToArray())
$w.Dispose(); $out.Dispose()

"wrote $target ({0:N0} bytes, sizes: $($sizes -join ', '))" -f (Get-Item $target).Length
"wrote $png"
