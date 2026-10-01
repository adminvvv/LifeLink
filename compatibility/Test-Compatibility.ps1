param(
    [string[]]$Versions,
    [string]$Jar,
    [switch]$AllowUnsupportedVersions
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$matrix = Get-Content -Raw (Join-Path $PSScriptRoot 'versions.json') | ConvertFrom-Json -AsHashtable
if (-not $Versions) { $Versions = @($matrix.Keys | Sort-Object { [version]$_ }) }
foreach ($target in $Versions) {
    if (-not $matrix.ContainsKey($target)) { throw "Unlisted Minecraft version: $target" }
}
Push-Location $repo
try {
    if (-not $Jar) {
        & .\gradlew.bat build --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Baseline build failed.' }
        $modVersion = ((Get-Content gradle.properties | Where-Object { $_ -match '^mod_version=' }) -split '=', 2)[1]
        $Jar = "build/libs/lifelink-$modVersion.jar"
    }
    $artifact = (Resolve-Path -LiteralPath $Jar).Path
    $sha = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    $results = @()
    foreach ($target in $Versions) {
        $entry = $matrix[$target]
        $resultDir = Join-Path $repo "build/compatibility/$target"
        New-Item -ItemType Directory -Force $resultDir | Out-Null
        $gradleArgs = @('test', "-Pcompatibility_jar=$artifact", "-Pminecraft_version=$target",
            "-Pyarn_mappings=$($entry.yarn)", "-Pfabric_version=$($entry.fabric)", '--console=plain')
        if ($AllowUnsupportedVersions) { $gradleArgs += '-Pcompatibility_override_dependencies=true' }
        & .\gradlew.bat @gradleArgs 2>&1 | Tee-Object -FilePath (Join-Path $resultDir 'audit.log')
        $resultCode = $LASTEXITCODE
        if ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash -ne $sha) {
            throw 'Release artifact changed during the matrix run.'
        }
        $results += [pscustomobject][ordered]@{ minecraft = $target; fabric = $entry.fabric; sha256 = $sha;
            passed = ($resultCode -eq 0); dependencyOverrides = ([bool]$AllowUnsupportedVersions); log = "build/compatibility/$target/audit.log" }
    }
    ConvertTo-Json -InputObject @($results) -Depth 4 | Set-Content build/compatibility/results.json
    $results | Format-Table minecraft, passed, sha256
    if ($results.passed -contains $false) { exit 1 }
} finally {
    Pop-Location
}
