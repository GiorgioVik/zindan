# Converts USER_GUIDE.md to HTML and prints PDF via Microsoft Edge.
param(
    [string]$InputMd = (Join-Path $PSScriptRoot "..\USER_GUIDE.md"),
    [string]$OutputPdf = (Join-Path $PSScriptRoot "..\USER_GUIDE.pdf")
)

$ErrorActionPreference = "Stop"

function Escape-Html([string]$text) {
    return [System.Net.WebUtility]::HtmlEncode($text)
}

function Format-Inline([string]$line) {
    $s = Escape-Html $line
    $s = [regex]::Replace($s, '\[([^\]]+)\]\(([^)]+)\)', '<a href="$2">$1</a>')
    $s = [regex]::Replace($s, '\*\*([^*]+)\*\*', '<strong>$1</strong>')
    return $s
}

function Convert-MarkdownToHtml([string[]]$lines) {
    $sb = New-Object System.Text.StringBuilder
    $inUl = $false
    $inOl = $false
    $inTable = $false
    $tableHeaderDone = $false

    foreach ($raw in $lines) {
        $line = $raw -replace "`r$", ""

        if ($line -match '^\s*$') {
            if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
            if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
            if ($inTable) { [void]$sb.AppendLine("</table>"); $inTable = $false; $tableHeaderDone = $false }
            continue
        }

        if ($line -eq '---') {
            if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
            if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
            if ($inTable) { [void]$sb.AppendLine("</table>"); $inTable = $false; $tableHeaderDone = $false }
            [void]$sb.AppendLine("<hr/>")
            continue
        }

        if ($line -match '^# (.+)$') {
            if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
            if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
            if ($inTable) { [void]$sb.AppendLine("</table>"); $inTable = $false; $tableHeaderDone = $false }
            [void]$sb.AppendLine("<h1>$(Format-Inline $Matches[1])</h1>")
            continue
        }

        if ($line -match '^## (.+)$') {
            if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
            if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
            if ($inTable) { [void]$sb.AppendLine("</table>"); $inTable = $false; $tableHeaderDone = $false }
            [void]$sb.AppendLine("<h2>$(Format-Inline $Matches[1])</h2>")
            continue
        }

        if ($line -match '^> (.+)$') {
            [void]$sb.AppendLine("<blockquote>$(Format-Inline $Matches[1])</blockquote>")
            continue
        }

        if ($line -match '^\|(.+)\|$') {
            if ($line -match '^\|[\s\-:|]+\|$') { continue }
            $cells = ($line.Trim('|') -split '\|') | ForEach-Object { $_.Trim() }
            if (-not $inTable) {
                [void]$sb.AppendLine('<table>')
                $inTable = $true
                $tableHeaderDone = $false
            }
            if (-not $tableHeaderDone) {
                [void]$sb.AppendLine('<thead><tr>' + (($cells | ForEach-Object { "<th>$(Format-Inline $_)</th>" }) -join '') + '</tr></thead><tbody>')
                $tableHeaderDone = $true
            } else {
                [void]$sb.AppendLine('<tr>' + (($cells | ForEach-Object { "<td>$(Format-Inline $_)</td>" }) -join '') + '</tr>')
            }
            continue
        } elseif ($inTable) {
            [void]$sb.AppendLine("</tbody></table>")
            $inTable = $false
            $tableHeaderDone = $false
        }

        if ($line -match '^- (.+)$') {
            if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
            if (-not $inUl) { [void]$sb.AppendLine("<ul>"); $inUl = $true }
            [void]$sb.AppendLine("<li>$(Format-Inline $Matches[1])</li>")
            continue
        }

        if ($line -match '^\d+\. (.+)$') {
            if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
            if (-not $inOl) { [void]$sb.AppendLine("<ol>"); $inOl = $true }
            [void]$sb.AppendLine("<li>$(Format-Inline $Matches[1])</li>")
            continue
        }

        if ($inUl) { [void]$sb.AppendLine("</ul>"); $inUl = $false }
        if ($inOl) { [void]$sb.AppendLine("</ol>"); $inOl = $false }
        [void]$sb.AppendLine("<p>$(Format-Inline $line)</p>")
    }

    if ($inUl) { [void]$sb.AppendLine("</ul>") }
    if ($inOl) { [void]$sb.AppendLine("</ol>") }
    if ($inTable) { [void]$sb.AppendLine("</tbody></table>") }
    return $sb.ToString()
}

$inputPath = (Resolve-Path $InputMd).Path
$outputPdfPath = [System.IO.Path]::GetFullPath($OutputPdf)
$htmlPath = [System.IO.Path]::ChangeExtension($outputPdfPath, ".html")
$lines = Get-Content -LiteralPath $inputPath -Encoding UTF8
$body = Convert-MarkdownToHtml $lines

$html = @"
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8"/>
<title>Zindan — инструкция для пользователя</title>
<style>
  @page { margin: 18mm 16mm; }
  body { font-family: "Segoe UI", Arial, sans-serif; font-size: 11pt; line-height: 1.45; color: #1a1a1a; }
  h1 { font-size: 22pt; color: #2d5016; border-bottom: 2px solid #c9a227; padding-bottom: 6px; }
  h2 { font-size: 14pt; color: #2d5016; margin-top: 1.2em; }
  p, li { margin: 0.35em 0; }
  ul, ol { margin: 0.4em 0 0.8em 1.2em; }
  blockquote { margin: 0.8em 0; padding: 0.6em 1em; border-left: 4px solid #c9a227; background: #f8f6ee; }
  table { border-collapse: collapse; width: 100%; margin: 0.8em 0; font-size: 10pt; }
  th, td { border: 1px solid #ccc; padding: 6px 8px; text-align: left; vertical-align: top; }
  th { background: #eef5e8; }
  a { color: #1a5fb4; text-decoration: none; }
  hr { border: none; border-top: 1px solid #ddd; margin: 1.2em 0; }
</style>
</head>
<body>
$body
</body>
</html>
"@

[System.IO.File]::WriteAllText($htmlPath, $html, [System.Text.UTF8Encoding]::new($false))

$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) {
    $edge = "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
}
if (-not (Test-Path $edge)) {
    throw "Microsoft Edge not found for PDF export."
}

if (Test-Path $outputPdfPath) { Remove-Item -Force $outputPdfPath }

$htmlUri = "file:///" + ($htmlPath -replace '\\', '/')
$edgeArgs = @(
    "--headless=new",
    "--disable-gpu",
    "--run-all-compositor-stages-before-draw",
    "--virtual-time-budget=10000",
    "--print-to-pdf=$outputPdfPath",
    $htmlUri
)
# Edge logs Chromium noise to stderr; ignore it and wait for the PDF file.
$null = Start-Process -FilePath $edge -ArgumentList $edgeArgs -Wait -WindowStyle Hidden

Start-Sleep -Seconds 2
if (-not (Test-Path $outputPdfPath)) {
    throw "PDF was not created: $outputPdfPath"
}

Write-Host "Created: $outputPdfPath"
Write-Host "HTML:    $htmlPath"
