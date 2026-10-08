<#
.SYNOPSIS
    Collects launcher-icon diagnostics from the project folder into ICON_AUDIT_DUMP.txt.

.DESCRIPTION
    Read-only script. Collects:
      01 app AndroidManifest.xml (full text, parsed icon/roundIcon)
      02 inventory of every ic_launcher* file in the source tree (all modules, all variant source sets)
      03 contents of all ic_launcher*.xml files
      04 contents of drawables/mipmaps referenced by the adaptive icon
      05 full listing of app module res folders (main + debug/benchmark/etc.)
      06 icon-related lines from merged manifests and packaged artifacts under app\build
      07 "aapt2 dump badging" icon lines from built APKs (if SDK build-tools are found)
      08 git history/status for res folder and manifest (if git is available)
      09 automatic flags summary (wrong root/namespace, missing layers, duplicates, ?attr usage, ...)

    Output file: <ProjectRoot>\ICON_AUDIT_DUMP.txt  (UTF-8 with BOM)

.USAGE
    Copy this script to the project root and run:
        powershell -NoProfile -ExecutionPolicy Bypass -File .\make_icon_audit_dump.ps1
    Optional explicit root:
        powershell -NoProfile -ExecutionPolicy Bypass -File make_icon_audit_dump.ps1 -ProjectRoot "D:\Projects\MinuteWatcher"
    Then attach ICON_AUDIT_DUMP.txt for review.

.NOTES
    Compatible with Windows PowerShell 5.1 and PowerShell 7+. Requires no admin rights.
    Build folders are pruned during source scans for speed; app\build is inspected separately in section 06.
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = ""
)

$ErrorActionPreference = 'Continue'
$scriptVersion = '1.2.0'

