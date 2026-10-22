param(
    [switch]$SkipTests,
    [switch]$SkipWeb
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:/DevelopSoftware/Java/JDK-21' }
if (-not $env:M2_HOME) { $env:M2_HOME = 'D:/DevelopSoftware/Apache-Maven/apache-maven-3.9.9' }
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$mavenBin = Join-Path $env:M2_HOME 'bin'
$env:Path = $javaBin + ';' + $mavenBin + ';' + $env:Path

Push-Location $projectRoot
try {
    $mavenArgs = @('-B', '-ntp', 'clean', 'package')
    if ($SkipTests) { $mavenArgs += '-DskipTests' }
    Write-Output '=== Backend build ==='
    & (Join-Path $mavenBin 'mvn.cmd') @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }

    if (-not $SkipWeb) {
        $webDir = Join-Path $projectRoot 'pcl-dsh-web'
        Write-Output '=== Frontend build ==='
        Push-Location $webDir
        try {
            if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
                & npm.cmd ci
                if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
            } else {
                Write-Output 'node_modules already present; skipping npm ci.'
            }
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        } finally {
            Pop-Location
        }
    }

    Write-Output ''
    Write-Output 'Build complete:'
    Write-Output ('  server jar : ' + (Join-Path $projectRoot 'pcl-dsh-server/target/pcl-dsh-linux.jar'))
    if (-not $SkipWeb) {
        Write-Output ('  web dist   : ' + (Join-Path $projectRoot 'pcl-dsh-web/dist'))
    }
} finally {
    Pop-Location
}
