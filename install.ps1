# wpilog-mcp installer for Windows
# Usage: irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex

$ErrorActionPreference = "Stop"
$repo = "TripleHelixProgramming/wpilog-mcp"
$installDir = Join-Path $env:USERPROFILE ".wpilog-mcp"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  wpilog-mcp Installer" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# --- Find Java ---

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

# --- Download latest release ---

# GitHub needs TLS 1.2, which Windows PowerShell 5.1 may not offer by default
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

Write-Host "Fetching latest release from GitHub..." -ForegroundColor Cyan
$releaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers @{ "User-Agent" = "wpilog-mcp-installer" }
$version = $releaseInfo.tag_name -replace '^v', ''
$jarAsset = $releaseInfo.assets | Where-Object { $_.name -like "*-all.jar" } | Select-Object -First 1

if (-not $jarAsset) {
    Write-Host "ERROR: No JAR asset found in release $($releaseInfo.tag_name)" -ForegroundColor Red
    exit 1
}

# The version names files below, so it must look like one
if ($version -notmatch '^[0-9A-Za-z.-]+$') {
    Write-Host "ERROR: The latest release's tag is not a version: $($releaseInfo.tag_name)" -ForegroundColor Red
    exit 1
}

Write-Host "Latest version: $version" -ForegroundColor Green
Write-Host ""

# The JAR owns the install layout and prints the PATH hint. Always clean up the download.
$temporaryJar = [System.IO.Path]::GetTempFileName()
try {
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile $temporaryJar -UseBasicParsing
    # Inspect the exit code even when an older JAR writes its usage error to stderr.
    $ErrorActionPreference = "Continue"
    & $javaExe -jar $temporaryJar install --install-dir $installDir
    $installStatus = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($installStatus -eq 0) {
        Write-Host "Install path: release $version install command."
    } else {
        # A current tagged script may delegate too. Do not recurse if its JAR cannot install.
        if ($env:WPILOG_INSTALL_FALLBACK -eq "1") {
            throw "Release installer fallback already attempted (exit $installStatus)."
        }
        $installerUrl = "https://raw.githubusercontent.com/$repo/v$version/install.ps1"
        Write-Host "Install path: release $version predates the install command; using $installerUrl."
        $temporaryInstaller = [System.IO.Path]::GetTempFileName()
        $previousFallback = $env:WPILOG_INSTALL_FALLBACK
        try {
            Invoke-WebRequest -Uri $installerUrl -OutFile $temporaryInstaller -UseBasicParsing
            $env:WPILOG_INSTALL_FALLBACK = "1"
            $global:LASTEXITCODE = 0
            # A child scope keeps the fetched script's variables out of our cleanup paths.
            & ([scriptblock]::Create((Get-Content -Raw -LiteralPath $temporaryInstaller)))
            if ($LASTEXITCODE -ne 0) {
                throw "Release installer failed (exit $LASTEXITCODE)."
            }
        } finally {
            $env:WPILOG_INSTALL_FALLBACK = $previousFallback
            Remove-Item -LiteralPath $temporaryInstaller -Force
        }
    }
} finally {
    Remove-Item -LiteralPath $temporaryJar -Force
}
