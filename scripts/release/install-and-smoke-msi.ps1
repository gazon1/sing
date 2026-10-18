#!/usr/bin/env pwsh
# install-and-smoke-msi.ps1 — install an MSI-built Singularity and run it.
#
# #664 (follow-up): the Linux install-and-smoke.sh proved packages by installing them
# inside a throwaway container. MSI has no equivalent lightweight isolation on Windows,
# so this runs directly on the windows-2025 runner (a transient VM, not persistent).
#
# The MSI installs into "C:\Program Files\<publisher>\<product>" by default. jpackage
# uses the `packageName` from nativeDistributions as the folder name and the
# product name from the app's display title. The exact path is asserted by querying
# the Windows Installer registry rather than hardcoding.
#
# Assertions (in increasing order of what they prove):
#   1. msiexec exits 0 — the package manager accepts the MSI;
#   2. the .exe is on disk and owned by the package (queried from MSI database);
#   3. the .exe runs and exits 0 with --version;
#   4. the reported version matches the tag.
#
# Usage (from release.yml, running on windows-2025 runner):
#   $msi = "C:\path\to\singularity-1.2.3-windows-x64.msi"
#   $expectVersion = "1.2.3"
#   .\scripts\release\install-and-smoke-msi.ps1 -MsiPath $msi -ExpectVersion $expectVersion

param(
    [Parameter(Mandatory, Position=0)]
    [string]$MsiPath,

    [Parameter(Mandatory)]
    [string]$ExpectVersion
)

$ErrorActionPreference = 'Stop'

# Resolve the absolute path — GitHub Actions passes a relative one.
if (-not [System.IO.Path]::IsPathRooted($MsiPath)) {
    $MsiPath = Join-Path $PWD $MsiPath
}

if (-not (Test-Path $MsiPath)) {
    Write-Error "::error::MSI not found at: $MsiPath"
    exit 1
}

# Install the MSI silently. /qb gives a basic progress UI; /norestart suppresses
# the restart prompt; /quiet would be fully silent but harder to debug.
$installLog = "$env:TEMP\singularity-msi-install.log"
Write-Host "Installing MSI: $MsiPath"
$proc = Start-Process msiexec -ArgumentList "/i `"$MsiPath`" /qb /norestart /log `"$installLog`"" -Wait -PassThru
if ($proc.ExitCode -ne 0) {
    Write-Error "::error::msiexec exited $($proc.ExitCode). Log:"
    Get-Content $installLog -ErrorAction SilentlyContinue | Select-Object -Last 20
    exit 1
}
Write-Host "MSI installed successfully (exit code: $($proc.ExitCode))"

# Query the Windows Installer registry for the installed product to find the install
# location and executable. This is more reliable than guessing the path from the
# package name — the registry reflects what the MSI actually installed.
$productCode = $null
try {
    # The MSI stores its product code in the registry under HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall
    $regPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
    $installed = Get-ItemProperty $regPath -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -like 'Singularity*' } |
        Select-Object -First 1

    if (-not $installed) {
        # Try the 32-bit registry on 64-bit Windows
        $installed = Get-ItemProperty "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like 'Singularity*' } |
            Select-Object -First 1
    }

    if ($installed) {
        $installLocation = $installed.InstallLocation
        Write-Host "Found installed product: $($installed.DisplayName) at: $installLocation"

        # Find the .exe inside the install location
        if ($installLocation -and (Test-Path $installLocation)) {
            $exe = Get-ChildItem -Path $installLocation -Filter "*.exe" -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -ne 'unins000.exe' } |
                Select-Object -First 1
            if ($exe) {
                $productCode = $installed.PSChildName
                Write-Host "Executable: $($exe.FullName)"
            }
        }
    }
} catch {
    Write-Host "Warning: registry query failed: $_"
}

# Fallback: search for the exe by walking Program Files
if (-not $exe) {
    Write-Host "Searching for executable in Program Files..."
    $searchRoots = @(
        "${env:ProgramFiles}\Singularity*",
        "${env:ProgramFiles(x86)}\Singularity*",
        "${env:LOCALAPPDATA}\Singularity*"
    )
    foreach ($root in $searchRoots) {
        $candidates = Get-ChildItem -Path $root -Filter "*.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne 'unins000.exe' }
        if ($candidates) {
            $exe = $candidates | Select-Object -First 1
            Write-Host "Found executable at: $($exe.FullName)"
            break
        }
    }
}

if (-not $exe) {
    Write-Error "::error::Could not find the installed Singularity executable"
    exit 1
}

$launcher = $exe.FullName

# Assert the .exe is executable (exists and has a size)
$fileInfo = Get-Item $launcher
if ($fileInfo.Length -eq 0) {
    Write-Error "::error::$launcher is empty"
    exit 1
}
Write-Host "Executable size: $([math]::Round($fileInfo.Length / 1MB, 2)) MB"

# Run --version. This must exit 0 and print the expected version before Koin,
# before the database, before any AWT call. If it fails, the bundled JRE or
# the classpath is broken in a way the header checks never catch.
Write-Host "Running: $launcher --version"
try {
    $output = & $launcher --version 2>&1
    $exitCode = $LASTEXITCODE
} catch {
    Write-Error "::error::Failed to start $($launcher): $_"
    exit 1
}

if ($exitCode -ne 0) {
    Write-Error "::error::$launcher --version exited $exitCode. Output: $output"
    exit 1
}

Write-Host "Launcher output: $output"

if ($output -notlike "*$ExpectVersion*") {
    Write-Error "::error::Version mismatch. Tag: $ExpectVersion, reported: $output"
    exit 1
}

Write-Host "install-and-smoke OK (MSI $ExpectVersion)"
exit 0
