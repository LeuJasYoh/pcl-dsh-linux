param(
    [string]$Version = '0.3.0',
    [string]$NodeVersion = '24.21.0',
    [string]$PnpmVersion = '10.34.0',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$distRoot = Join-Path $projectRoot 'target/dist'
$stageRoot = Join-Path $projectRoot 'target/release'
$pkgName = 'pcl-dsh-linux-' + $Version + '-linux-x64'
$stage = Join-Path $stageRoot $pkgName

if (-not $SkipBuild) {
    Write-Output '=== Building backend and frontend ==='
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}

Write-Output '=== Assembling release directory ==='
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
$release = Join-Path $stage 'release'
New-Item -ItemType Directory -Force (Join-Path $release 'static'), (Join-Path $release 'tools') | Out-Null

Copy-Item (Join-Path $projectRoot 'pcl-dsh-server/target/pcl-dsh-linux.jar') (Join-Path $release 'pcl-dsh-linux.jar')
Copy-Item (Join-Path $projectRoot 'pcl-dsh-web/dist/*') (Join-Path $release 'static') -Recurse
$Version | Set-Content (Join-Path $stage 'VERSION') -Encoding utf8

foreach ($item in @('LICENSE', 'LICENSES', 'THIRD_PARTY_NOTICES.md')) {
    Copy-Item (Join-Path $projectRoot $item) (Join-Path $stage $item) -Recurse
}
foreach ($item in @('install.sh', 'uninstall.sh', 'reset-password.sh', 'pcl-dsh-linux.service', 'nginx-pcl-dsh-linux.conf')) {
    $source = Join-Path $projectRoot ('packaging/' + $item)
    if (Test-Path $source) { Copy-Item $source (Join-Path $stage $item) }
}

Write-Output ('=== Downloading Node ' + $NodeVersion + ' linux-x64 ===')
$buildTools = Join-Path $projectRoot '.build-tools'
New-Item -ItemType Directory -Force $buildTools | Out-Null
$archiveName = 'node-v' + $NodeVersion + '-linux-x64.tar.xz'
$archivePath = Join-Path $buildTools $archiveName
$shasumsPath = Join-Path $buildTools ('SHASUMS256-' + $NodeVersion + '.txt')
if (-not (Test-Path $archivePath)) {
    Invoke-WebRequest ('https://nodejs.org/dist/v' + $NodeVersion + '/' + $archiveName) -OutFile $archivePath
}
if (-not (Test-Path $shasumsPath)) {
    Invoke-WebRequest ('https://nodejs.org/dist/v' + $NodeVersion + '/SHASUMS256.txt') -OutFile $shasumsPath
}
$line = Get-Content $shasumsPath | Where-Object { $_ -match (' ' + [regex]::Escape($archiveName) + '$') } | Select-Object -First 1
if (-not $line) { throw 'Cannot find Node checksum line.' }
$expected = ($line -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw 'Node archive checksum mismatch.' }

$extractRoot = Join-Path $buildTools ('node-extract-' + $NodeVersion)
Remove-Item $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $extractRoot | Out-Null
& tar.exe -xJf $archivePath -C $extractRoot --exclude 'node-v24.21.0-linux-x64/bin/npm' --exclude 'node-v24.21.0-linux-x64/bin/npx' --exclude 'node-v24.21.0-linux-x64/bin/corepack'
if ($LASTEXITCODE -ne 0) { throw 'Failed to extract Node archive.' }
$nodeSource = Join-Path $extractRoot ('node-v' + $NodeVersion + '-linux-x64')
Copy-Item $nodeSource (Join-Path $release 'tools/node') -Recurse

Write-Output ('=== Installing pnpm ' + $PnpmVersion + ' into release/tools/pnpm ===')
$pnpmDir = Join-Path $release 'tools/pnpm'
New-Item -ItemType Directory -Force $pnpmDir | Out-Null
& npm.cmd install --prefix $pnpmDir ('pnpm@' + $PnpmVersion) --ignore-scripts --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'Failed to install pnpm.' }
$pnpmEntry = Join-Path $pnpmDir 'node_modules/pnpm/bin/pnpm.cjs'
if (-not (Test-Path $pnpmEntry)) { throw 'pnpm entry point missing.' }

Write-Output '=== Creating tar.gz ==='
New-Item -ItemType Directory -Force $distRoot | Out-Null
$tarPath = Join-Path $distRoot ($pkgName + '.tar.gz')
Remove-Item $tarPath -Force -ErrorAction SilentlyContinue
& tar.exe -czf $tarPath -C $stageRoot $pkgName
if ($LASTEXITCODE -ne 0) { throw 'Failed to create tar.gz.' }
$tarHash = (Get-FileHash $tarPath -Algorithm SHA256).Hash.ToLowerInvariant()
($tarHash + '  ' + $pkgName + '.tar.gz') | Set-Content ($tarPath + '.sha256') -Encoding ascii

Write-Output '=== Creating source archive ==='
$sourceZip = Join-Path $distRoot ('pcl-dsh-linux-' + $Version + '-source.zip')
Remove-Item $sourceZip -Force -ErrorAction SilentlyContinue
$sourcePrefix = 'pcl-dsh-linux-' + $Version + '/'
& git -C $projectRoot archive --format=zip "--prefix=$sourcePrefix" -o $sourceZip HEAD
if ($LASTEXITCODE -ne 0) { Write-Output 'WARN: git archive failed (uncommitted changes are not included).'; $LASTEXITCODE = 0 }

Write-Output ''
Write-Output 'Package complete:'
Write-Output ('  tar.gz : ' + $tarPath)
Write-Output ('  sha256 : ' + $tarHash)
Write-Output ('  source : ' + $sourceZip)
