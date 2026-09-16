<#
.SYNOPSIS
Создаёт единый дамп проекта для AI-ассистента с исправленным измерительным контуром.
.DESCRIPTION
Гарантии:
1. Используются ТОЛЬКО файлы под версионным контролем (git ls-files).
2. Исключаются: .artifacts/, .gradle_home/, .gradle/, build/, .idea/,
   .kotlin/, .cxx/, intermediates/, generated/, captures/, screenshots/,
   reports/, audit_dump/, .git/, node_modules/.
3. Исключаются файлы: local.properties, benchmark-config.txt,
   detekt_output.txt, diag_result.txt, deps_ksp.txt, ktlint_output.txt,
   verify_output.txt, verify_status.txt, *.log, *.apk, *.aab, *.jks.
4. Рекурсивно парсятся XML отчёты: ktlint (Checkstyle), detekt, lint-results-debug.xml.
5. Проверяется свежесть (staleness) отчётов относительно исходников и последнего коммита.
6. Всё собирается в ОДИН файл ProjectDump_Combined.txt.
7. Корректная UTF-8 кодировка без BOM при чтении и записи.
.NOTES
Запуск: .\make_audit_dump.ps1 [-IncludeTests] [-Blind]
#>
param(
    [switch]$IncludeTests,
    [switch]$Blind
)

$ErrorActionPreference = "Continue"

# ============================================================================
# Constants & Configuration
# ============================================================================

