@echo off
setlocal

set "UUIDMIGRATE_SELF=%~f0"
set "UUIDMIGRATE_HOST_DIR=%~dp0"
set "UUIDMIGRATE_TMP=%TEMP%\uuidmigrate-archive-%RANDOM%%RANDOM%%RANDOM%.ps1"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
 "$self = [System.IO.Path]::GetFullPath($env:UUIDMIGRATE_SELF); " ^
 "$marker = '#__UUIDMIGRATE_PS_PAYLOAD__'; " ^
 "$lines = Get-Content -LiteralPath $self -Encoding UTF8; " ^
 "$index = [Array]::IndexOf($lines, $marker); " ^
 "if ($index -lt 0) { throw 'payload marker not found' }; " ^
 "$payload = $lines[($index + 1)..($lines.Length - 1)]; " ^
 "Set-Content -LiteralPath $env:UUIDMIGRATE_TMP -Value $payload -Encoding UTF8"
if errorlevel 1 (
    echo [ERROR] Failed to extract embedded archive script.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%UUIDMIGRATE_TMP%" %*
set "UUIDMIGRATE_EXIT=%ERRORLEVEL%"

del "%UUIDMIGRATE_TMP%" >nul 2>nul
exit /b %UUIDMIGRATE_EXIT%
#__UUIDMIGRATE_PS_PAYLOAD__
param(
    [string]$SnapshotId
)

$ErrorActionPreference = "Stop"

$serverRoot = [System.IO.Path]::GetFullPath($env:UUIDMIGRATE_HOST_DIR)
$pluginRoot = Join-Path $serverRoot "plugins\UUIDMigrate"
$logDir = Join-Path $pluginRoot "logs"
$reportDir = Join-Path $pluginRoot "reports"
$floodgateUuidPrefix = "00000000-0000-0000-0009-"

if ([string]::IsNullOrWhiteSpace($SnapshotId)) {
    $SnapshotId = Read-Host "Enter snapshot-id"
}

$SnapshotId = $SnapshotId.Trim()
if ($SnapshotId.EndsWith(".")) {
    $SnapshotId = $SnapshotId.Substring(0, $SnapshotId.Length - 1)
}

if ([string]::IsNullOrWhiteSpace($SnapshotId)) {
    Write-Host "[ERROR] snapshot-id must not be empty."
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $serverRoot "plugins") -PathType Container)) {
    Write-Host "[ERROR] plugins directory is missing. Put this script in the server root."
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $serverRoot "world") -PathType Container)) {
    Write-Host "[ERROR] world directory is missing. Make sure this is the server root."
    exit 1
}

$targetRoot = Join-Path $pluginRoot "legacy-data\$SnapshotId"
if ((Test-Path -LiteralPath $targetRoot) -and (Get-ChildItem -LiteralPath $targetRoot -Force | Select-Object -First 1)) {
    Write-Host "[ERROR] target snapshot directory already exists and is not empty: $targetRoot"
    exit 1
}

$confirm = Read-Host "Confirm the server is fully stopped [y/N]"
$confirm = $confirm.Trim()
if ($confirm -ine "y") {
    Write-Host "Cancelled."
    exit 1
}

$null = New-Item -ItemType Directory -Force -Path $pluginRoot, $logDir, $reportDir, $targetRoot

$logFile = Join-Path $logDir "archive-$SnapshotId.log"
$manifestFile = Join-Path $reportDir "archive-manifest-$SnapshotId.txt"

if (Test-Path -LiteralPath $logFile) {
    Remove-Item -LiteralPath $logFile -Force
}
if (Test-Path -LiteralPath $manifestFile) {
    Remove-Item -LiteralPath $manifestFile -Force
}

function Write-ArchiveLog {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8
}

function Write-Manifest {
    param([string]$Message)
    Add-Content -LiteralPath $manifestFile -Value $Message -Encoding UTF8
}

