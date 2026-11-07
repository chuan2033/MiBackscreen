param(
    [Parameter(Mandatory = $true)]
    [string]$ZipPath,
    [string]$RomRoot = ""
)

$ErrorActionPreference = "Stop"
$dataRoot = "/data/system/theme_magic/users/0/mibackscreen_18"
$zipResolved = (Resolve-Path -LiteralPath $ZipPath).Path
$romResolved = if ([string]::IsNullOrWhiteSpace($RomRoot)) {
    $null
} else {
    (Resolve-Path -LiteralPath $RomRoot).Path
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Require-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
}

function Read-ZipText {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [string]$Name
    )
    $entry = $Archive.GetEntry($Name)
    if ($null -eq $entry) {
        throw "Missing zip entry: $Name"
    }
    $stream = $entry.Open()
    try {
        $reader = [System.IO.StreamReader]::new(
            $stream,
            [System.Text.UTF8Encoding]::new($false, $true),
            $true)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-StreamSha256 {
    param([System.IO.Stream]$Stream)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        # Windows PowerShell 5.1 没有 [Convert]::ToHexString，用 BitConverter 兼容
        $bytes = $sha.ComputeHash($Stream)
        return ([System.BitConverter]::ToString($bytes) -replace '-', '')
    } finally {
        $sha.Dispose()
    }
}

function Convert-DataPathToEntry {
    param([string]$Path)
    if (-not $Path.StartsWith("$dataRoot/", [System.StringComparison]::Ordinal)) {
        throw "Unexpected data path: $Path"
    }
    $suffix = $Path.Substring($dataRoot.Length + 1)
    if ($suffix.StartsWith("appcard/")) {
        return "mibackscreen_resources/media/rearscreen/$suffix"
    }
    if ($suffix.StartsWith("precust_theme/theme/.data/")) {
        return "mibackscreen_resources/$suffix"
    }
    throw "Unsupported data path: $Path"
}

