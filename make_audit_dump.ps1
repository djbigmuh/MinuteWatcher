<#
.SYNOPSIS
Creates a single project dump for AI assistant (sources + TESTS + static analysis reports).

.DESCRIPTION
Guarantees:
1. Uses version-controlled files (git ls-files). Optionally (-IncludeUntracked)
   adds untracked working tree files (new tests/sources not yet committed).
2. TEST sources (src/test, src/androidTest, src/testFixtures) are included IN DUMP BY DEFAULT
   and placed in a separate section "32 TEST SOURCES FULL" so they can be audited alongside
   the main code. For lightweight dump without tests, use -ExcludeTests.
3. Excluded directories: .artifacts/, .gradle_home/, .gradle/, build/, .idea/, .kotlin/, .cxx/,
   intermediates/, generated/, tmp/, captures/, screenshots/, reports/, audit_dump/, .git/, node_modules/.
4. Excluded files: local.properties, benchmark-config.txt, detekt_output.txt, diag_result.txt,
   deps_ksp.txt, ktlint_output.txt, verify_output.txt, verify_status.txt, *.log, *.apk, *.aab, *.jks,
   and audit tooling itself (make_audit_dump.ps1, VERIFY.bat, etc.).
5. Recursively parses XML reports: ktlint (Checkstyle), detekt, lint-results-debug.xml, unit-test results.
6. Checks freshness (staleness) of reports relative to the last modified time of source files.
7. Combines everything into ONE file ProjectDump_Combined.txt.
8. Correct UTF-8 (without BOM) encoding when reading and writing.
9. Resilience to non-ASCII paths: git is called with core.quotepath=false; path matching
   does not depend on separator (\ or /).
10. Git context (branch, working tree state, commit history) is NOT included in dump:
    dump contains only the latest version of tracked files.

.PARAMETER ExcludeTests
Do not include test sources in dump (section 32 will be marked as skipped).

.PARAMETER IncludeTests
Legacy backward compatibility flag. Tests are now included BY DEFAULT, so the flag
is accepted but ignored (a message is output).

.PARAMETER IncludeUntracked
Additionally include UNtracked git working tree files (section 34). Useful for auditing
newly written, uncommitted tests/sources. Off by default (dump = tracked-only).

.PARAMETER Blind
Legacy backward compatibility flag: git context is never included in dump,
so the flag is accepted but ignored.

.NOTES
Execution: .\make_audit_dump.ps1 [-ExcludeTests] [-IncludeUntracked] [-Blind]
Compatibility: Windows PowerShell 5.1+ and PowerShell 7+ (syntax limited to 5.1).
#>
param(
    [switch]$ExcludeTests,
    [switch]$IncludeTests,
    [switch]$IncludeUntracked,
    [switch]$Blind
)

$ErrorActionPreference = "Continue"

# -IncludeTests is deprecated: tests are now included by default.
if ($IncludeTests) {
    Write-Host "NOTE: -IncludeTests is now the default and is ignored. Use -ExcludeTests to omit test sources." -ForegroundColor Yellow
}

# -Blind is deprecated: git context is never included in dump.
if ($Blind) {
    Write-Host "NOTE: -Blind is now the default behavior (git context is never included) and is ignored." -ForegroundColor Yellow
}

# Whether to include test sources in the dump.
$IncludeTestSources = -not $ExcludeTests

# ============================================================================
# Constants & Configuration
# ============================================================================