function Ensure-ParentDirectory {
    param([string]$Path)
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        $null = New-Item -ItemType Directory -Force -Path $parent
    }
}

function Test-IsOfflineUuidV3 {
    param([string]$Stem)
    if ([string]::IsNullOrWhiteSpace($Stem) -or $Stem.Length -lt 36) {
        return $false
    }
    if ($Stem.StartsWith($floodgateUuidPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    if ($Stem[8] -ne '-' -or $Stem[13] -ne '-' -or $Stem[18] -ne '-' -or $Stem[23] -ne '-') {
        return $false
    }
    return $Stem.Substring(14, 1).Equals("3", [System.StringComparison]::OrdinalIgnoreCase)
}

function Move-OfflineUuidFiles {
    param(
        [string]$RelativeDirectory,
        [string]$Filter
    )

    $sourceDir = Join-Path $serverRoot $RelativeDirectory
    if (-not (Test-Path -LiteralPath $sourceDir -PathType Container)) {
        Write-ArchiveLog "SKIP $RelativeDirectory (missing)"
        Write-Manifest "SKIP,$RelativeDirectory"
        return
    }

    $files = @(Get-ChildItem -LiteralPath $sourceDir -File -Filter $Filter)
    if ($files.Count -eq 0) {
        Write-ArchiveLog "SKIP $RelativeDirectory (no matching files)"
        Write-Manifest "SKIP,$RelativeDirectory"
        return
    }

    foreach ($file in $files) {
        $stem = $file.BaseName
        $relativeFile = "$RelativeDirectory\$($file.Name)"
        if ($stem.StartsWith($floodgateUuidPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            Write-ArchiveLog "KEEP $relativeFile (floodgate uuid)"
            Write-Manifest "KEEP_FLOODGATE,$relativeFile"
            continue
        }
        if (-not (Test-IsOfflineUuidV3 -Stem $stem)) {
            $reason = if ($stem.Length -ge 36 -and $stem[8] -eq '-' -and $stem[13] -eq '-' -and $stem[18] -eq '-' -and $stem[23] -eq '-') {
                "not offline uuid v3"
            } else {
                "non-uuid filename"
            }
            $tag = if ($reason -eq "not offline uuid v3") { "KEEP_NON_OFFLINE" } else { "KEEP_OTHER" }
            Write-ArchiveLog "KEEP $relativeFile ($reason)"
            Write-Manifest "$tag,$relativeFile"
            continue
        }

        $destination = Join-Path $targetRoot $relativeFile
        Ensure-ParentDirectory -Path $destination
        if (Test-Path -LiteralPath $destination) {
            throw "Target exists: $destination"
        }
        Move-Item -LiteralPath $file.FullName -Destination $destination
        Write-ArchiveLog "MOVE $relativeFile"
        Write-Manifest "MOVE,$relativeFile"
    }

    Write-Manifest "MOVE_FILTERED,$RelativeDirectory"
}

function Copy-PathEntry {
    param([string]$RelativePath)

    $source = Join-Path $serverRoot $RelativePath
    $destination = Join-Path $targetRoot $RelativePath

    if (-not (Test-Path -LiteralPath $source)) {
        Write-ArchiveLog "SKIP $RelativePath (missing)"
        Write-Manifest "SKIP,$RelativePath"
        return
    }

    Ensure-ParentDirectory -Path $destination
    if (Test-Path -LiteralPath $destination) {
        throw "Target exists: $destination"
    }

    if (Test-Path -LiteralPath $source -PathType Container) {
        Copy-Item -LiteralPath $source -Destination $destination -Recurse
    } else {
        Copy-Item -LiteralPath $source -Destination $destination
    }

    Write-ArchiveLog "COPY $RelativePath"
    Write-Manifest "COPY,$RelativePath"
}

function Move-PathEntry {
    param(
        [string]$RelativePath,
        [switch]$Optional
    )

    $source = Join-Path $serverRoot $RelativePath
    $destination = Join-Path $targetRoot $RelativePath

    if (-not (Test-Path -LiteralPath $source)) {
        $tag = if ($Optional) { "SKIP_OPTIONAL" } else { "SKIP" }
        $note = if ($Optional) { "missing optional sidecar" } else { "missing" }
        Write-ArchiveLog "SKIP $RelativePath ($note)"
        Write-Manifest "$tag,$RelativePath"
        return
    }

    Ensure-ParentDirectory -Path $destination
    if (Test-Path -LiteralPath $destination) {
        throw "Target exists: $destination"
    }

    Move-Item -LiteralPath $source -Destination $destination
    Write-ArchiveLog "MOVE $RelativePath"
    Write-Manifest "MOVE,$RelativePath"
}

try {
    Write-ArchiveLog "Archive started, snapshot-id=$SnapshotId"
    Write-Manifest "snapshot-id=$SnapshotId"
    Write-Manifest "server-root=$serverRoot"
    Write-Manifest "target-root=$(Join-Path 'plugins\UUIDMigrate' "legacy-data\$SnapshotId")"
    Write-Manifest "offline-filter=uuid-version-3-only"
    Write-Manifest "floodgate-prefix=$floodgateUuidPrefix"

    Move-OfflineUuidFiles -RelativeDirectory "world\playerdata" -Filter "*.dat"
    Move-OfflineUuidFiles -RelativeDirectory "world\stats" -Filter "*.json"
    Move-OfflineUuidFiles -RelativeDirectory "world\advancements" -Filter "*.json"
    Move-OfflineUuidFiles -RelativeDirectory "world\players\data" -Filter "*.dat"
    Move-OfflineUuidFiles -RelativeDirectory "world\players\stats" -Filter "*.json"
    Move-OfflineUuidFiles -RelativeDirectory "world\players\advancements" -Filter "*.json"
    Move-OfflineUuidFiles -RelativeDirectory "plugins\Essentials\userdata" -Filter "*.yml"

    Move-PathEntry -RelativePath "plugins\XConomy\playerdata\data.db"
    Move-PathEntry -RelativePath "plugins\XConomy\playerdata\data.db-wal" -Optional
    Move-PathEntry -RelativePath "plugins\XConomy\playerdata\data.db-shm" -Optional
    Copy-PathEntry -RelativePath "plugins\PlayerTitle\PlayerTitle.db"
    Copy-PathEntry -RelativePath "plugins\PlayerTask\PlayerTask.db"
    Copy-PathEntry -RelativePath "plugins\LiteSignIn\Database.db"
    Copy-PathEntry -RelativePath "plugins\LuckPerms\luckperms-h2-v2.mv.db"
    Copy-PathEntry -RelativePath "plugins\SimplePlaytime\data.json"
    Copy-PathEntry -RelativePath "plugins\HoloMobHealth\database.db"
    Copy-PathEntry -RelativePath "plugins\XyKit\data.yml"
    Copy-PathEntry -RelativePath "plugins\fakeplayer\data.db"
    Copy-PathEntry -RelativePath "plugins\Residence\Save"
    Copy-PathEntry -RelativePath "plugins\QuickShop-Hikari\shops.mv.db"

    Write-ArchiveLog "Archive completed."
    Write-Manifest "status=SUCCESS"
    Write-Manifest "excluded=Floodgate,JavaOnlineUuid,AuthMe,CoreProtect,Images,Citizens,TAB"
    Write-Host ""
    Write-Host "Archive completed."
    Write-Host "Target: $targetRoot"
    Write-Host "Log: $logFile"
    Write-Host "Manifest: $manifestFile"
    exit 0
} catch {
    Write-ArchiveLog "Archive failed: $($_.Exception.Message)"
    Write-Manifest "status=FAILED"
    Write-Host ""
    Write-Host "Archive failed. Check log: $logFile"
    exit 1
}
