param(
    [string]$Kotlinc = 'kotlinc'
)

$ErrorActionPreference = 'Stop'
$buildDir = Join-Path $PSScriptRoot 'build'
$depsDir = Join-Path $buildDir 'deps'
New-Item -ItemType Directory -Force -Path $depsDir | Out-Null

$luaJar = Join-Path $depsDir 'luaj-jse-3.0.1.jar'
$jsonJar = Join-Path $depsDir 'json-20240303.jar'
if (-not (Test-Path -LiteralPath $luaJar)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar' -OutFile $luaJar
}
if (-not (Test-Path -LiteralPath $jsonJar)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jsonJar
}

$classpath = $luaJar + [System.IO.Path]::PathSeparator + $jsonJar
$checksJar = Join-Path $buildDir 'checks.jar'
# cmd.exe treats an unquoted semicolon as an argument separator for kotlinc.bat.
& $Kotlinc (Join-Path $PSScriptRoot 'check.kt') -classpath ('"' + $classpath + '"') -include-runtime -d $checksJar
if ($LASTEXITCODE -ne 0) { throw 'Protocol check compilation failed.' }

& java '-Dfile.encoding=UTF-8' -cp ($checksJar + [System.IO.Path]::PathSeparator + $classpath) CheckKt (Join-Path $PSScriptRoot 'main.lua')
if ($LASTEXITCODE -ne 0) { throw 'Protocol checks failed.' }