# ------------------------------------------------------------------ setup ---
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    if ($PSScriptRoot) { $ProjectRoot = $PSScriptRoot } else { $ProjectRoot = (Get-Location).Path }
}
try { $ProjectRoot = (Resolve-Path $ProjectRoot).Path } catch {
    Write-Host ("ERROR: ProjectRoot not found: " + $ProjectRoot)
    exit 1
}
$ProjectRoot = $ProjectRoot.TrimEnd('\')
$outFile = Join-Path $ProjectRoot 'ICON_AUDIT_DUMP.txt'

$sb = New-Object System.Text.StringBuilder
function W { param([string]$t = '') [void]$sb.AppendLine($t) }
function Section { param([string]$title) W ''; W ('===== ' + $title + ' =====') }
function RelPath {
    param([string]$full)
    if ($full.StartsWith($ProjectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($ProjectRoot.Length).TrimStart('\')
    }
    return $full
}

$flags = New-Object System.Collections.ArrayList
function Flag { param([string]$level, [string]$text) [void]$flags.Add(($level + ': ' + $text)) }

# Recursive file search over the SOURCE tree with pruning of generated folders.
function Get-SourceFiles {
    param([string]$Root, [string]$Filter)
    $prune = @('build', '.git', '.gradle', '.idea', '.kotlin', 'node_modules', '.arena', '.cache')
    $stack = New-Object System.Collections.Stack
    $stack.Push($Root)
    while ($stack.Count -gt 0) {
        $dir = $stack.Pop()
        try {
            foreach ($d in [System.IO.Directory]::EnumerateDirectories($dir)) {
                $leaf = [System.IO.Path]::GetFileName($d)
                if ($prune -contains $leaf) { continue }
                $stack.Push($d)
            }
        } catch { }
        try {
            foreach ($f in [System.IO.Directory]::EnumerateFiles($dir, $Filter)) {
                Get-Item -LiteralPath $f
            }
        } catch { }
    }
}

# Defensive defaults (sections fill them in when data is available)
$manifestText = ''
$manifestIcon = ''
$manifestRoundIcon = ''
$iconFiles = @()
$xmlIcons = @()
$mainIconXml = $null
$mainIconText = ''
$refs = @()
$apks = @()
$aapt2 = $null

W 'ICON AUDIT DUMP'
W ('Generated: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
W ('Script version: ' + $scriptVersion)
W ('Project root: ' + $ProjectRoot)
W ('PowerShell: ' + $PSVersionTable.PSVersion.ToString() + ' | OS: ' + [Environment]::OSVersion.VersionString)

# ------------------------------------------------------- 01 app manifest ---
Section '01 APP MANIFEST (app/src/main/AndroidManifest.xml)'
$manifestPath = Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml'
try {
    if (Test-Path -LiteralPath $manifestPath) {
        $manifestText = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8
        W $manifestText
    } else {
        W '[ERROR] app manifest not found'
        Flag 'CRITICAL' 'app/src/main/AndroidManifest.xml not found'
    }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

if ($manifestText) {
    $mIcon = [regex]::Match($manifestText, 'android:icon\s*=\s*"([^"]*)"')
    if ($mIcon.Success) { $manifestIcon = $mIcon.Groups[1].Value }
    $mRound = [regex]::Match($manifestText, 'android:roundIcon\s*=\s*"([^"]*)"')
    if ($mRound.Success) { $manifestRoundIcon = $mRound.Groups[1].Value }
    W ''
    if ($manifestIcon) { W ('Parsed android:icon      = ' + $manifestIcon) } else { W 'Parsed android:icon      = <not set>'; Flag 'CRITICAL' 'android:icon is not set in app manifest' }
    if ($manifestRoundIcon) { W ('Parsed android:roundIcon = ' + $manifestRoundIcon) } else { W 'Parsed android:roundIcon = <not set>' }
}

# -------------------------------------- 02 ic_launcher* inventory (source) --
Section '02 ICON FILES INVENTORY (source tree, all modules and variant source sets)'
try {
    $iconFiles = @(Get-SourceFiles -Root $ProjectRoot -Filter 'ic_launcher*' | Sort-Object FullName)
    if ($iconFiles.Count -eq 0) {
        W '[NONE] no ic_launcher* files found in source tree'
        Flag 'CRITICAL' 'no ic_launcher* files found in the source tree'
    } else {
        W ('Count: ' + $iconFiles.Count)
        W ''
        W ('{0,-90} {1,10} {2:yyyy-MM-dd HH:mm}' -f 'PATH', 'SIZE', 'MODIFIED')
        foreach ($f in $iconFiles) {
            W ('{0,-90} {1,10} {2:yyyy-MM-dd HH:mm}' -f (RelPath $f.FullName), $f.Length, $f.LastWriteTime)
        }
    }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

$binIcons = @($iconFiles | Where-Object { $_.Extension -ne '.xml' -and $_.Extension -ne '.txt' })
if ($binIcons.Count -gt 0) {
    W ''
    W 'Binary icon files (raster) with MD5:'
    foreach ($f in $binIcons) {
        try {
            $h = (Get-FileHash -LiteralPath $f.FullName -Algorithm MD5).Hash
            W ('  ' + (RelPath $f.FullName) + '  ' + $f.Length + ' bytes  MD5=' + $h)
        } catch { W ('  ' + (RelPath $f.FullName) + '  [hash error: ' + $_.Exception.Message + ']') }
    }
    Flag 'WARNING' ('raster ic_launcher files present in source tree (' + $binIcons.Count + ') - AGENTS.md forbids rasterized launcher icons')
}

# ------------------------------------------------- 03 icon XML contents ----
Section '03 ICON XML CONTENTS'
$xmlIcons = @($iconFiles | Where-Object { $_.Extension -eq '.xml' })
if ($xmlIcons.Count -eq 0) { W '[NONE]' }
foreach ($f in $xmlIcons) {
    W ''
    W ('----- ' + (RelPath $f.FullName) + ' -----')
    try {
        $c = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($c)) {
            W '[EMPTY FILE]'
            Flag 'CRITICAL' ((RelPath $f.FullName) + ' is EMPTY')
        } else { W $c }
    } catch { W ('[ERROR] ' + $_.Exception.Message) }
}

# main adaptive icon = app module mipmap ic_launcher.xml
$mainIconXml = $xmlIcons | Where-Object { $_.FullName -match '\\app\\src\\main\\res\\mipmap[^\\]*\\ic_launcher\.xml$' } | Select-Object -First 1
if (-not $mainIconXml) {
    $mainIconXml = $xmlIcons | Where-Object { $_.Name -eq 'ic_launcher.xml' -and $_.FullName -match '\\app\\src\\main\\' } | Select-Object -First 1
}
if ($mainIconXml) {
    try { $mainIconText = Get-Content -LiteralPath $mainIconXml.FullName -Raw -Encoding UTF8 } catch { $mainIconText = '' }
}

# ------------------------------------ 04 drawables referenced by the icon --
Section '04 REFERENCED LAYER FILES (resources referenced from the adaptive icon XML)'
# $refs entries have the form "type/name" where type is drawable|mipmap|color
if ($mainIconText) {
    $refs = @([regex]::Matches($mainIconText, '@(drawable|mipmap|color)/([A-Za-z0-9_]+)') | ForEach-Object { $_.Groups[1].Value + '/' + $_.Groups[2].Value } | Select-Object -Unique)
    if ($refs.Count -eq 0) {
        W '[NONE] no @drawable/@mipmap/@color references found in the adaptive icon XML'
        Flag 'WARNING' 'adaptive icon XML references no layer resources'
    }
} else {
    W '[SKIP] adaptive icon XML not found or unreadable'
}

# collect values*.xml files once (needed for @color references)
$valuesXmlFiles = @()
try {
    $valuesXmlFiles = @(Get-SourceFiles -Root $ProjectRoot -Filter '*.xml' | Where-Object { $_.FullName -match '\\res\\values[^\\]*\\' })
} catch { }

foreach ($ref in $refs) {
    $parts = $ref.Split('/')
    $refType = $parts[0]
    $name = $parts[1]
    W ''
    W ('----- reference: @' + $refType + '/' + $name + ' -----')
    try {
        if ($refType -eq 'color') {
            $pattern = 'name\s*=\s*"' + [regex]::Escape($name) + '"'
            $colorHits = @()
            foreach ($vf in $valuesXmlFiles) {
                $hits = @(Select-String -LiteralPath $vf.FullName -Pattern $pattern -ErrorAction SilentlyContinue)
                foreach ($h in $hits) { $colorHits += ('  ' + (RelPath $vf.FullName) + ' L' + $h.LineNumber + ': ' + $h.Line.Trim()) }
            }
            if ($colorHits.Count -eq 0) {
                W '[NOT FOUND in any values/*.xml]'
                Flag 'CRITICAL' ('color resource referenced by launcher icon not found in source tree: ' + $name)
            } else { foreach ($ch in $colorHits) { W $ch } }
        } else {
            $found = @(Get-SourceFiles -Root $ProjectRoot -Filter ($name + '.*') | Where-Object { $_.FullName -match '\\res\\' } | Sort-Object FullName)
            if ($found.Count -eq 0) {
                W '[NOT FOUND in source res folders]'
                Flag 'CRITICAL' ('resource referenced by launcher icon not found in source tree: ' + $name)
            }
            foreach ($f in $found) {
                W ('--- ' + (RelPath $f.FullName) + ' (' + $f.Length + ' bytes) ---')
                if ($f.Extension -eq '.xml') {
                    try {
                        $c = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
                        W $c
                    } catch { W ('[ERROR] ' + $_.Exception.Message) }
                } else {
                    try { W ('[binary] MD5=' + (Get-FileHash -LiteralPath $f.FullName -Algorithm MD5).Hash) } catch { W '[binary]' }
                }
            }
        }
    } catch { W ('[ERROR] ' + $_.Exception.Message) }
}

# ------------------------------------------------- 05 app res listings -----
Section '05 APP MODULE RES FOLDERS FULL LISTING (all variant source sets)'
try {
    $appSrc = Join-Path $ProjectRoot 'app\src'
    if (Test-Path -LiteralPath $appSrc) {
        $resDirs = @(Get-ChildItem -LiteralPath $appSrc -Recurse -Directory -Filter 'res' -ErrorAction SilentlyContinue)
        if ($resDirs.Count -eq 0) {
            W '[NONE] no res folders under app\src'
            Flag 'WARNING' 'app module has no res folders'
        }
        foreach ($d in $resDirs) {
            W ''
            W ('--- ' + (RelPath $d.FullName) + ' ---')
            Get-ChildItem -LiteralPath $d.FullName -Recurse -File -ErrorAction SilentlyContinue | Sort-Object FullName | ForEach-Object {
                W ('  {0,-88} {1,9} {2:yyyy-MM-dd HH:mm}' -f (RelPath $_.FullName), $_.Length, $_.LastWriteTime)
            }
        }
    } else { W '[ERROR] app\src not found' }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

# --------------------------------------- 06 build intermediates artifacts --
Section '06 BUILD ARTIFACTS (app\build): APKs, merged manifests, packaged icon resources'
try {
    $appBuild = Join-Path $ProjectRoot 'app\build'
    if (Test-Path -LiteralPath $appBuild) {
        W '--- APK files under app\build ---'
        $apks = @(Get-ChildItem -LiteralPath $appBuild -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
        if ($apks.Count -eq 0) { W '[NONE] no APK found (run assembleDebug to enable badging diagnostics)' }
        foreach ($a in $apks) { W ('  ' + (RelPath $a.FullName) + '  ' + $a.Length + ' bytes  ' + $a.LastWriteTime.ToString('yyyy-MM-dd HH:mm')) }

        W ''
        W '--- merged manifests: icon-related lines (first 30 manifests) ---'
        $merged = @(Get-ChildItem -LiteralPath $appBuild -Recurse -File -Filter 'AndroidManifest.xml' -ErrorAction SilentlyContinue | Select-Object -First 30)
        if ($merged.Count -eq 0) { W '[NONE]' }
        foreach ($mf in $merged) {
            $hits = @(Select-String -LiteralPath $mf.FullName -Pattern 'android:icon|roundIcon|ic_launcher' -ErrorAction SilentlyContinue)
            if ($hits.Count -gt 0) {
                W ('  [' + (RelPath $mf.FullName) + ']')
                foreach ($h in $hits) { W ('    L' + $h.LineNumber + ': ' + $h.Line.Trim()) }
            }
        }

        W ''
        W '--- ic_launcher* files under app\build (merged/packaged resources, first 300) ---'
        $built = @(Get-ChildItem -LiteralPath $appBuild -Recurse -File -Filter 'ic_launcher*' -ErrorAction SilentlyContinue | Select-Object -First 300)
        if ($built.Count -eq 0) { W '[NONE]' }
        foreach ($bf in $built) {
            W ('  ' + (RelPath $bf.FullName) + '  ' + $bf.Length + ' bytes  ' + $bf.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))
            if ($bf.Extension -eq '.xml' -and $bf.Length -lt 4096) {
                try {
                    W '  --- content ---'
                    foreach ($ln in (Get-Content -LiteralPath $bf.FullName -Encoding UTF8)) { W ('  | ' + $ln) }
                } catch { W ('  [content error] ' + $_.Exception.Message) }
            }
        }

        W ''
        W '--- resource merger attribution: ic_launcher lines in merger.xml (intermediates\incremental) ---'
        $inc = Join-Path $appBuild 'intermediates\incremental'
        if (Test-Path -LiteralPath $inc) {
            $mergers = @(Get-ChildItem -LiteralPath $inc -Recurse -File -Filter 'merger.xml' -ErrorAction SilentlyContinue | Select-Object -First 20)
            if ($mergers.Count -eq 0) { W '[NONE] no merger.xml found' }
            foreach ($mg in $mergers) {
                $hits = @(Select-String -LiteralPath $mg.FullName -Pattern 'ic_launcher' -ErrorAction SilentlyContinue | Select-Object -First 40)
                if ($hits.Count -gt 0) {
                    W ('  [' + (RelPath $mg.FullName) + ']')
                    foreach ($h in $hits) { W ('    L' + $h.LineNumber + ': ' + $h.Line.Trim()) }
                }
            }
        } else { W '[NONE] no incremental folder' }
    } else { W '[NONE] app\build not present (project was not built here)' }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

# ------------------------------------------------------- 07 aapt2 badging --
Section '07 AAPT2 BADGING (icon lines from packaged APKs)'
try {
    $sdkRoots = New-Object System.Collections.ArrayList
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $v = [Environment]::GetEnvironmentVariable($envName)
        if ($v) { [void]$sdkRoots.Add($v) }
    }
    $lp = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $lp) {
        $m = Select-String -LiteralPath $lp -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($m) {
            $p = $m.Matches[0].Groups[1].Value.Trim()
            $p = $p.Replace('\\', '\').Replace('\:', ':')
            [void]$sdkRoots.Add($p)
        }
    }
    if ($env:LOCALAPPDATA) { [void]$sdkRoots.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk')) }
    foreach ($root in ($sdkRoots | Select-Object -Unique)) {
        if (-not $root) { continue }
        $bt = Join-Path $root 'build-tools'
        if (Test-Path -LiteralPath $bt) {
            $cand = Get-ChildItem -LiteralPath $bt -Recurse -File -Filter 'aapt2.exe' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($cand) { $aapt2 = $cand.FullName; break }
        }
    }
} catch { W ('[ERROR locating aapt2] ' + $_.Exception.Message) }

if (-not $aapt2) {
    W '[SKIP] aapt2.exe not found (set ANDROID_HOME or sdk.dir in local.properties)'
} else {
    W ('aapt2: ' + $aapt2)
    if ($apks.Count -eq 0) {
        W '[SKIP] no APK to inspect'
    } else {
        foreach ($a in ($apks | Select-Object -First 3)) {
            W ''
            W ('--- ' + (RelPath $a.FullName) + ' ---')
            try {
                $badging = (& $aapt2 dump badging $a.FullName 2>&1 | Out-String)
                foreach ($line in ($badging -split "`r?`n")) {
                    if ($line -match 'package:|application|icon|launchable') { W ('  ' + $line.Trim()) }
                }
            } catch { W ('[ERROR] ' + $_.Exception.Message) }
        }
    }
}

# --------------------------------------------------------------- 08 git ----
Section '08 GIT (history and status of res folder and manifest, if available)'
try {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git -and (Test-Path -LiteralPath (Join-Path $ProjectRoot '.git'))) {
        W '--- last 10 commits touching app/src/main/res ---'
        W ((& git -C $ProjectRoot log -10 --date=short --pretty='format:%h %ad %s' -- app/src/main/res 2>&1) | Out-String)
        W '--- last 5 commits touching app manifest ---'
        W ((& git -C $ProjectRoot log -5 --date=short --pretty='format:%h %ad %s' -- app/src/main/AndroidManifest.xml 2>&1) | Out-String)
        W '--- working tree status (res + manifest) ---'
        $st = ((& git -C $ProjectRoot status --porcelain -- app/src/main/res app/src/main/AndroidManifest.xml 2>&1) | Out-String)
        if ([string]::IsNullOrWhiteSpace($st)) { W '(clean)' } else { W $st }
    } else { W '[SKIP] git not available or not a git repository' }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

# ------------------------------------------- 10 adb device state (optional) --
Section '10 ADB DEVICE STATE (optional: installed build vs fresh APK, launcher-visible icon)'
try {
    $adbExe = $null
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $adbExe = $adbCmd.Source }
    if (-not $adbExe) {
        foreach ($root in ($sdkRoots | Select-Object -Unique)) {
            if (-not $root) { continue }
            $c = Join-Path $root 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $c) { $adbExe = $c; break }
        }
    }
    if (-not $adbExe) {
        W '[SKIP] adb not found'
    } else {
        W ('adb: ' + $adbExe)
        $devices = (& $adbExe devices 2>&1 | Out-String)
        W $devices.Trim()
        $hasDevice = ($devices -split "`r?`n" | Where-Object { $_ -match "`tdevice$" }).Count -gt 0
        if ($hasDevice) {
            W ''
            W '--- device ---'
            W ('model: ' + ((& $adbExe shell getprop ro.product.model 2>&1) | Out-String).Trim())
            W ('sdk:   ' + ((& $adbExe shell getprop ro.build.version.sdk 2>&1) | Out-String).Trim())
            W ''
            W '--- installed com.example.minutewatcher (versionCode / lastUpdateTime / icon) ---'
            $pkg = (& $adbExe shell dumpsys package com.example.minutewatcher 2>&1 | Out-String)
            $pkgLines = $pkg -split "`r?`n" | Where-Object { $_ -match 'versionCode|versionName|lastUpdateTime|firstInstallTime|icon=' } | Select-Object -First 12
            if ($pkgLines) { foreach ($pl in $pkgLines) { W ('  ' + $pl.Trim()) } } else { W '  [package not installed]' }
        } else {
            W '[SKIP] no device attached'
        }
    }
} catch { W ('[ERROR] ' + $_.Exception.Message) }

# ----------------------------------------------------------- 09 flags ------
Section '09 AUTOMATIC FLAGS'
try {
    if ($mainIconText) {
        if ($mainIconText -notmatch '<adaptive-icon') { Flag 'CRITICAL' 'ic_launcher.xml root element is not <adaptive-icon>' }
        if ($mainIconText -notmatch 'schemas\.android\.com/apk/res/android') { Flag 'CRITICAL' 'ic_launcher.xml does not declare namespace http://schemas.android.com/apk/res/android' }
        if ($mainIconText -notmatch '<background') { Flag 'WARNING' 'ic_launcher.xml has no <background> element (required by AGENTS.md)' }
        if ($mainIconText -notmatch '<foreground') { Flag 'WARNING' 'ic_launcher.xml has no <foreground> element (required by AGENTS.md)' }
        if ($mainIconText -notmatch '<monochrome') { Flag 'INFO' 'ic_launcher.xml has no <monochrome> element (required by AGENTS.md; ignored by systems below API 33)' }
    } else {
        Flag 'CRITICAL' 'adaptive icon XML (app/src/main/res/mipmap*/ic_launcher.xml) not found or unreadable'
    }

    # theme attribute references in icon-related xml files (break inflation in launcher context)
    $attrFiles = New-Object System.Collections.ArrayList
    foreach ($f in $xmlIcons) {
        try {
            $c = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
            if ($c -match '\?attr/|\?android:attr/') { [void]$attrFiles.Add((RelPath $f.FullName)) }
        } catch { }
    }
    foreach ($ref in $refs) {
        $parts = $ref.Split('/')
        if ($parts[0] -eq 'color') { continue }
        $name = $parts[1]
        try {
            $layerFiles = @(Get-SourceFiles -Root $ProjectRoot -Filter ($name + '.xml') | Where-Object { $_.FullName -match '\\res\\' })
            foreach ($lf in $layerFiles) {
                $c = Get-Content -LiteralPath $lf.FullName -Raw -Encoding UTF8
                if ($c -match '\?attr/|\?android:attr/') { [void]$attrFiles.Add((RelPath $lf.FullName)) }
            }
        } catch { }
    }
    $attrList = @($attrFiles | Select-Object -Unique)
    if ($attrList.Count -gt 0) {
        Flag 'CRITICAL' ('theme attribute references (?attr) in launcher icon files break inflation in launcher context: ' + ($attrList -join ', '))
    }

    # duplicates / unexpected variants
    $expectedNames = @('ic_launcher.xml', 'ic_launcher_background.xml', 'ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml')
    $unexpected = @($iconFiles | Where-Object { $expectedNames -notcontains $_.Name })
    if ($unexpected.Count -gt 0) {
        Flag 'WARNING' ('unexpected ic_launcher* file variants in source tree: ' + (($unexpected | ForEach-Object { RelPath $_.FullName }) -join ', '))
    }
    $roundFiles = @($iconFiles | Where-Object { $_.Name -like 'ic_launcher_round*' })
    if ($roundFiles.Count -gt 0 -and $manifestRoundIcon -notmatch 'ic_launcher_round') {
        Flag 'WARNING' 'ic_launcher_round* files exist but manifest roundIcon does not reference them (AGENTS.md forbids ic_launcher_round without matching manifest reference)'
    }

    # manifest values vs AGENTS.md canonical layout
    if ($manifestIcon -and $manifestIcon -ne '@mipmap/ic_launcher') { Flag 'WARNING' ('android:icon is "' + $manifestIcon + '", canonical value is "@mipmap/ic_launcher"') }
    if ($manifestRoundIcon -and $manifestRoundIcon -ne '@mipmap/ic_launcher') { Flag 'WARNING' ('android:roundIcon is "' + $manifestRoundIcon + '", canonical value is "@mipmap/ic_launcher"') }

    # variant source sets overriding the icon (debug/benchmark res)
    $variantIcons = @($iconFiles | Where-Object { $_.FullName -match '\\app\\src\\(?!main\\)[^\\]+\\res\\' })
    if ($variantIcons.Count -gt 0) {
        Flag 'INFO' ('icon files present in non-main app source sets (variant override): ' + (($variantIcons | ForEach-Object { RelPath $_.FullName }) -join ', '))
    }

    if ($apks.Count -eq 0) { Flag 'INFO' 'no APK found under app\build - build the app to enable aapt2 badging diagnostics' }
    if (-not $aapt2) { Flag 'INFO' 'aapt2.exe not located - point ANDROID_HOME or local.properties sdk.dir to the SDK for badging diagnostics' }

    if ($flags.Count -eq 0) { W 'No automatic flags raised.' }
    foreach ($fl in $flags) {
        $idx = $fl.IndexOf(':')
        if ($idx -gt 0) { W ('[' + $fl.Substring(0, $idx) + '] ' + $fl.Substring($idx + 2)) } else { W $fl }
    }
} catch { W ('[ERROR building flags] ' + $_.Exception.Message) }

# ------------------------------------------------------------- save file ---
try {
    [System.IO.File]::WriteAllText($outFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true)))
    Write-Host ''
    Write-Host ('Saved: ' + $outFile)
    Write-Host ('Flags raised: ' + $flags.Count)
    foreach ($fl in $flags) { Write-Host ('  ' + $fl) }
} catch {
    Write-Host ('ERROR saving output: ' + $_.Exception.Message)
    exit 1
}
