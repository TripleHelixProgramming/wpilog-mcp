# wpilog-mcp installer for Windows
# Usage: irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex
$ErrorActionPreference = "Stop"
$repo = "TripleHelixProgramming/wpilog-mcp"
$installDir = Join-Path $env:USERPROFILE ".wpilog-mcp"
$tag = $null
$preRelease = $false
$extension = $null
$interactive = -not [Console]::IsInputRedirected
$hasLogdir = $false
$hasTeam = $false
$advanced = $false
$installArgs = @()
for ($i = 0; $i -lt $args.Count; $i++) {
    $flag = $args[$i]
    switch ($flag) {
        { $_ -in '--tag', '--install-dir', '--logdir', '--team' } {
            $i++
            if ($i -ge $args.Count -or [string]::IsNullOrWhiteSpace($args[$i])) { throw "Missing value for $flag" }
            $value = $args[$i]
            switch ($flag) {
                '--tag' { $tag = $value }
                '--install-dir' { $installDir = $value; $advanced = $true }
                '--logdir' { $installArgs += @($flag, $value); $hasLogdir = $true; $advanced = $true }
                '--team' { $installArgs += @($flag, $value); $hasTeam = $true; $advanced = $true }
            }
        }
        '--pre-release' { $preRelease = $true }
        '--with-extension' { $extension = $true; $advanced = $true }
        '--without-extension' { $extension = $false }
        '--interactive' { $interactive = $true }
        '--non-interactive' { $interactive = $false }
        { $_ -in '--refresh', '--force' } { $installArgs += $flag; $advanced = $true }
        default { throw "Unknown installer argument: $flag" }
    }
}
if ($tag -and $preRelease) { throw 'Choose --tag or --pre-release, not both.' }
if ($tag -and $tag -notmatch '^[0-9A-Za-z.-]+$') { throw "Invalid release tag: $tag" }
if ($interactive) {
    if ((Test-Path (Join-Path $installDir 'servers.yaml')) -or (Test-Path (Join-Path $installDir 'servers.json'))) {
        Write-Host 'Keeping your existing server settings, including during --refresh.'
    } else {
        if (-not $hasLogdir) {
            $defaultLogdir = Join-Path $env:USERPROFILE 'riologs'
            $answer = Read-Host "Log directory [$defaultLogdir]"
            if ([string]::IsNullOrWhiteSpace($answer)) { $answer = $defaultLogdir }
            $installArgs += @('--logdir', $answer)
            while ($true) {
                $answer = Read-Host 'Another log directory (Enter to finish)'
                if ([string]::IsNullOrWhiteSpace($answer)) { break }
                $installArgs += @('--logdir', $answer)
            }
        }
        if (-not $hasTeam) {
            $answer = Read-Host 'Team number (Enter to leave unset)'
            if (-not [string]::IsNullOrWhiteSpace($answer)) { $installArgs += @('--team', $answer) }
        }
    }
    if ($null -eq $extension) {
        $answer = Read-Host 'Install the matching VS Code extension? [Y/n]'
        $extension = $answer -notmatch '^(n|no)$'
        if ($extension) { $advanced = $true }
    }
}

function Find-Java {
    # 1. WPILib JDK (scan for latest year)
    $wpilibDirs = @(
        (Join-Path $env:USERPROFILE "wpilib"),
        "C:\Users\Public\wpilib"
    )
    $candidates = foreach ($base in $wpilibDirs) {
        if (Test-Path $base) {
            Get-ChildItem -Path $base -Directory | Where-Object { $_.Name -match '^\d{4}$' }
        }
    }
    foreach ($year in ($candidates | Sort-Object Name -Descending)) {
        $javaExe = Join-Path $year.FullName "jdk\bin\java.exe"
        if (Test-Path $javaExe) {
            Write-Host "Found WPILib $($year.Name) JDK: $javaExe" -ForegroundColor Green
            return $javaExe
        }
    }

    # 2. JAVA_HOME
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        Write-Host "Found JAVA_HOME: $javaExe" -ForegroundColor Green
        return $javaExe
    }

    # 3. System PATH
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        Write-Host "Found java on PATH: $($javaCmd.Source)" -ForegroundColor Green
        return $javaCmd.Source
    }

    Write-Host "ERROR: Java 17+ is required but not found." -ForegroundColor Red
    Write-Host "Install the WPILib toolkit: https://docs.wpilib.org/en/stable/docs/zero-to-robot/step-2/wpilib-setup.html" -ForegroundColor Yellow
    exit 1
}

