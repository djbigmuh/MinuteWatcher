<#
.SYNOPSIS
Создаёт единый дамп проекта для AI-ассистента.
.DESCRIPTION
Гарантии:
1. Используются ТОЛЬКО файлы под версионным контролем (git ls-files).
2. Исключаются: .artifacts/, .gradle_home/, .gradle/, build/, .idea/,
   .kotlin/, .cxx/, intermediates/, generated/, captures/, screenshots/,
   reports/, audit_dump/, .git/, node_modules/.
3. Исключаются файлы: local.properties, benchmark-config.txt,
   detekt_output.txt, diag_result.txt, deps_ksp.txt, ktlint_output.txt,
   make_audit_dump.ps1, MAKE_AUDIT_DUMP.bat, *.log, *.apk, *.aab, *.jks.
4. Парсятся отчёты lint/ktlint/detekt/test results.
5. Анализируются missing translations.
6. Всё собирается в ОДИН файл ProjectDump_Combined.txt.
7. Корректная UTF-8 кодировка без BOM при чтении и записи.
.NOTES
Запуск: .\make_audit_dump.ps1 [-IncludeTests]
#>
param(
    [switch]$IncludeTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ============================================================================
# Constants
# ============================================================================

$RootFull = (Get-Location).Path

# Глобальная установка UTF-8 для всех cmdlet'ов PowerShell
$PSDefaultParameterValues['*:Encoding'] = 'utf8'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Директории, которые ВСЕГДА исключаются (проверка по сегментам пути)
$SkipSegments = @(
    '.artifacts',
    '.gradle_home',
    '.gradle',
    'build',
    '.idea',
    '.kotlin',
    '.cxx',
    'intermediates',
    'generated',
    'tmp',
    'captures',
    'screenshots',
    'reports',
    'audit_dump',
    '.git',
    'node_modules'
)

# Файлы, которые ВСЕГДА исключаются (по имени)
$SkipFileNames = @(
    'local.properties',
    'benchmark-config.txt',
    'detekt_output.txt',
    'diag_result.txt',
    'deps_ksp.txt',
    'ktlint_output.txt',
    'make_audit_dump.ps1',
    'MAKE_AUDIT_DUMP.bat',
    'verify.ps1',
    'VERIFY.bat'
)

# Бинарные расширения
$BinaryExtensions = @(
    '.png', '.webp', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.svg',
    '.jar', '.aar', '.so', '.bin', '.class',
    '.pdf', '.ttf', '.otf', '.woff', '.woff2',
    '.zip', '.7z', '.rar', '.gz', '.tar',
    '.mp3', '.mp4', '.wav', '.exe', '.dll', '.o', '.a',
    '.apk', '.aab', '.jks', '.keystore', '.hprof'
)

# Расширения для полного дампа
$FullDumpExtensions = @(
    '.kt', '.kts', '.java',
    '.xml',
    '.gradle', '.toml',
    '.yml', '.yaml',
    '.md',
    '.pro',
    '.json',
    '.properties',
    '.cfg', '.ini',
    '.gitignore', '.editorconfig',
    '.txt'
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
    param([string]$Path, [array]$Rows)
    if ($null -eq $Rows -or $Rows.Count -eq 0) {
        Save-Text $Path ''
        return
    }
    $csv = $Rows | ConvertTo-Csv -NoTypeInformation
    Save-Text $Path ($csv -join "`r`n")
}

function Get-Rel {
    param([string]$Path)
    if ($Path.StartsWith($RootFull)) {
        return $Path.Substring($RootFull.Length).TrimStart('\', '/')
    }
    return $Path
}

function Test-FullDumpExt {
    param([string]$Ext)
    return $FullDumpExtensions -contains $Ext.ToLower()
}

function Test-SkipSourceFile {
    param([System.IO.FileInfo]$File)

    # Проверка по сегментам пути
    $segments = $File.FullName.Split(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    foreach ($segment in $segments) {
        if ($SkipSegments -contains $segment) { return $true }
    }

    # Проверка по имени файла
    if ($SkipFileNames -contains $File.Name) { return $true }

    # Проверка по расширению
    if ($BinaryExtensions -contains $File.Extension.ToLower()) { return $true }

    # Логи
    if ($File.Name -like '*.log') { return $true }

    return $false
}

function Test-IncludeForFullDump {
    param([System.IO.FileInfo]$File)

    $ext = $File.Extension.ToLower()
    $rel = Get-Rel $File.FullName
    $relSlash = $rel.Replace('\', '/')

    if (-not (Test-FullDumpExt $ext)) { return $false }
    if ($File.Length -gt 2MB) { return $false }
    if ($File.Name -like '*.svg') { return $false }

    # Исключаем drawables/mipmap
    if ($relSlash -match '(^|/)drawable') { return $false }
    if ($relSlash -match '(^|/)mipmap') { return $false }

    # Для XML в res/ — только values, layout, navigation, menu, xml
    if ($ext -eq '.xml' -and $relSlash -match '/res/') {
        if ($relSlash -notmatch '/res/(values|values-[^/]+|layout|layout-[^/]+|navigation|menu|xml)/') {
            return $false
        }
    }

    # Ограничение для docs/
    if ($relSlash -match '/docs/' -and $File.Length -gt 200KB) { return $false }

    # Тесты — только если явно запрошены
    if (-not $IncludeTests) {
        if ($relSlash -match '/src/test/' -or $relSlash -match '/src/androidTest/') {
            return $false
        }
    }

    return $true
}

function Add-FileDump {
    param(
        [System.Text.StringBuilder]$Sb,
        [System.IO.FileInfo]$File,
        [switch]$ForceFull
    )

    $rel = Get-Rel $File.FullName

    # Читаем с автоопределением кодировки (UTF-8 preferred)
    $lines = @()
    try {
        $reader = New-Object System.IO.StreamReader($File.FullName, [System.Text.Encoding]::UTF8, $true)
        try {
            $lineList = New-Object System.Collections.Generic.List[string]
            while ($null -ne ($line = $reader.ReadLine())) {
                $lineList.Add($line)
            }
            $lines = $lineList.ToArray()
        }
        finally {
            $reader.Close()
        }
    }
    catch {
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
    for ($i = 0; $i -lt $limit; $i++) {
        [void]$Sb.AppendLine(("{0,6}: {1}" -f ($i + 1), $lines[$i]))
    }

    if (-not $full -and $lines.Count -gt 200) {
        [void]$Sb.AppendLine('')
        [void]$Sb.AppendLine("- LAST 80 LINES -")
        $start = [Math]::Max(0, $lines.Count - 80)
        for ($i = $start; $i -lt $lines.Count; $i++) {
            [void]$Sb.AppendLine(("{0,6}: {1}" -f ($i + 1), $lines[$i]))
        }
    }

    [void]$Sb.AppendLine("")
}

function Append-FileSection {
    param(
        [System.Text.StringBuilder]$Sb,
        [string]$Path,
        [string]$Title
    )

    [void]$Sb.AppendLine('')
    [void]$Sb.AppendLine("===== $Title =====")

    if (Test-Path -LiteralPath $Path) {
        $content = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($null -ne $content) { [void]$Sb.AppendLine($content) }
    }
    else {
        [void]$Sb.AppendLine('(file not found)')
    }
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

    # Ищем test-results во всех модулях
    $testDirs = Get-ChildItem -Path $RootFull -Recurse -Directory -Filter 'test-results' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\\.artifacts\\' -and $_.FullName -notmatch '\\\.gradle' }

    foreach ($dir in $testDirs) {
        $xmlFiles = Get-ChildItem -Path $dir.FullName -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
        foreach ($xmlFile in $xmlFiles) {
            try {
                [xml]$xml = Get-Content $xmlFile.FullName -Raw -Encoding UTF8
                $rows += [PSCustomObject]@{
                    File      = Get-Rel $xmlFile.FullName
                    TestSuite = $xml.testsuite.name
                    Tests     = $xml.testsuite.tests
                    Failures  = $xml.testsuite.failures
                    Errors    = $xml.testsuite.errors
                    Time      = $xml.testsuite.time
                }
                foreach ($tc in $xml.testsuite.testcase) {
                    if ($tc.failure -or $tc.error) {
                        [void]$failSb.AppendLine("FAIL: $($tc.classname).$($tc.name)")
                        if ($tc.failure) {
                            [void]$failSb.AppendLine("  Message: $($tc.failure.message)")
                        }
                        if ($tc.error) {
                            [void]$failSb.AppendLine("  Error: $($tc.error.message)")
                        }
                        [void]$failSb.AppendLine('')
                    }
                }
            }
            catch { }
        }
    }

    Save-Csv $csvPath $rows
    Save-Text $failPath $failSb.ToString()

    return ,$rows
}

function Parse-DetektReport {
    param([string]$OutDir)

    $detektCsv = Join-Path $OutDir '42_detekt_summary.csv'
    $detektRows = @()

    $detektFiles = @(
        'detekt_output.txt',
        'app/build/reports/detekt/detekt.txt',
        'core/data/build/reports/detekt/detekt.txt'
    ) | Where-Object { Test-Path $_ }

    if (-not $detektFiles) {
        Write-Warning "Detekt reports not found. Run .\VERIFY.bat before dumping."
        Save-Text $detektCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return ,$detektRows
    }

    foreach ($file in $detektFiles) {
        try {
            $content = Get-Content $file -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if (-not $content) { continue }

            $pattern = '(?m)^(?<file>[^:]+):(?<line>\d+):(?<col>\d+):\s*(?<message>.+)$'
            $matches = [regex]::Matches($content, $pattern)
            foreach ($m in $matches) {
                $detektRows += [PSCustomObject]@{
                    File    = $m.Groups['file'].Value
                    Line    = $m.Groups['line'].Value
                    Column  = $m.Groups['col'].Value
                    Message = $m.Groups['message'].Value.Trim()
                    Source  = 'detekt'
                }
            }
        }
        catch { }
    }

    if ($detektRows.Count -gt 5000) { $detektRows = $detektRows | Select-Object -First 5000 }

    Save-Csv $detektCsv $detektRows

    return ,$detektRows
}

function Parse-LintReport {
    param([string]$OutDir)

    $lintCsv = Join-Path $OutDir '43_lint_summary.csv'
    $lintRows = @()

    $lintFiles = @(
        'app/build/reports/lint-results-debug.txt',
        'app/build/reports/lint-results.txt'
    ) | Where-Object { Test-Path $_ }

    if (-not $lintFiles) {
        Write-Warning "Lint reports not found. Run .\VERIFY.bat before dumping."
        Save-Text $lintCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return ,$lintRows
    }

    foreach ($file in $lintFiles) {
        try {
            $content = Get-Content $file -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if (-not $content) { continue }

            $pattern = '(?ms)(?<severity>Error|Warning|Information):(?<message>.+?)\s+(?<file>[^\s]+):(?<line>\d+)'
            $matches = [regex]::Matches($content, $pattern)
            foreach ($m in $matches) {
                $lintRows += [PSCustomObject]@{
                    Severity = $m.Groups['severity'].Value
                    Message  = $m.Groups['message'].Value.Trim()
                    File     = $m.Groups['file'].Value
                    Line     = $m.Groups['line'].Value
                    Source   = 'lint'
                }
            }
        }
        catch { }
    }

    if ($lintRows.Count -gt 5000) { $lintRows = $lintRows | Select-Object -First 5000 }

    Save-Csv $lintCsv $lintRows

    return ,$lintRows
}

function Parse-KtlintReport {
    param([string]$OutDir)

    $ktlintCsv = Join-Path $OutDir '44_ktlint_summary.csv'
    $ktlintRows = @()

    $ktlintFiles = @(
        'ktlint_output.txt',
        'app/build/reports/ktlint/ktlint.txt'
    ) | Where-Object { Test-Path $_ }

    if (-not $ktlintFiles) {
        Write-Warning "Ktlint reports not found. Run .\VERIFY.bat before dumping."
        Save-Text $ktlintCsv "# REPORT NOT FOUND: run .\VERIFY.bat before dumping"
        return ,$ktlintRows
    }

    foreach ($file in $ktlintFiles) {
        try {
            $content = Get-Content $file -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if (-not $content) { continue }

            $pattern = '(?m)^(?<file>[^:]+):(?<line>\d+):(?<col>\d+):\s*(?<message>.+)$'
            $matches = [regex]::Matches($content, $pattern)
            foreach ($m in $matches) {
                $ktlintRows += [PSCustomObject]@{
                    File    = $m.Groups['file'].Value
                    Line    = $m.Groups['line'].Value
                    Column  = $m.Groups['col'].Value
                    Message = $m.Groups['message'].Value.Trim()
                    Source  = 'ktlint'
                }
            }
        }
        catch { }
    }

    if ($ktlintRows.Count -gt 5000) { $ktlintRows = $ktlintRows | Select-Object -First 5000 }

    Save-Csv $ktlintCsv $ktlintRows

    return ,$ktlintRows
}

function Analyze-MissingTranslations {
    param([string]$OutDir, [array]$allFiles)

    $missingCsv = Join-Path $OutDir '50_missing_translations.csv'
    $missingRows = @()

    $resFiles = @($allFiles | Where-Object {
        $_.FullName -match '\\src\\main\\res\\values' -and $_.Extension -eq '.xml'
    })

    $defaultResources = @{}
    $nonTranslatable = @{}
    $localeResources = @{}

    foreach ($file in $resFiles) {
        try {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        }
        catch { continue }

        $dirName = Split-Path -Parent $file.FullName | Split-Path -Leaf
        $allNames = @()

        if ($xml.resources -and $xml.resources.ChildNodes) {
            foreach ($node in $xml.resources.ChildNodes) {
                # КРИТИЧНО: проверяем тип узла. ChildNodes содержит XmlComment, XmlWhitespace и т.д.
                if ($node -isnot [System.Xml.XmlElement]) { continue }
                $nodeName = $node.localName
                $attrName = $node.GetAttribute('name')
                if ([string]::IsNullOrEmpty($attrName)) { continue }

                switch ($nodeName) {
                    'string' {
                        $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'string' }
                    }
                    'plurals' {
                        $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'plurals' }
                    }
                    'string-array' {
                        $allNames += [PSCustomObject]@{ Name = $attrName; Type = 'array' }
                    }
                    default {
                        continue
                    }
                }

                if ($dirName -eq 'values') {
                    $translatable = $node.GetAttribute('translatable')
                    if ($translatable -eq 'false') {
                        $nonTranslatable[$attrName] = $true
                    }
                }
            }
        }

        if ($dirName -eq 'values') {
            foreach ($item in $allNames) {
                $defaultResources[$item.Name] = $item.Type
            }
        }
        else {
            if ($dirName -match '^values-[a-z]{2,3}(-r[A-Z]{2,3})?$') {
                if (-not $localeResources.ContainsKey($dirName)) {
                    $localeResources[$dirName] = @{}
                }
                foreach ($item in $allNames) {
                    $localeResources[$dirName][$item.Name] = $item.Type
                }
            }
        }
    }

    foreach ($localeDir in $localeResources.Keys) {
        $present = $localeResources[$localeDir]
        foreach ($key in $defaultResources.Keys) {
            if ($nonTranslatable.ContainsKey($key)) { continue }
            if (-not $present.ContainsKey($key)) {
                $missingRows += [PSCustomObject]@{
                    Locale     = $localeDir
                    MissingKey = $key
                    Type       = $defaultResources[$key]
                }
            }
        }
    }

    Save-Csv $missingCsv $missingRows

    return ,$missingRows
}

function Find-SuspiciousMatches {
    param([string]$OutDir, [array]$allFiles)

    $hintsPath = Join-Path $OutDir '61_suspicious_matches.txt'

    $patterns = @(
        @{ Name = 'TODO';       Regex = '//\s*TODO' },
        @{ Name = 'FIXME';      Regex = '//\s*FIXME' },
        @{ Name = 'HACK';       Regex = '//\s*HACK' },
        @{ Name = 'DEPRECATED'; Regex = '@Deprecated|@Suppress\("DEPRECATION"\)' },
        @{ Name = 'SUPPRESS';   Regex = '@Suppress\(' },
        @{ Name = 'MAGIC_NUMBER'; Regex = '\b\d{2,}\b' }
    )

    $hintsSb = New-Object System.Text.StringBuilder
    $textFiles = $allFiles | Where-Object {
        Test-FullDumpExt $_.Extension -and
        ($_.Extension -notmatch '\.(json|md|csv|toml|properties|cfg|ini|txt)$')
    }

    $totalMatches = 0

    foreach ($file in $textFiles) {
        if ($file.Length -gt 512KB) { continue }
        if ($file.Name -like 'make_audit_dump*' -or $file.Name -eq 'MAKE_AUDIT_DUMP.bat' -or $file.Name -eq 'verify.ps1' -or $file.Name -eq 'VERIFY.bat') { continue }

        $lines = @()
        try {
            $lines = [System.IO.File]::ReadAllLines($file.FullName, [System.Text.Encoding]::UTF8)
        }
        catch { continue }

        $patternCounts = @{}
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $lineText = $lines[$i]
            foreach ($pattern in $patterns) {
                if ($lineText -match $pattern.Regex) {
                    # Reduce MAGIC_NUMBER noise in code files
                    if ($pattern.Name -eq 'MAGIC_NUMBER') {
                        if ($lineText -match '["' + "'" + ']|//|\*') { continue }
                    }

                    $key = $pattern.Name
                    if (-not $patternCounts.ContainsKey($key)) { $patternCounts[$key] = 0 }
                    $patternCounts[$key]++
                    $totalMatches++
                }
            }
        }

        if ($patternCounts.Count -gt 0) {
            [void]$hintsSb.AppendLine("File: $(Get-Rel $file.FullName)")
            foreach ($key in $patternCounts.Keys) {
                [void]$hintsSb.AppendLine("  $key : $($patternCounts[$key])")
            }
            [void]$hintsSb.AppendLine('')
        }
    }

    Save-Text $hintsPath $hintsSb.ToString()

    return $totalMatches
}

# ============================================================================
# Main
# ============================================================================

Write-Host "Creating audit dump..." -ForegroundColor Cyan
Write-Host "  Root: $RootFull" -ForegroundColor Gray

# Проверка git
if (-not (Test-Path ".git")) {
    Write-Error "This script must be run from the root of a git repository."
    exit 1
}

# Output dir
$OutDir = Join-Path $RootFull 'audit_dump'
if (Test-Path -LiteralPath $OutDir) {
    try {
        Remove-Item -LiteralPath $OutDir -Recurse -Force -ErrorAction Stop
    }
    catch {
        $stamp = Get-Date -Format 'yyyyMMdd_HH-mm-ss'
        $OutDir = Join-Path $RootFull ("audit_dump_" + $stamp)
    }
}
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$OutDirFull = (Resolve-Path -LiteralPath $OutDir).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
Write-Host "  Output: $OutDirFull" -ForegroundColor Gray

# ============================================================================
# Collect Files
# ============================================================================

Write-Host "Collecting files from git..." -ForegroundColor Yellow

$gitFiles = git ls-files 2>&1 | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
$allFiles = @()

foreach ($relPath in $gitFiles) {
    $fullPath = Join-Path $RootFull $relPath
    if (Test-Path $fullPath) {
        $file = Get-Item $fullPath
        if (-not (Test-SkipSourceFile $file)) {
            $allFiles += $file
        }
    }
}

# --- Untracked (но не игнорируемые) файлы: аудит-дамп их НЕ содержит, предупреждаем явно ---
$untracked = git ls-files --others --exclude-standard 2>&1 |
    Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }
$untrackedSources = @($untracked | Where-Object { $_ -match '\.(kt|kts|java|xml)$' })

if ($untrackedSources.Count -gt 0) {
    Write-Warning "Found $($untrackedSources.Count) untracked source files. They will be included in a separate section."
}

Write-Host "  Found $($allFiles.Count) files" -ForegroundColor Green

# ============================================================================
# Git Context
# ============================================================================

Write-Host "Collecting git context..." -ForegroundColor Yellow

$gitSb = New-Object System.Text.StringBuilder
[void]$gitSb.AppendLine("git executable: $(Get-Command git -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)")
[void]$gitSb.AppendLine("inside work tree: $(git rev-parse --is-inside-work-tree 2>&1)")
[void]$gitSb.AppendLine("branch: $(git branch --show-current 2>&1)")
[void]$gitSb.AppendLine('')
[void]$gitSb.AppendLine('- git status --short -')
[void]$gitSb.AppendLine((git status --short 2>&1))
[void]$gitSb.AppendLine('')
[void]$gitSb.AppendLine('- git log -20 -')
[void]$gitSb.AppendLine((git log -20 --oneline 2>&1))

Save-Text (Join-Path $OutDirFull '20_git.txt') $gitSb.ToString()

# ============================================================================
# Parse Reports
# ============================================================================

Write-Host "Parsing reports..." -ForegroundColor Yellow

$testRows = Parse-TestResults $OutDirFull
$detektRows = Parse-DetektReport $OutDirFull
$lintRows = Parse-LintReport $OutDirFull
$ktlintRows = Parse-KtlintReport $OutDirFull
$missingRows = Analyze-MissingTranslations $OutDirFull $allFiles
$totalMatches = Find-SuspiciousMatches $OutDirFull $allFiles

Write-Host "  Tests: $($testRows.Count)" -ForegroundColor Gray
Write-Host "  Detekt: $($detektRows.Count)" -ForegroundColor Gray
Write-Host "  Lint: $($lintRows.Count)" -ForegroundColor Gray
Write-Host "  Ktlint: $($ktlintRows.Count)" -ForegroundColor Gray
Write-Host "  Missing translations: $($missingRows.Count)" -ForegroundColor Gray
Write-Host "  Suspicious matches: $totalMatches" -ForegroundColor Gray

# ============================================================================
# Key Files
# ============================================================================

Write-Host "Collecting key files..." -ForegroundColor Yellow

$keyFilesPatterns = @(
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    'libs.versions.toml',
    'AndroidManifest.xml',
    'detekt.yml',
    '.editorconfig',
    '.gitignore'
)

$keyFiles = @($allFiles | Where-Object {
    $name = $_.Name
    $keyFilesPatterns | Where-Object { $name -like "*$_*" }
})

$keyFilesSb = New-Object System.Text.StringBuilder
$keyFiles = $keyFiles | Sort-Object FullName

foreach ($f in $keyFiles) {
    Add-FileDump -Sb $keyFilesSb -File $f -ForceFull
}

Save-Text (Join-Path $OutDirFull '20_key_files.txt') $keyFilesSb.ToString()

# ============================================================================
# Sources Full
# ============================================================================

Write-Host "Collecting source files..." -ForegroundColor Yellow

$sourcesSb = New-Object System.Text.StringBuilder
$sourceFiles = @($allFiles | Where-Object { Test-FullDumpExt $_.Extension } | Sort-Object FullName)

foreach ($f in $sourceFiles) {
    Add-FileDump -Sb $sourcesSb -File $f
}

Save-Text (Join-Path $OutDirFull '30_sources_full.txt') $sourcesSb.ToString()

# ============================================================================
# Overview
# ============================================================================

Write-Host "Building overview..." -ForegroundColor Yellow

$overview = New-Object System.Text.StringBuilder
[void]$overview.AppendLine("PROJECT OVERVIEW")
[void]$overview.AppendLine(('=' * 100))
[void]$overview.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$overview.AppendLine("Include tests: $IncludeTests")
[void]$overview.AppendLine("Total files collected: $($allFiles.Count)")
[void]$overview.AppendLine("Untracked source files: $($untrackedSources.Count)")
[void]$overview.AppendLine('')
[void]$overview.AppendLine("Reports parsed:")
[void]$overview.AppendLine("  Test results: $($testRows.Count)")
[void]$overview.AppendLine("  Detekt issues: $($detektRows.Count)")
[void]$overview.AppendLine("  Lint issues: $($lintRows.Count)")
[void]$overview.AppendLine("  Ktlint issues: $($ktlintRows.Count)")
[void]$overview.AppendLine("  Missing translations: $($missingRows.Count)")
[void]$overview.AppendLine("  Suspicious matches: $totalMatches")
[void]$overview.AppendLine('')
[void]$overview.AppendLine('Output files:')

Get-ChildItem -LiteralPath $OutDirFull -File | Sort-Object Name | ForEach-Object {
    [void]$overview.AppendLine(("{0,10} bytes {1}" -f $_.Length, $_.Name))
}

Save-Text (Join-Path $OutDirFull '00_overview.txt') $overview.ToString()

# ============================================================================
# Diagnostics
# ============================================================================

Write-Host "Building diagnostics..." -ForegroundColor Yellow

$diag = New-Object System.Text.StringBuilder
[void]$diag.AppendLine('Diagnostics')
[void]$diag.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$diag.AppendLine("PowerShell: $($PSVersionTable.PSVersion)")
[void]$diag.AppendLine("OS: $([System.Environment]::OSVersion.VersionString)")
[void]$diag.AppendLine("JAVA_HOME: $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '<not set>' })")
[void]$diag.AppendLine('')

if ($untrackedSources.Count -gt 0) {
    [void]$diag.AppendLine("WARNING: untracked source files (NOT included in main dump sections):")
    foreach ($f in $untrackedSources) { [void]$diag.AppendLine("  $f") }
    [void]$diag.AppendLine('')
}

if ($allFiles.Count -lt 10) {
    [void]$diag.AppendLine('WARNING: Very few files were collected.')
}

$localProperties = @($allFiles | Where-Object { $_.Name -eq 'local.properties' })
if ($localProperties.Count -gt 0) {
    [void]$diag.AppendLine('WARNING: local.properties exists in the project tree (should not be committed).')
    foreach ($file in $localProperties) {
        [void]$diag.AppendLine("  $(Get-Rel $file.FullName)")
    }
}

[void]$diag.AppendLine('')
[void]$diag.AppendLine('Skip segments:')
foreach ($s in $SkipSegments) { [void]$diag.AppendLine("  $s") }

[void]$diag.AppendLine('')
[void]$diag.AppendLine('Top 30 largest collected files:')
$allFiles | Sort-Object Length -Descending | Select-Object -First 30 | ForEach-Object {
    [void]$diag.AppendLine(("{0,10} bytes {1}" -f $_.Length, (Get-Rel $_.FullName)))
}

Save-Text (Join-Path $OutDirFull '99_diagnostics.txt') $diag.ToString()

# --- Untracked Sources Full Dump ---
$untrackedSb = New-Object System.Text.StringBuilder
if ($untrackedSources.Count -gt 0) {
    foreach ($relPath in $untrackedSources) {
        $fullPath = Join-Path $RootFull $relPath
        if (Test-Path $fullPath) {
            Add-FileDump -Sb $untrackedSb -File (Get-Item $fullPath) -ForceFull
        }
    }
}
Save-Text (Join-Path $OutDirFull '21_untracked_sources.txt') $untrackedSb.ToString()

# ============================================================================
# Combined Dump
# ============================================================================

Write-Host "Assembling combined dump..." -ForegroundColor Yellow

$combined = Join-Path $OutDirFull 'ProjectDump_Combined.txt'
$combinedSb = New-Object System.Text.StringBuilder

Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '00_overview.txt') -Title '00 OVERVIEW'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '99_diagnostics.txt') -Title '99 DIAGNOSTICS'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '20_git.txt') -Title '20 GIT'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '21_untracked_sources.txt') -Title '21 UNTRACKED SOURCES'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '40_tests_summary.csv') -Title '40 TESTS SUMMARY CSV'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '41_failing_tests.txt') -Title '41 FAILING TESTS'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '42_detekt_summary.csv') -Title '42 DETEKT SUMMARY CSV'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '43_lint_summary.csv') -Title '43 LINT SUMMARY CSV'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '44_ktlint_summary.csv') -Title '44 KTLINT SUMMARY CSV'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '50_missing_translations.csv') -Title '50 MISSING TRANSLATIONS CSV'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '61_suspicious_matches.txt') -Title '61 SUSPICIOUS MATCHES'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '20_key_files.txt') -Title '20 KEY FILES'
Append-FileSection -Sb $combinedSb -Path (Join-Path $OutDirFull '30_sources_full.txt') -Title '30 SOURCES FULL'

