$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

$output = Join-Path (Split-Path $PSScriptRoot -Parent) 'clipboard-river-0.2.0.xipk'
$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::Create)
$archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($name in @('manifest.yaml', 'main.lua')) {
        $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        try {
            $bytes = [System.IO.File]::ReadAllBytes((Join-Path $PSScriptRoot $name))
            $entryStream.Write($bytes, 0, $bytes.Length)
        } finally { $entryStream.Dispose() }
    }
} finally {
    $archive.Dispose()
    $stream.Dispose()
}
Get-Item -LiteralPath $output | Select-Object FullName, Length