$javaExe = Find-Java

# Show the Java version. java -version writes to stderr, which Windows PowerShell 5.1 turns into
# an error that stops the script when $ErrorActionPreference is Stop.
$ErrorActionPreference = "Continue"
$versionOutput = & $javaExe -version 2>&1 | Select-Object -First 1
$ErrorActionPreference = "Stop"
Write-Host "Java version: $versionOutput"
Write-Host ""

# GitHub needs TLS 1.2, which Windows PowerShell 5.1 may not offer by default.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$api = "https://api.github.com/repos/$repo/releases/latest"
if ($tag) { $api = "https://api.github.com/repos/$repo/releases/tags/$tag" }
if ($preRelease) { $api = "https://api.github.com/repos/$repo/releases?per_page=1" }
$releases = Invoke-RestMethod -Uri $api -Headers @{ 'User-Agent' = 'wpilog-mcp-installer' }
$releaseInfo = @($releases)[0]
$version = $releaseInfo.tag_name -replace '^v', ''
if ($version -notmatch '^[0-9A-Za-z.-]+$') { throw "Release tag is not a version: $($releaseInfo.tag_name)" }
$jarAsset = $releaseInfo.assets | Where-Object { $_.name -eq "wpilog-mcp-$version-all.jar" } | Select-Object -First 1
$vsixAsset = $releaseInfo.assets | Where-Object { $_.name -eq "wpilog-analyzer-$version.vsix" } | Select-Object -First 1
if (-not $jarAsset) { throw "No JAR asset found in release $version" }
if ($extension -and -not $vsixAsset) { throw "No VSIX asset found in release $version" }
Write-Host "Installing release $version"

$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('wpilog-install-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $temporaryJar = Join-Path $scratch 'server.jar'
    $errorFile = Join-Path $scratch 'stderr.txt'
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile $temporaryJar -UseBasicParsing
    if ($extension) {
        $temporaryVsix = Join-Path $scratch 'extension.vsix'
        Invoke-WebRequest -Uri $vsixAsset.browser_download_url -OutFile $temporaryVsix -UseBasicParsing
        $installArgs += @('--with-extension', '--vsix', $temporaryVsix)
    }
    # Capture stderr separately: JVM banners and code's output must not hide the exit status.
    $ErrorActionPreference = 'Continue'
    & $javaExe -jar $temporaryJar install --install-dir $installDir @installArgs 2>$errorFile
    $installStatus = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $installError = Get-Content -Raw -LiteralPath $errorFile
    if ($installError) { [Console]::Error.Write($installError) }
    if ($installStatus -eq 0) {
        Write-Host "Install path: release $version install command."
    } else {
        if ($installError -notmatch 'Unknown option') { throw "Installation failed (exit $installStatus)." }
        if ($env:WPILOG_INSTALL_FALLBACK -eq '1') { throw "Release installer fallback already attempted (exit $installStatus)." }
        if ($advanced) { throw "Release $version predates the install command and cannot honor these options. Choose a newer --tag." }
        $installerUrl = "https://raw.githubusercontent.com/$repo/v$version/install.ps1"
        Write-Host "Install path: release $version predates the install command; using $installerUrl."
        $temporaryInstaller = Join-Path $scratch 'installer.ps1'
        $previousFallback = $env:WPILOG_INSTALL_FALLBACK
        try {
            Invoke-WebRequest -Uri $installerUrl -OutFile $temporaryInstaller -UseBasicParsing
            $env:WPILOG_INSTALL_FALLBACK = '1'
            $global:LASTEXITCODE = 0
            & ([scriptblock]::Create((Get-Content -Raw -LiteralPath $temporaryInstaller)))
            if ($LASTEXITCODE -ne 0) { throw "Release installer failed (exit $LASTEXITCODE)." }
        } finally {
            $env:WPILOG_INSTALL_FALLBACK = $previousFallback
        }
    }
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
