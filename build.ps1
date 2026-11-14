$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

$manifest = Join-Path $PSScriptRoot 'manifest.yaml'
[string]$versionLine = Get-Content -LiteralPath $manifest -Encoding UTF8 | Where-Object { $_ -match '^version:' } | Select-Object -First 1
$version = ($versionLine -replace '^version:\s*', '').Trim().Trim('"', "'")
if (-not $version) { throw 'manifest.yaml must declare a version.' }

$buildDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$output = Join-Path $buildDir "clipboard-river-$version.xipk"
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
