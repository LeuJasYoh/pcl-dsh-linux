param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:/DevelopSoftware/Java/JDK-21' }
if (-not $env:M2_HOME) { $env:M2_HOME = 'D:/DevelopSoftware/Apache-Maven/apache-maven-3.9.9' }
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$mavenBin = Join-Path $env:M2_HOME 'bin'
$env:Path = $javaBin + ';' + $mavenBin + ';' + $env:Path
& (Join-Path $mavenBin 'mvn.cmd') @MavenArgs -f (Join-Path $projectRoot 'pom.xml')
exit $LASTEXITCODE