Save-Text $combined $combinedSb.ToString()

# ============================================================================
# Summary
# ============================================================================

$totalSize = (Get-Item $combined).Length
$totalSizeMB = [math]::Round($totalSize / 1MB, 2)

Write-Host " "
Write-Host "Dump completed successfully!" -ForegroundColor Green
Write-Host "  Combined dump: $combined" -ForegroundColor Cyan
Write-Host "  Total size: $totalSizeMB MB" -ForegroundColor Cyan
Write-Host "  Total files: $($allFiles.Count)" -ForegroundColor Cyan
Write-Host " "
Write-Host "Key improvements:" -ForegroundColor Magenta
Write-Host "  - Excluded .artifacts/ (old AI plans/walkthroughs)" -ForegroundColor Gray
Write-Host "  - Excluded .gradle_home/, build/, .gradle/" -ForegroundColor Gray
Write-Host "  - Used git ls-files (only versioned files)" -ForegroundColor Gray
Write-Host "  - Parsed lint/ktlint/detekt/test reports" -ForegroundColor Gray
Write-Host "  - Analyzed missing translations" -ForegroundColor Gray
Write-Host "  - Proper UTF-8 encoding without BOM" -ForegroundColor Gray
Write-Host "  - Everything in ONE file: ProjectDump_Combined.txt" -ForegroundColor Gray