$RootFull = (Get-Location).Path.TrimEnd('\', '/')

# Global UTF-8 setting for all PowerShell cmdlets
$PSDefaultParameterValues['*:Encoding'] = 'utf8'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Flags for report presence
$script:ReportsFound = @{
    Tests  = $false
    Detekt = $false
    Lint   = $false
    Ktlint = $false
}

# Directories that are ALWAYS excluded
$SkipSegments = @(
    '.artifacts', '.gradle_home', '.gradle', 'build', '.idea', '.kotlin',
    '.cxx', 'intermediates', 'generated', 'tmp', 'captures', 'screenshots',
    'reports', 'audit_dump', '.git', 'node_modules'
)

# Files that are ALWAYS excluded (by name)
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

# Normalizes path separators to '/' for platform-independent matching.
function Normalize-Slashes {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return "" }
    return $Path.Replace('\', '/')
}

function Save-Text {
    param([string]$Path, [string]$Text)
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
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
    $normPath = Normalize-Slashes $Path
    $normRoot = Normalize-Slashes $RootFull
    if ($normPath.StartsWith($normRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return $normPath.Substring($normRoot.Length).TrimStart('/')
    }
    return $normPath
}

function Format-ReportCount {
    param([string]$Key, [int]$Count)
    if ($script:ReportsFound[$Key]) { return "$Count" }
    return 'N/A (report not found - run .\VERIFY.bat before dumping)'
}

function Test-IsTestRelPath {
    param([string]$RelPath)
    $rel = Normalize-Slashes $RelPath
    return ($rel -match '(^|/)src/(test|androidTest|testFixtures|testDebug|testRelease|unitTest)/')
}

function Test-SkipSourceFile {
    param([System.IO.FileInfo]$File)
    $relForSegments = Get-Rel $File.FullName
    $segments = (Normalize-Slashes $relForSegments).Split('/')
    foreach ($segment in $segments) {
        if ($SkipSegments -contains $segment) { return $true }
    }
    if ($SkipFileNames -contains $File.Name) { return $true }
    if ($BinaryExtensions -contains $File.Extension.ToLower()) { return $true }
    if ($File.Name -like '*.log') { return $true }
    return $false
}

# Determines if a file is "dumpable content" (type/size/res rules).
# Does NOT filter tests — main vs test separation is done by caller.
function Test-IncludeForFullDump {
    param([System.IO.FileInfo]$File)
    $ext = $File.Extension.ToLower()
    $rel = Get-Rel $File.FullName
    $relSlash = Normalize-Slashes $rel
    if ($FullDumpExtensions -notcontains $ext) { return $false }
    if ($File.Length -gt 2MB) { return $false }
    if ($File.Name -like '*.svg') { return $false }
    if ($relSlash -match '(^|/)(drawable|mipmap)') { return $false }
    if ($ext -eq '.xml' -and $relSlash -match '/res/') {
        if ($relSlash -notmatch '/res/(values|values-[^/]+|layout|layout-[^/]+|navigation|menu|xml)/') { return $false }
    }
    if ($relSlash -match '/docs/' -and $File.Length -gt 200KB) { return $false }
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
$script:LatestSourceChange = $null
$script:LatestSourceResolved = $false
# List of files included in the dump; populated during file collection phase.
$script:DumpSourceFiles = @()

function Resolve-LatestSourceChange {
    if ($script:LatestSourceResolved) { return $script:LatestSourceChange }
    $script:LatestSourceResolved = $true
    $sourceFiles = @($script:DumpSourceFiles)
    if ($sourceFiles.Count -gt 0) {
        $script:LatestSourceChange = ($sourceFiles | Measure-Object -Property LastWriteTime -Maximum).Maximum
    }
    return $script:LatestSourceChange
}

function Get-StaleWarning {
    param([DateTime]$ReportTime)
    $latestChange = Resolve-LatestSourceChange
    if ($null -ne $latestChange) {
        if ($ReportTime -lt $latestChange) {
            return " [STALE: report is older than latest source change ($($latestChange.ToString('yyyy-MM-dd HH:mm:ss')))]"
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
        Where-Object { (Normalize-Slashes $_.FullName) -notmatch '/\.artifacts/' -and (Normalize-Slashes $_.FullName) -notmatch '/\.gradle' }
    if (@($testDirs).Count -gt 0) { $script:ReportsFound['Tests'] = $true }
    foreach ($dir in $testDirs) {
        $variant = "unknown"
        if ($dir.FullName -match 'test(\w+)UnitTest') { $variant = $matches[1] }
        $xmlFiles = Get-ChildItem -Path $dir.FullName -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
        foreach ($xmlFile in $xmlFiles) {
            try {
                $content = Get-Content -LiteralPath $xmlFile.FullName -Raw -Encoding UTF8
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
        if (-not (Test-Path -LiteralPath $path)) { continue }
        try {
            $fileInfo = Get-Item -LiteralPath $path
            $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
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
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter '*.xml' -ErrorAction SilentlyContinue |
        Where-Object { (Normalize-Slashes $_.FullName) -match '/build/reports/detekt/' -and $_.Name -notmatch 'baseline' }
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
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter '*Check.xml' -ErrorAction SilentlyContinue |
        Where-Object { (Normalize-Slashes $_.FullName) -match '/build/reports/ktlint/' }
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
    $xmlFiles = Get-ChildItem -Path $RootFull -Recurse -Filter 'lint-results-debug.xml' -ErrorAction SilentlyContinue |
        Where-Object { (Normalize-Slashes $_.FullName) -match '/build/reports/' }
    if (-not $xmlFiles) {
        Save-Text $lintCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return @()
    }
    $script:ReportsFound['Lint'] = $true
    $rows = @()
    foreach ($path in $xmlFiles.FullName) {
        try {
            $fileInfo = Get-Item -LiteralPath $path
            $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
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
    $resFiles = @($allFiles | Where-Object { (Normalize-Slashes $_.FullName) -match '/src/main/res/values' -and $_.Extension -eq '.xml' })
    $defaultResources = @{}; $nonTranslatable = @{}; $localeResources = @{}
    foreach ($file in $resFiles) {
        try {
            $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            if ([string]::IsNullOrWhiteSpace($content)) { continue }
            [xml]$xml = $content
        } catch { continue }
        if ($null -eq $xml.resources) { continue }
        $dirName = Split-Path -Parent $file.FullName
        $dirName = Split-Path -Leaf $dirName
        $allNames = @()
        if ($xml.resources.ChildNodes) {
            foreach ($node in $xml.resources.ChildNodes) {
                if ($node -isnot [System.Xml.XmlElement]) { continue }
                $attrName = $node.GetAttribute('name')
                if ([string]::IsNullOrEmpty($attrName)) { continue }
                switch ($node.LocalName) {
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
if (Test-Path -LiteralPath $OutDir) {
    try { Remove-Item -LiteralPath $OutDir -Recurse -Force } catch { $OutDir = Join-Path $RootFull ("audit_dump_" + (Get-Date -Format 'yyyyMMdd_HH-mm-ss')) }
}
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$OutDirFull = (Resolve-Path -LiteralPath $OutDir).Path.TrimEnd('\', '/')

# --- Collect TRACKED files (core.quotepath=false reliably handles non-ASCII paths) ---
Write-Host "Collecting files..." -ForegroundColor Yellow
$gitFiles = git -c core.quotepath=false ls-files 2>&1 | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
$allFiles = @()
foreach ($rel in $gitFiles) {
    $full = Join-Path $RootFull $rel
    if (Test-Path -LiteralPath $full) {
        $f = Get-Item -LiteralPath $full -Force
        if (-not (Test-SkipSourceFile $f)) { $allFiles += $f }
    }
}

# --- Collect UNTRACKED working-tree source/test files (only with -IncludeUntracked) ---
$untrackedFiles = @()
if ($IncludeUntracked) {
    $untracked = git -c core.quotepath=false ls-files --others --exclude-standard 2>&1 | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($rel in $untracked) {
        if ($rel -notmatch '\.(kt|kts|java|xml)$') { continue }
        $full = Join-Path $RootFull $rel
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $f = Get-Item -LiteralPath $full -Force
        if (Test-SkipSourceFile $f) { continue }
        if (-not (Test-IncludeForFullDump $f)) { continue }
        if ((Test-IsTestRelPath $rel) -and (-not $IncludeTestSources)) { continue }
        $untrackedFiles += $f
    }
}

# Report freshness base: all files included in the dump.
$script:DumpSourceFiles = @($allFiles) + @($untrackedFiles)

Write-Host "Parsing reports..." -ForegroundColor Yellow
$testRows = Parse-TestResults $OutDirFull
$detektRows = Parse-DetektReport $OutDirFull
$lintRows = Parse-LintReport $OutDirFull
$ktlintRows = Parse-KtlintReport $OutDirFull
$missingRows = Analyze-MissingTranslations $OutDirFull $allFiles

# Suspicious scan: account for tests per flag (tests are also audited code).
$suspiciousScanFiles = @($allFiles | Where-Object {
    if ($IncludeTestSources) { $true } else { -not (Test-IsTestRelPath (Get-Rel $_.FullName)) }
})
if ($IncludeUntracked) {
    $suspiciousScanFiles += @($untrackedFiles | Where-Object {
        if ($IncludeTestSources) { $true } else { -not (Test-IsTestRelPath (Get-Rel $_.FullName)) }
    })
}
$totalMatches = Find-SuspiciousMatches $OutDirFull $suspiciousScanFiles

# ============================================================================
# Overview & Diagnostics
# ============================================================================
$overview = New-Object System.Text.StringBuilder
[void]$overview.AppendLine("PROJECT OVERVIEW`n" + ('=' * 100))
[void]$overview.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$overview.AppendLine("Test sources included: $(if ($IncludeTestSources) { 'YES' } else { 'NO (-ExcludeTests)' })")
[void]$overview.AppendLine("Untracked files included: $(if ($IncludeUntracked) { 'YES' } else { 'NO' })")

# verify_status check
$verifyStatusFile = Join-Path $RootFull "verify_status.txt"
if (Test-Path -LiteralPath $verifyStatusFile) {
    $vStatus = Get-Content -LiteralPath $verifyStatusFile -Raw
    [void]$overview.AppendLine("Last VERIFY status: $vStatus")
} else {
    [void]$overview.AppendLine("WARNING: verify_status.txt not found. Dump may be STALE.")
}

[void]$overview.AppendLine("`nReports parsed:")
[void]$overview.AppendLine("  Test result files: $(Format-ReportCount 'Tests' (@($testRows).Count))")
[void]$overview.AppendLine("  Detekt: $(Format-ReportCount 'Detekt' (@($detektRows).Count))")
[void]$overview.AppendLine("  Lint: $(Format-ReportCount 'Lint' (@($lintRows).Count))")
[void]$overview.AppendLine("  Ktlint: $(Format-ReportCount 'Ktlint' (@($ktlintRows).Count))")

[void]$overview.AppendLine("`nProcessed Report Files:")
foreach ($rep in ($script:ProcessedReports | Sort-Object { $_.File })) {
    $stale = Get-StaleWarning $rep.Time
    [void]$overview.AppendLine("  $($rep.File) ($($rep.Time.ToString('yyyy-MM-dd HH:mm:ss')))$stale")
}
Save-Text (Join-Path $OutDirFull '00_overview.txt') $overview.ToString()

# ============================================================================
# Sections
# ============================================================================

# --- KEY FILES (always FULL) ---
$keyPatterns = @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'libs.versions.toml', 'AndroidManifest.xml', 'detekt.yml', '.editorconfig', '.gitignore')
$keyFilesSb = New-Object System.Text.StringBuilder
$keyFileObjs = @($allFiles | Where-Object { $name = $_.Name; ($keyPatterns | Where-Object { $name -like "*$_*" }) } | Sort-Object FullName)
foreach ($f in $keyFileObjs) { Add-FileDump $keyFilesSb $f -ForceFull }
Save-Text (Join-Path $OutDirFull '20_key_files.txt') $keyFilesSb.ToString()

# --- Separate tracked files into MAIN sources and TESTS ---
$dumpable = @($allFiles | Where-Object { Test-IncludeForFullDump $_ })
$mainFiles = @($dumpable | Where-Object { -not (Test-IsTestRelPath (Get-Rel $_.FullName)) } | Sort-Object FullName)
$testFiles = @($dumpable | Where-Object { (Test-IsTestRelPath (Get-Rel $_.FullName)) } | Sort-Object FullName)

# --- 30 SOURCES FULL (main sources, WITHOUT tests) ---
$sourcesSb = New-Object System.Text.StringBuilder
foreach ($f in $mainFiles) { Add-FileDump $sourcesSb $f }
Save-Text (Join-Path $OutDirFull '30_sources_full.txt') $sourcesSb.ToString()

# --- 32 TEST SOURCES FULL (tests) ---
$testSourcesSb = New-Object System.Text.StringBuilder
if ($IncludeTestSources) {
    foreach ($f in $testFiles) { Add-FileDump $testSourcesSb $f }
    if ($testFiles.Count -eq 0) {
        [void]$testSourcesSb.AppendLine('(no test source files matched the dump filters)')
    }
} else {
    [void]$testSourcesSb.AppendLine('(test sources EXCLUDED via -ExcludeTests)')
}
Save-Text (Join-Path $OutDirFull '32_test_sources_full.txt') $testSourcesSb.ToString()

# --- 34 UNTRACKED FILES (optional) ---
if ($IncludeUntracked) {
    $untrackedSb = New-Object System.Text.StringBuilder
    foreach ($f in ($untrackedFiles | Sort-Object FullName)) {
        Add-FileDump $untrackedSb $f -DisplayName ("$((Get-Rel $f.FullName))  [UNTRACKED]")
    }
    if ($untrackedFiles.Count -eq 0) {
        [void]$untrackedSb.AppendLine('(no untracked source/test files)')
    }
    Save-Text (Join-Path $OutDirFull '34_untracked_files.txt') $untrackedSb.ToString()
}

# ============================================================================
# Combine
# ============================================================================
$combined = Join-Path $OutDirFull 'ProjectDump_Combined.txt'
$combinedSb = New-Object System.Text.StringBuilder

$sections = New-Object System.Collections.ArrayList
[void]$sections.Add(@{ File = '00_overview.txt';             Title = '00 OVERVIEW' })
[void]$sections.Add(@{ File = '20_key_files.txt';            Title = '20 KEY FILES' })
[void]$sections.Add(@{ File = '30_sources_full.txt';         Title = '30 SOURCES FULL' })
[void]$sections.Add(@{ File = '32_test_sources_full.txt';    Title = '32 TEST SOURCES FULL' })
if ($IncludeUntracked) {
    [void]$sections.Add(@{ File = '34_untracked_files.txt';  Title = '34 UNTRACKED FILES' })
}
[void]$sections.Add(@{ File = '40_tests_summary.csv';        Title = '40 TESTS SUMMARY CSV' })
[void]$sections.Add(@{ File = '41_failing_tests.txt';        Title = '41 FAILING TESTS' })
[void]$sections.Add(@{ File = '42_detekt_summary.csv';       Title = '42 DETEKT SUMMARY CSV' })
[void]$sections.Add(@{ File = '43_lint_summary.csv';         Title = '43 LINT SUMMARY CSV' })
[void]$sections.Add(@{ File = '44_ktlint_summary.csv';       Title = '44 KTLINT SUMMARY CSV' })
[void]$sections.Add(@{ File = '50_missing_translations.csv'; Title = '50 MISSING TRANSLATIONS CSV' })
[void]$sections.Add(@{ File = '61_suspicious_matches.txt';   Title = '61 SUSPICIOUS MATCHES' })

foreach ($sec in $sections) {
    Append-FileSection $combinedSb (Join-Path $OutDirFull $sec.File) $sec.Title
}
Save-Text $combined $combinedSb.ToString()

# --- Brief console summary ---
Write-Host ""
Write-Host "Dump completed: $combined ($([math]::Round((Get-Item -LiteralPath $combined).Length / 1MB, 2)) MB)" -ForegroundColor Green
Write-Host ("  Main sources : {0} file(s)" -f $mainFiles.Count)
if ($IncludeTestSources) {
    Write-Host ("  Test sources : {0} file(s)" -f $testFiles.Count) -ForegroundColor Cyan
} else {
    Write-Host "  Test sources : EXCLUDED (-ExcludeTests)" -ForegroundColor Yellow
}
if ($IncludeUntracked) {
    Write-Host ("  Untracked    : {0} file(s)" -f $untrackedFiles.Count)
}