$archive = [System.IO.Compression.ZipFile]::OpenRead($zipResolved)
try {
    $names = @($archive.Entries | ForEach-Object FullName)
    $duplicates = @($names | Group-Object | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) {
        throw "Duplicate zip entries: $($duplicates.Name -join ', ')"
    }
    if ($names | Where-Object { $_ -match '^(system|product)(/|$)' }) {
        throw "Zip contains a mountable system or product root"
    }

    $required = @(
        "module.prop",
        "skip_mount",
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "uninstall.sh",
        "appinfo_utils.sh",
        "lib.sh",
        "status.sh",
        "action.sh",
        "logpack.sh",
        "fix-perms.sh",
        "fix-regperm.sh",
        "clear-reareye.sh",
        "fix-aiapp.sh",
        "webroot/index.html",
        "webroot/app.js",
        "webroot/style.css",
        "appcard_appInfo_patch.json",
        "mibackscreen_resources/media/rearscreen/template/normal/rearScreen.json",
        "mibackscreen_resources/media/rearscreen/template/default/rearScreen.json"
    )
    foreach ($name in $required) {
        if ($names -notcontains $name) {
            throw "Required zip entry is missing: $name"
        }
    }

    foreach ($scriptName in @(
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "uninstall.sh",
        "appinfo_utils.sh",
        "lib.sh",
        "status.sh",
        "action.sh",
        "logpack.sh",
        "fix-perms.sh",
        "fix-regperm.sh",
        "clear-reareye.sh",
        "fix-aiapp.sh"
    )) {
        $text = Read-ZipText -Archive $archive -Name $scriptName
        if (-not $text.StartsWith("#!/system/bin/sh`n")) {
            throw "$scriptName has an invalid shebang or line ending"
        }
        if ($text.Contains("`r") -or ([int]$text[0] -eq 0xFEFF)) {
            throw "$scriptName is not UTF-8 without BOM using LF line endings"
        }
        $entry = $archive.GetEntry($scriptName)
        $mode = ($entry.ExternalAttributes -shr 16) -band 0x1FF
        if ($mode -ne 493) {
            throw "$scriptName mode is not 0755 in the zip: $mode"
        }
    }

    $moduleProp = Read-ZipText -Archive $archive -Name "module.prop"
    # version / versionCode 期望值从打包源文件读取（与构建脚本同源），升版本只改 module.prop.
    # id 是模块身份，保持固定断言.
    $sourceModulePropPath = Join-Path (Split-Path -Parent $PSScriptRoot) "packaging\mibackscreen_18_personalization\module.prop"
    Require-File $sourceModulePropPath
    $sourceModuleProp = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceModulePropPath
    if ($sourceModuleProp -notmatch '(?m)^version=(.+)$') {
        throw "packaging module.prop is missing version"
    }
    $expectedVersion = $Matches[1].Trim()
    if ($sourceModuleProp -notmatch '(?m)^versionCode=(.+)$') {
        throw "packaging module.prop is missing versionCode"
    }
    $expectedVersionCode = $Matches[1].Trim()

    if ($moduleProp -notmatch '(?m)^id=(.+)$') {
        throw "module.prop is missing id"
    }
    if ($Matches[1].Trim() -ne "mibackscreen_18_personalization") {
        throw "module.prop identity is invalid"
    }
    if ($moduleProp -notmatch '(?m)^version=(.+)$') {
        throw "module.prop is missing version"
    }
    if ($Matches[1].Trim() -ne $expectedVersion) {
        throw "module.prop version is invalid: expected $expectedVersion"
    }
    if ($moduleProp -notmatch '(?m)^versionCode=(.+)$') {
        throw "module.prop is missing versionCode"
    }
    if ($Matches[1].Trim() -ne $expectedVersionCode) {
        throw "module.prop versionCode is invalid: expected $expectedVersionCode"
    }

    $normal = Read-ZipText -Archive $archive -Name (
        "mibackscreen_resources/media/rearscreen/template/normal/rearScreen.json") | ConvertFrom-Json
    $default = Read-ZipText -Archive $archive -Name (
        "mibackscreen_resources/media/rearscreen/template/default/rearScreen.json") | ConvertFrom-Json
    if (@($normal.products).Count -ne 2 -or
            @($normal.products | Where-Object type -eq "ai-mate").Count -ne 1 -or
            @($normal.products | Where-Object type -eq "ai").Count -ne 1) {
        throw "Normal supplemental template has unexpected categories"
    }
    $newTypes = @($normal.products | Where-Object type -eq "ai" | ForEach-Object items | ForEach-Object resType)
    if (@($newTypes | Where-Object { $_ -eq "groupPhoto" }).Count -ne 1 -or
            @($newTypes | Where-Object { $_ -eq "phoneCase" }).Count -ne 1) {
        throw "Normal supplemental template is missing AI additions"
    }
    if (@($default.products).Count -ne 1 -or $default.products[0].type -ne "ai-mate") {
        throw "Default supplemental template has unexpected categories"
    }

    $referencedEntries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($template in @($normal, $default)) {
        foreach ($category in @($template.products)) {
            foreach ($item in @($category.items)) {
                foreach ($property in @("resPreviewPath", "resLocalPath", "rightPath", "metaPath")) {
                    $path = [string]$item.$property
                    if (-not [string]::IsNullOrWhiteSpace($path)) {
                        [void]$referencedEntries.Add((Convert-DataPathToEntry -Path $path))
                    }
                }
            }
        }
    }

    $patch = Read-ZipText -Archive $archive -Name "appcard_appInfo_patch.json" | ConvertFrom-Json
    $preset = Read-ZipText -Archive $archive -Name (
        "mibackscreen_resources/media/rearscreen/appcard/default/rearScreen.json") | ConvertFrom-Json
    if (@($preset.products).Count -ne 4 -or
            @($preset.products | ForEach-Object items).Count -ne 6) {
        throw "App card preset has unexpected categories or items"
    }
    foreach ($item in @($preset.products | ForEach-Object items)) {
        foreach ($property in @("mtzPath", "appIconPath", "previewLightPath", "previewDarkPath")) {
            $path = [string]$item.$property
            if (-not $path.StartsWith("$dataRoot/appcard/", [System.StringComparison]::Ordinal)) {
                throw "App card preset path does not use the module data root: $path"
            }
        }
    }
    $entryFiles = @($names | Where-Object { $_ -match '^appcard_entries/[^/]+\.json$' })
    if (@($patch).Count -ne 6 -or $entryFiles.Count -ne 6) {
        throw "Expected 6 app card entries"
    }
    $ids = @($patch | ForEach-Object resId)
    if (@($ids | Sort-Object -Unique).Count -ne 6) {
        throw "App card IDs are not unique"
    }
    foreach ($item in @($patch)) {
        if ($entryFiles -notcontains ("appcard_entries/{0}.json" -f $item.resId)) {
            throw "Missing individual app card entry: $($item.resId)"
        }
        foreach ($property in @("mtzPath", "appIconPath", "previewLightPath", "previewDarkPath")) {
            [void]$referencedEntries.Add((Convert-DataPathToEntry -Path ([string]$item.$property)))
        }
    }

    foreach ($entryName in $referencedEntries) {
        if ($names -notcontains $entryName) {
            throw "Referenced resource is missing from zip: $entryName"
        }
    }

    $romCompared = 0
    if ($null -ne $romResolved) {
        foreach ($entry in $archive.Entries) {
            $relative = $null
            if ($entry.FullName.StartsWith("mibackscreen_resources/media/rearscreen/appcard/")) {
                if ($entry.FullName -eq "mibackscreen_resources/media/rearscreen/appcard/default/rearScreen.json") {
                    continue
                }
                $relative = "product/media/rearscreen/appcard/" +
                    $entry.FullName.Substring("mibackscreen_resources/media/rearscreen/appcard/".Length)
            } elseif ($entry.FullName.StartsWith("mibackscreen_resources/precust_theme/theme/.data/")) {
                $relative = "product/etc/precust_theme/theme/.data/" +
                    $entry.FullName.Substring("mibackscreen_resources/precust_theme/theme/.data/".Length)
            }
            if ($null -eq $relative) {
                continue
            }
            $romPath = Join-Path $romResolved $relative.Replace('/', '\')
            Require-File -Path $romPath
            $zipStream = $entry.Open()
            try {
                $zipHash = Get-StreamSha256 -Stream $zipStream
            } finally {
                $zipStream.Dispose()
            }
            $romHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $romPath).Hash
            if ($zipHash -ne $romHash) {
                throw "ROM hash mismatch: $($entry.FullName)"
            }
            $romCompared++
        }
    }

    [PSCustomObject]@{
        Zip = $zipResolved
        Entries = $archive.Entries.Count
        AppCards = 6
        ReferencedResources = $referencedEntries.Count
        RomFilesVerified = $romCompared
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipResolved).Hash
        Status = "PASS"
    }
} finally {
    $archive.Dispose()
}