$RootFull = (Get-Location).Path.TrimEnd('\', '/')

# Глобальная установка UTF-8 для всех cmdlet'ов PowerShell
$PSDefaultParameterValues['*:Encoding'] = 'utf8'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Флаги наличия отчётов
$script:ReportsFound = @{
    Tests  = $false
    Detekt = $false
    Lint   = $false
    Ktlint = $false
}

# Директории, которые ВСЕГДА исключаются
$SkipSegments = @(
    '.artifacts', '.gradle_home', '.gradle', 'build', '.idea', '.kotlin',
    '.cxx', 'intermediates', 'generated', 'tmp', 'captures', 'screenshots',
    'reports', 'audit_dump', '.git', 'node_modules'
)

# Файлы, которые ВСЕГДА исключаются (по имени)
$SkipFileNames = @(
    'local.properties', 'benchmark-config.txt', 'detekt_output.txt',
    'diag_result.txt', 'deps_ksp.txt', 'ktlint_output.txt',
    'verify_output.txt', 'verify_status.txt',
    'make_audit_dump.ps1', 'MAKE_AUDIT_DUMP.bat', 'verify.ps1', 'VERIFY.bat'
)

$BinaryExtensions = @(
    '.png', '.webp', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.svg',
    '.jar', '.aar', '.so', '.bin', '.class', '.pdf', '.ttf', '.otf',
    '.woff', '.woff2', '.zip', '.7z', '.rar', '.gz', '.tar',
    '.mp3', '.mp4', '.wav', '.exe', '.dll', '.o', '.a',
    '.apk', '.aab', '.jks', '.keystore', '.hprof'
)

$FullDumpExtensions = @(
    '.kt', '.kts', '.java', '.xml', '.gradle', '.toml', '.yml', '.yaml',
    '.md', '.pro', '.json', '.properties', '.cfg', '.ini',
    '.gitignore', '.editorconfig', '.txt'
)

# ============================================================================
# Helper Functions
# ============================================================================

function Save-Text {
    param([string]$Path, [string]$Text)
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Save-Csv {
    param([string]$Path, [array]$Rows, [string[]]$Headers)
    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        $headerLine = if ($Headers) { ($Headers -join ',') } else { "" }
        Save-Text $Path $headerLine
        return
    }
    $csv = $Rows | ConvertTo-Csv -NoTypeInformation
    Save-Text $Path ($csv -join "`r`n")
}

function Get-Rel {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return "" }
    if ($Path.StartsWith($RootFull, [StringComparison]::OrdinalIgnoreCase)) {
        return $Path.Substring($RootFull.Length).TrimStart('\', '/')
    }
    return $Path
}

function Format-ReportCount {
    param([string]$Key, [int]$Count)
    if ($script:ReportsFound[$Key]) { return "$Count" }
    return 'N/A (report not found - run .\VERIFY.bat before dumping)'
}

function Test-IsTestRelPath {
    param([string]$RelPath)
    $rel = $RelPath.Replace('\', '/')
    return ($rel -match '(^|/)src/(test|androidTest|testFixtures)/')
}

function Test-SkipSourceFile {
    param([System.IO.FileInfo]$File)
    $relForSegments = Get-Rel $File.FullName
    $segments = $relForSegments.Split([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    foreach ($segment in $segments) {
        if ($SkipSegments -contains $segment) { return $true }
    }
    if ($SkipFileNames -contains $File.Name) { return $true }
    if ($BinaryExtensions -contains $File.Extension.ToLower()) { return $true }
    if ($File.Name -like '*.log') { return $true }
    return $false
}

function Test-IncludeForFullDump {
    param([System.IO.FileInfo]$File)
    $ext = $File.Extension.ToLower()
    $rel = Get-Rel $File.FullName
    $relSlash = $rel.Replace('\', '/')
    if ($FullDumpExtensions -notcontains $ext) { return $false }
    if ($File.Length -gt 2MB) { return $false }
    if ($File.Name -like '*.svg') { return $false }
    if ($relSlash -match '(^|/)(drawable|mipmap)') { return $false }
    if ($ext -eq '.xml' -and $relSlash -match '/res/') {
        if ($relSlash -notmatch '/res/(values|values-[^/]+|layout|layout-[^/]+|navigation|menu|xml)/') { return $false }
    }
    if ($relSlash -match '/docs/' -and $File.Length -gt 200KB) { return $false }
    if (-not $IncludeTests) {
        if (Test-IsTestRelPath $rel) { return $false }
    }
    return $true
}

function Add-FileDump {
    param(
        [System.Text.StringBuilder]$Sb,
        [System.IO.FileInfo]$File,
        [switch]$ForceFull,
        [string]$DisplayName = ''
    )
    $rel = if ($DisplayName) { $DisplayName } else { Get-Rel $File.FullName }
    $lines = @()
    try {
        $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8, $true)
        try {
            $lineList = New-Object System.Collections.Generic.List[string]
            while ($null -ne ($line = $reader.ReadLine())) { $lineList.Add($line) }
            $lines = $lineList.ToArray()
        } finally { $reader.Close() }
    } catch {
        $lines = @("[ERROR: could not read file: $($_.Exception.Message)]")
    }
    $full = if ($ForceFull) { $true } else { Test-IncludeForFullDump $File }
    if ($lines.Count -gt 6000) { $full = $false }
    [void]$Sb.AppendLine(('=' * 100))
    [void]$Sb.AppendLine("FILE: $rel")
    [void]$Sb.AppendLine("Size: $($File.Length) bytes")
    [void]$Sb.AppendLine("Lines: $($lines.Count)")
    [void]$Sb.AppendLine("Mode: $(if ($full) { 'FULL' } else { 'HEAD-ONLY' })")
    [void]$Sb.AppendLine(('=' * 100))
    $limit = if ($full) { $lines.Count } else { [Math]::Min(200, $lines.Count) }
    for ($i = 0; $i -lt $limit; $i++) { [void]$Sb.AppendLine(("{0,6}: {1}" -f ($i + 1), $lines[$i])) }
    if (-not $full -and $lines.Count -gt 200) {
        [void]$Sb.AppendLine('')
        [void]$Sb.AppendLine("- LAST 80 LINES -")
        $start = [Math]::Max(0, $lines.Count - 80)
        for ($i = $start; $i -lt $lines.Count; $i++) { [void]$Sb.AppendLine(("{0,6}: {1}" -f ($i + 1), $lines[$i])) }
    }
    [void]$Sb.AppendLine("")
}

function Append-FileSection {
    param([System.Text.StringBuilder]$Sb, [string]$Path, [string]$Title)
    [void]$Sb.AppendLine('')
    [void]$Sb.AppendLine("===== $Title =====")
    if (Test-Path -LiteralPath $Path) {
        $content = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($null -ne $content) { [void]$Sb.AppendLine($content) }
    } else {
        [void]$Sb.AppendLine('(file not found)')
    }
}

# ============================================================================
# Freshness Management
# ============================================================================

$script:ProcessedReports = @()

function Get-StaleWarning {
    param([DateTime]$ReportTime)
    $lastCommitEpoch = git log -1 --format=%ct 2>&1
    if ($lastCommitEpoch -match '^\d+$') {
        $lastCommitTime = [DateTimeOffset]::FromUnixTimeSeconds($lastCommitEpoch).LocalDateTime
        if ($ReportTime -lt $lastCommitTime) {
            return " [STALE: report is older than last commit ($($lastCommitTime.ToString('HH:mm:ss')))]"
        }
    }
    return ""
}

# ============================================================================
# Report Parsing Functions
# ============================================================================

function Parse-TestResults {
    param([string]$OutDir)
    $csvPath = Join-Path $OutDir '40_tests_summary.csv'
    $failPath = Join-Path $OutDir '41_failing_tests.txt'
    $rows = @()
    $failSb = New-Object System.Text.StringBuilder
    $testDirs = Get-ChildItem -Path $RootFull -Recurse -Directory -Filter 'test-results' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\\.artifacts\\' -and $_.FullName -notmatch '\\\.gradle' }
    if (@($testDirs).Count -gt 0) { $script:ReportsFound['Tests'] = $true }
    foreach ($dir in $testDirs) {
        $variant = "unknown"
        if ($dir.FullName -match 'test(\w+)UnitTest') { $variant = $matches[1] }
        $xmlFiles = Get-ChildItem -Path $dir.FullName -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
        foreach ($xmlFile in $xmlFiles) {
            try {
                $content = Get-Content $xmlFile.FullName -Raw -Encoding UTF8
                if ([string]::IsNullOrWhiteSpace($content)) { continue }
                [xml]$xml = $content
                $script:ProcessedReports += @{ File = Get-Rel $xmlFile.FullName; Time = $xmlFile.LastWriteTime }

                $ts = $xml.testsuite
                $rows += [PSCustomObject]@{
                    Variant   = $variant
                    TestSuite = $ts.name
                    Tests     = $ts.tests
                    Failures  = $ts.failures
                    Errors    = $ts.errors
                    Time      = $ts.time
                    File      = Get-Rel $xmlFile.FullName
                }

                if ($ts.testcase) {
                    foreach ($tc in $ts.testcase) {
                        if ($tc.failure) {
                            [void]$failSb.AppendLine("FAIL [$variant]: $($tc.classname).$($tc.name)")
                            [void]$failSb.AppendLine("  Message: $($tc.failure.message)")
                            [void]$failSb.AppendLine('')
                        }
                        if ($tc.error) {
                            [void]$failSb.AppendLine("ERROR [$variant]: $($tc.classname).$($tc.name)")
                            [void]$failSb.AppendLine("  Message: $($tc.error.message)")
                            [void]$failSb.AppendLine('')
                        }
                    }
                }
            } catch { }
        }
    }
    Save-Csv $csvPath $rows @('Variant', 'TestSuite', 'Tests', 'Failures', 'Errors', 'Time', 'File')
    Save-Text $failPath $failSb.ToString()
    return ,$rows
}

function Parse-CheckstyleXml {
    param([string[]]$FilePaths, [string]$SourceType)
    $rows = @()
    foreach ($path in $FilePaths) {
        if (-not (Test-Path $path)) { continue }
        try {
            $fileInfo = Get-Item $path
            $content = Get-Content $path -Raw -Encoding UTF8
            if ([string]::IsNullOrWhiteSpace($content)) { continue }
            [xml]$xml = $content
            $script:ProcessedReports += @{ File = Get-Rel $path; Time = $fileInfo.LastWriteTime }

            if ($xml.checkstyle -and $xml.checkstyle.file) {
                foreach ($fileNode in $xml.checkstyle.file) {
                    $fileName = $fileNode.name
                    if ($fileNode.error) {
                        foreach ($error in $fileNode.error) {
                            $rows += [PSCustomObject]@{
                                File    = Get-Rel $fileName
                                Line    = $error.line
                                Column  = $error.column
                                Message = $error.message
                                Severity = $error.severity
                                Source  = $SourceType
                            }
                        }
                    }
                }
            }
        } catch { }
    }
    # De-duplicate rows across variants
    if ($rows.Count -gt 0) {
        $rows = $rows | Sort-Object File, Line, Column, Message -Unique
    }
    return ,$rows
}

function Parse-DetektReport {
    param([string]$OutDir)
    $detektCsv = Join-Path $OutDir '42_detekt_summary.csv'
    # Use *.xml for detekt, but exclude baseline
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter '*.xml' |
        Where-Object { $_.FullName -match '\\build\\reports\\detekt\\' -and $_.Name -notmatch 'baseline' }
    if (-not $xmlFiles) {
        Save-Text $detektCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return @()
    }
    $script:ReportsFound['Detekt'] = $true
    $rows = Parse-CheckstyleXml (@($xmlFiles.FullName)) 'detekt'
    Save-Csv $detektCsv $rows @('File', 'Line', 'Column', 'Message', 'Severity', 'Source')
    return ,$rows
}

function Parse-KtlintReport {
    param([string]$OutDir)
    $ktlintCsv = Join-Path $OutDir '44_ktlint_summary.csv'
    # Use *.xml for ktlint, but ONLY *Check.xml files
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter '*Check.xml' |
        Where-Object { $_.FullName -match '\\build\\reports\\ktlint\\' }
    if (-not $xmlFiles) {
        Save-Text $ktlintCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return @()
    }
    $script:ReportsFound['Ktlint'] = $true
    $rows = Parse-CheckstyleXml (@($xmlFiles.FullName)) 'ktlint'
    Save-Csv $ktlintCsv $rows @('File', 'Line', 'Column', 'Message', 'Severity', 'Source')
    return ,$rows
}

function Parse-LintReport {
    param([string]$OutDir)
    $lintCsv = Join-Path $OutDir '43_lint_summary.csv'
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter 'lint-results-debug.xml' | Where-Object { $_.FullName -match '\\build\\reports\\' }
    if (-not $xmlFiles) {
        Save-Text $lintCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return @()
    }
    $script:ReportsFound['Lint'] = $true
    $rows = @()
    foreach ($path in $xmlFiles.FullName) {
        try {
            $fileInfo = Get-Item $path
            $content = Get-Content $path -Raw -Encoding UTF8
            if ([string]::IsNullOrWhiteSpace($content)) { continue }
            [xml]$xml = $content
            $script:ProcessedReports += @{ File = Get-Rel $path; Time = $fileInfo.LastWriteTime }

            if ($xml.issues -and $xml.issues.issue) {
                foreach ($issue in $xml.issues.issue) {
                    $fileLoc = ""
                    $lineLoc = ""
                    if ($issue.location) {
                        $fileLoc = Get-Rel $issue.location.file
                        $lineLoc = $issue.location.line
                    }
                    $rows += [PSCustomObject]@{
                        Severity = $issue.severity
                        ID       = $issue.id
                        Message  = $issue.message
                        File     = $fileLoc
                        Line     = $lineLoc
                        Source   = 'lint'
                    }
                }
            }
        } catch { }
    }
    if ($rows.Count -gt 0) {
        $rows = $rows | Sort-Object Severity, ID, Message, File, Line -Unique
    }
    Save-Csv $lintCsv $rows @('Severity', 'ID', 'Message', 'File', 'Line', 'Source')
    return ,$rows
}

function Analyze-MissingTranslations {
    param([string]$OutDir, [array]$allFiles)
    $missingCsv = Join-Path $OutDir '50_missing_translations.csv'
    $missingRows = @()
    $resFiles = @($allFiles | Where-Object { $_.FullName -match '\\src\\main\\res\\values' -and $_.Extension -eq '.xml' })
    $defaultResources = @{}; $nonTranslatable = @{}; $localeResources = @{}
    foreach ($file in $resFiles) {
        try {
            $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            if ([string]::IsNullOrWhiteSpace($content)) { continue }
            [xml]$xml = $content
        } catch { continue }
        if ($null -eq $xml.resources) { continue }
        $dirName = Split-Path -Parent $file.FullName | Split-Path -Leaf
        $allNames = @()
        if ($xml.resources.ChildNodes) {
            foreach ($node in $xml.resources.ChildNodes) {
                if ($node -isnot [System.Xml.XmlElement]) { continue }
                $attrName = $node.GetAttribute('name')
                if ([string]::IsNullOrEmpty($attrName)) { continue }
                switch ($node.localName) {
                    'string' { $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'string' } }
                    'plurals' { $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'plurals' } }
                    'string-array' { $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'array' } }
                }
                if ($dirName -eq 'values' -and $node.GetAttribute('translatable') -eq 'false') { $nonTranslatable[$attrName] = $true }
            }
        }
        if ($dirName -eq 'values') { foreach ($item in $allNames) { $defaultResources[$item.Name] = $item.Type } }
        elseif ($dirName -match '^values-[a-z]{2,3}(-r[A-Z]{2,3})?$') {
            if (-not $localeResources.ContainsKey($dirName)) { $localeResources[$dirName] = @{} }
            foreach ($item in $allNames) { $localeResources[$dirName][$item.Name] = $item.Type }
        }
    }
    foreach ($localeDir in $localeResources.Keys) {
        $present = $localeResources[$localeDir]
        foreach ($key in $defaultResources.Keys) {
            if ($nonTranslatable.ContainsKey($key) -or $present.ContainsKey($key)) { continue }
            $missingRows += [PSCustomObject]@{ Locale = $localeDir; MissingKey = $key; Type = $defaultResources[$key] }
        }
    }
    Save-Csv $missingCsv $missingRows @('Locale', 'MissingKey', 'Type')
    return ,$missingRows
}

function Find-SuspiciousMatches {
    param([string]$OutDir, [array]$allFiles)
    $hintsPath = Join-Path $OutDir '61_suspicious_matches.txt'
    $patterns = @(
        @{ Name = 'TODO'; Regex = '//\s*TODO' }, @{ Name = 'FIXME'; Regex = '//\s*FIXME' },
        @{ Name = 'HACK'; Regex = '//\s*HACK' }, @{ Name = 'DEPRECATED'; Regex = '@Deprecated|@Suppress\("DEPRECATION"\)' },
        @{ Name = 'SUPPRESS';   Regex = '@Suppress\(' }, @{ Name = 'MAGIC_NUMBER'; Regex = '\b\d{2,}\b' }
    )
    $hintsSb = New-Object System.Text.StringBuilder
    $textFiles = $allFiles | Where-Object { $FullDumpExtensions -contains $_.Extension.ToLower() -and ($_.Extension -notmatch '\.(json|md|csv|toml|properties|cfg|ini|txt)$') }
    $totalMatches = 0
    foreach ($file in $textFiles) {
        if ($file.Length -gt 512KB) { continue }
        $lines = try { [System.IO.File]::ReadAllLines($file.FullName, [System.Text.Encoding]::UTF8) } catch { continue }
        if ($null -eq $lines) { continue }
        $patternCounts = @{}
        foreach ($lineText in $lines) {
            foreach ($p in $patterns) {
                if ($lineText -match $p.Regex) {
                    if ($p.Name -eq 'MAGIC_NUMBER' -and ($lineText -match '["' + "'" + ']|//|\*')) { continue }
                    $key = $p.Name; if (-not $patternCounts.ContainsKey($key)) { $patternCounts[$key] = 0 }
                    $patternCounts[$key]++; $totalMatches++
                }
            }
        }
        if ($patternCounts.Count -gt 0) {
            [void]$hintsSb.AppendLine("File: $(Get-Rel $file.FullName)")
            foreach ($key in $patternCounts.Keys) { [void]$hintsSb.AppendLine("  $key : $($patternCounts[$key])") }
            [void]$hintsSb.AppendLine('')
        }
    }
    Save-Text $hintsPath $hintsSb.ToString()
    return $totalMatches
}

# ============================================================================
# Main Execution
# ============================================================================

Write-Host "Creating audit dump..." -ForegroundColor Cyan
if (-not (Test-Path ".git")) { Write-Error "Must run from git root."; exit 1 }

$OutDir = Join-Path $RootFull 'audit_dump'
if (Test-Path -LiteralPath $OutDir) { try { Remove-Item -LiteralPath $OutDir -Recurse -Force } catch { $OutDir = Join-Path $RootFull ("audit_dump_" + (Get-Date -Format 'yyyyMMdd_HH-mm-ss')) } }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$OutDirFull = (Resolve-Path -LiteralPath $OutDir).Path.TrimEnd('\')

Write-Host "Collecting files..." -ForegroundColor Yellow
$gitFiles = git ls-files 2>&1 | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
$allFiles = @()
foreach ($rel in $gitFiles) {
    $full = Join-Path $RootFull $rel
    if (Test-Path -LiteralPath $full) { $f = Get-Item -LiteralPath $full -Force; if (-not (Test-SkipSourceFile $f)) { $allFiles += $f } }
}

$untracked = git ls-files --others --exclude-standard 2>&1 | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
$untrackedSources = @($untracked | Where-Object { $_ -match '\.(kt|kts|java|xml)$' })
$untrackedToDump = if ($IncludeTests) { $untrackedSources } else { @($untrackedSources | Where-Object { -not (Test-IsTestRelPath $_) }) }

Write-Host "Parsing reports..." -ForegroundColor Yellow
$testRows = Parse-TestResults $OutDirFull
$detektRows = Parse-DetektReport $OutDirFull
$lintRows = Parse-LintReport $OutDirFull
$ktlintRows = Parse-KtlintReport $OutDirFull
$missingRows = Analyze-MissingTranslations $OutDirFull $allFiles
$suspiciousScanFiles = if ($IncludeTests) { $allFiles } else { @($allFiles | Where-Object { -not (Test-IsTestRelPath (Get-Rel $_.FullName)) }) }
$totalMatches = Find-SuspiciousMatches $OutDirFull $suspiciousScanFiles

# Final Overview & Diagnostics
$overview = New-Object System.Text.StringBuilder
[void]$overview.AppendLine("PROJECT OVERVIEW`n" + ('=' * 100))
[void]$overview.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")

# verify_status check
$verifyStatusFile = Join-Path $RootFull "verify_status.txt"
if (Test-Path $verifyStatusFile) {
    $vStatus = Get-Content $verifyStatusFile -Raw
    [void]$overview.AppendLine("Last VERIFY status: $vStatus")
} else {
    [void]$overview.AppendLine("WARNING: verify_status.txt not found. Dump may be STALE.")
}

[void]$overview.AppendLine("`nReports parsed:")
[void]$overview.AppendLine("  Tests: $(Format-ReportCount 'Tests' (@($testRows).Count))")
[void]$overview.AppendLine("  Detekt: $(Format-ReportCount 'Detekt' (@($detektRows).Count))")
[void]$overview.AppendLine("  Lint: $(Format-ReportCount 'Lint' (@($lintRows).Count))")
[void]$overview.AppendLine("  Ktlint: $(Format-ReportCount 'Ktlint' (@($ktlintRows).Count))")

[void]$overview.AppendLine("`nProcessed Report Files:")
foreach ($rep in $script:ProcessedReports | Sort-Object File) {
    $stale = Get-StaleWarning $rep.Time
    [void]$overview.AppendLine("  $($rep.File) ($($rep.Time.ToString('yyyy-MM-dd HH:mm:ss')))$stale")
}
Save-Text (Join-Path $OutDirFull '00_overview.txt') $overview.ToString()

# Build sections and combine
$gitSb = New-Object System.Text.StringBuilder
if ($Blind) { [void]$gitSb.AppendLine('(git context omitted)') } else {
    [void]$gitSb.AppendLine("branch: $(git branch --show-current 2>&1)`n`n- git status --short -`n$((git status --short 2>&1) -join [Environment]::NewLine)`n`n- git log -20 -`n$((git log -20 --oneline 2>&1) -join [Environment]::NewLine)")
}
Save-Text (Join-Path $OutDirFull '20_git.txt') $gitSb.ToString()

$keyPatterns = @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'libs.versions.toml', 'AndroidManifest.xml', 'detekt.yml', '.editorconfig', '.gitignore')
$keyFilesSb = New-Object System.Text.StringBuilder
foreach ($f in ($allFiles | Where-Object { $name = $_.Name; $keyPatterns | Where-Object { $name -like "*$_*" } } | Sort-Object FullName)) { Add-FileDump $keyFilesSb $f -ForceFull }
Save-Text (Join-Path $OutDirFull '20_key_files.txt') $keyFilesSb.ToString()

$sourcesSb = New-Object System.Text.StringBuilder
foreach ($f in ($allFiles | Where-Object { Test-IncludeForFullDump $_ } | Sort-Object FullName)) { Add-FileDump $sourcesSb $f }
Save-Text (Join-Path $OutDirFull '30_sources_full.txt') $sourcesSb.ToString()

$combined = Join-Path $OutDirFull 'ProjectDump_Combined.txt'
$combinedSb = New-Object System.Text.StringBuilder
@('00_overview.txt', '20_git.txt', '20_key_files.txt', '30_sources_full.txt', '40_tests_summary.csv', '41_failing_tests.txt', '42_detekt_summary.csv', '43_lint_summary.csv', '44_ktlint_summary.csv', '50_missing_translations.csv', '61_suspicious_matches.txt') | ForEach-Object {
    Append-FileSection $combinedSb (Join-Path $OutDirFull $_) $_.Replace('_', ' ').Replace('.txt', '').Replace('.csv', ' CSV').ToUpper()
}
Save-Text $combined $combinedSb.ToString()
Write-Host "`nDump completed: $combined ($([math]::Round((Get-Item $combined).Length / 1MB, 2)) MB)" -ForegroundColor Green
