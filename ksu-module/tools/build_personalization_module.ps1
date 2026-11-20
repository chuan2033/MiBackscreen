param(
    [Parameter(Mandatory = $true)]
    [string]$RomRoot,
    [string]$OutputRoot = "",
    [string]$StageRoot = ""
)

$ErrorActionPreference = "Stop"
$moduleId = "mibackscreen_18_personalization"
$dataRoot = "/data/system/theme_magic/users/0/mibackscreen_18"
$distRoot = Split-Path -Parent $PSScriptRoot
$packagingRoot = Join-Path $distRoot "packaging\mibackscreen_18_personalization"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $distRoot "out"
}
if ([string]::IsNullOrWhiteSpace($StageRoot)) {
    $StageRoot = [System.IO.Path]::GetTempPath()
}

$romRootResolved = (Resolve-Path -LiteralPath $RomRoot).Path
$modulePropPath = Join-Path $packagingRoot "module.prop"
$modulePropText = Get-Content -Raw -Encoding UTF8 -LiteralPath $modulePropPath
if ($modulePropText -notmatch '(?m)^version=(.+)$') {
    throw "module.prop is missing version"
}
$moduleVersion = $Matches[1].Trim()
$zipPath = Join-Path $OutputRoot "MiBackscreen_v${moduleVersion}.zip"
$stagePath = Join-Path $StageRoot ("${moduleId}_" + [guid]::NewGuid().ToString("N"))
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# Magisk 系管理器按「刷机包」处理模块 zip，需要标准安装器：
# update-binary 用 Magisk 官方 scripts/module_installer.sh 的原文，updater-script 只有 #MAGISK。
# KernelSU 系不读 META-INF，不受影响。module_installer 会用 -x 'META-INF/*' 解包，
# 这两个文件不会进模块目录。
$magiskUpdateBinary = @'
#!/sbin/sh

#################
# Initialization
#################

umask 022

# echo before loading util_functions
ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
'@
$magiskUpdaterScript = "#MAGISK"

function Require-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
}

function Write-Utf8Lf {
    param(
        [string]$Path,
        [string]$Text
    )
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd() + "`n"
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

function Write-Json {
    param(
        [string]$Path,
        [object]$Value
    )
    Write-Utf8Lf -Path $Path -Text ($Value | ConvertTo-Json -Depth 100)
}

function Clone-JsonNode {
    param([object]$Value)
    return ($Value | ConvertTo-Json -Depth 100 | ConvertFrom-Json)
}

function Convert-ThemePath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $Path
    }
    return $Path.Replace(
        "/product/etc/precust_theme/theme/.data",
        "$dataRoot/precust_theme/theme/.data")
}

function Convert-AppCardPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $Path
    }
    return $Path.Replace(
        "/system/media/rearscreen/appcard",
        "$dataRoot/appcard").Replace(
        "/product/media/rearscreen/appcard",
        "$dataRoot/appcard")
}

function Set-ThemeItemPaths {
    param([object]$Category)
    foreach ($item in @($Category.items)) {
        foreach ($name in @("resPreviewPath", "resLocalPath", "rightPath", "metaPath")) {
            if ($item.PSObject.Properties.Name -contains $name) {
                $item.$name = Convert-ThemePath -Path ([string]$item.$name)
            }
        }
    }
}

function Copy-ThemeResource {
    param([string]$AbsolutePath)
    $prefix = "/product/etc/precust_theme/theme/.data/"
    if (-not $AbsolutePath.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
        throw "Unsupported theme resource path: $AbsolutePath"
    }

    $relative = $AbsolutePath.Substring($prefix.Length).Replace('/', '\')
    $source = Join-Path $romRootResolved ("product\etc\precust_theme\theme\.data\" + $relative)
    $destinationRoot = Join-Path $stagePath "mibackscreen_resources\precust_theme\theme\.data"

    if ($AbsolutePath -like "*/preview/theme/*") {
        $source = Split-Path -Parent $source
        $relative = Split-Path -Parent $relative
    }
    Require-FileOrDirectory -Path $source
    $destination = Join-Path $destinationRoot $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
}

function Require-FileOrDirectory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required resource is missing: $Path"
    }
}

$normalTemplatePath = Join-Path $romRootResolved "product\media\rearscreen\template\normal\rearScreen.json"
$defaultTemplatePath = Join-Path $romRootResolved "product\media\rearscreen\template\default\rearScreen.json"
$appCardTemplatePath = Join-Path $romRootResolved "product\media\rearscreen\appcard\default\rearScreen.json"
$appCardResourcePath = Join-Path $romRootResolved "product\media\rearscreen\appcard"
# 17 Pro Max (popsicle) 原生 normal 模板，不在其中的条目由模块提供 (baseline).
$baselineNormalPath = Join-Path $distRoot "device_popsicle_template\normal\rearScreen.json"

foreach ($path in @(
    $modulePropPath,
    $normalTemplatePath,
    $defaultTemplatePath,
    $appCardTemplatePath,
    $baselineNormalPath
)) {
    Require-File -Path $path
}
Require-FileOrDirectory -Path $appCardResourcePath

$sourceNormal = Get-Content -Raw -Encoding UTF8 -LiteralPath $normalTemplatePath | ConvertFrom-Json
$sourceDefault = Get-Content -Raw -Encoding UTF8 -LiteralPath $defaultTemplatePath | ConvertFrom-Json
$sourceAppCards = Get-Content -Raw -Encoding UTF8 -LiteralPath $appCardTemplatePath | ConvertFrom-Json
$baselineNormal = Get-Content -Raw -Encoding UTF8 -LiteralPath $baselineNormalPath | ConvertFrom-Json

$baselineIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($category in @($baselineNormal.products)) {
    foreach ($item in @($category.items)) {
        [void]$baselineIds.Add([string]$item.resId)
    }
}
if ($baselineIds.Count -eq 0) {
    throw "The 17 Pro Max baseline template has no items: $baselineNormalPath"
}

# normal 补充模板直接使用 18 Pro Max 的完整 normal 模板，分类和条目顺序与 ROM 相同.
# 17 Pro Max 已有的条目保留 /product 原路径，APK 只用它们确定新条目的插入位置.
# 其余条目由模块提供，路径改写到 data-root，资源随包复制.
$normalSupplement = Clone-JsonNode -Value $sourceNormal
$moduleItems = @()
foreach ($category in @($normalSupplement.products)) {
    foreach ($item in @($category.items)) {
        if ([string]::IsNullOrWhiteSpace([string]$item.resId)) {
            throw "18 Pro Max normal template item has no resId in category $($category.type)"
        }
        if ($baselineIds.Contains([string]$item.resId)) {
            continue
        }
        foreach ($name in @("resLocalPath", "metaPath")) {
            if ([string]::IsNullOrWhiteSpace([string]$item.$name)) {
                throw "Module-provided item $($item.resId) has no $name"
            }
        }
        $moduleItems += [PSCustomObject]@{ Category = [string]$category.type; Item = $item }
    }
}
$moduleResTypes = @($moduleItems | ForEach-Object { [string]$_.Item.resType })
foreach ($requiredType in @("ai-mate", "groupPhoto", "phoneCase")) {
    if ($moduleResTypes -notcontains $requiredType) {
        throw "The 18 Pro Max template provides no $requiredType item"
    }
}

$sourceDefaultCompanion = $sourceDefault.products | Where-Object { $_.type -eq "ai-mate" } | Select-Object -First 1
if ($null -eq $sourceDefaultCompanion) {
    throw "The 18 Pro Max default template has no ai-mate category"
}
$defaultCompanion = Clone-JsonNode -Value $sourceDefaultCompanion
$defaultSupplement = [ordered]@{
    version = [int]$sourceDefault.version
    count = 1
    products = @($defaultCompanion)
}

$themePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$pathSources = @($moduleItems | ForEach-Object Item) + @($defaultCompanion.items)
foreach ($item in $pathSources) {
    foreach ($name in @("resPreviewPath", "resLocalPath", "rightPath", "metaPath")) {
        if (-not [string]::IsNullOrWhiteSpace([string]$item.$name)) {
            [void]$themePaths.Add([string]$item.$name)
        }
    }
}
foreach ($entry in $moduleItems) {
    foreach ($name in @("resPreviewPath", "resLocalPath", "rightPath", "metaPath")) {
        if ($entry.Item.PSObject.Properties.Name -contains $name) {
            $entry.Item.$name = Convert-ThemePath -Path ([string]$entry.Item.$name)
        }
    }
}
Set-ThemeItemPaths -Category $defaultCompanion
$normalSupplement.count = @($normalSupplement.products).Count

New-Item -ItemType Directory -Path $stagePath -Force | Out-Null
try {
    foreach ($fileName in @(
        "module.prop",
        "skip_mount",
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "uninstall.sh",
        "appinfo_utils.sh",
        "appinfo_txn.sh",
        "lib.sh",
        "status.sh",
        "action.sh",
        "logpack.sh",
        "fix-perms.sh",
        "fix-regperm.sh",
        "clear-reareye.sh",
        "fix-aiapp.sh"
    )) {
        $source = Join-Path $packagingRoot $fileName
        Require-File -Path $source
        Copy-Item -LiteralPath $source -Destination (Join-Path $stagePath $fileName)
    }

    # 状态面板前端（有 WebUI X / KSU WebUI Standalone 宿主时才会渲染）
    $webrootSource = Join-Path $packagingRoot "webroot"
    foreach ($webFile in @("index.html", "app.js", "style.css")) {
        $source = Join-Path $webrootSource $webFile
        Require-File -Path $source
        $webDest = Join-Path $stagePath "webroot"
        New-Item -ItemType Directory -Path $webDest -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination (Join-Path $webDest $webFile)
    }

    # 顶层脚本与 webroot 一起规范成「UTF-8 无 BOM + LF」
    $textFiles = @(Get-ChildItem -LiteralPath $stagePath -File) +
        @(Get-ChildItem -LiteralPath (Join-Path $stagePath "webroot") -File)
    foreach ($textFile in $textFiles) {
        Write-Utf8Lf -Path $textFile.FullName -Text (Get-Content -Raw -Encoding UTF8 -LiteralPath $textFile.FullName)
    }

    $metaInfRoot = Join-Path $stagePath "META-INF\com\google\android"
    Write-Utf8Lf -Path (Join-Path $metaInfRoot "update-binary") -Text $magiskUpdateBinary
    Write-Utf8Lf -Path (Join-Path $metaInfRoot "updater-script") -Text $magiskUpdaterScript

    $templateRoot = Join-Path $stagePath "mibackscreen_resources\media\rearscreen\template"
    Write-Json -Path (Join-Path $templateRoot "normal\rearScreen.json") -Value $normalSupplement
    Write-Json -Path (Join-Path $templateRoot "default\rearScreen.json") -Value $defaultSupplement

    foreach ($themePath in $themePaths) {
        Copy-ThemeResource -AbsolutePath $themePath
    }

    $appCardDestination = Join-Path $stagePath "mibackscreen_resources\media\rearscreen\appcard"
    New-Item -ItemType Directory -Path (Split-Path -Parent $appCardDestination) -Force | Out-Null
    Copy-Item -LiteralPath $appCardResourcePath -Destination $appCardDestination -Recurse -Force

    $moduleAppCards = Clone-JsonNode -Value $sourceAppCards
    foreach ($category in @($moduleAppCards.products)) {
        foreach ($item in @($category.items)) {
            foreach ($name in @("mtzPath", "appIconPath", "previewLightPath", "previewDarkPath")) {
                $item.$name = Convert-AppCardPath -Path ([string]$item.$name)
            }
        }
    }
    Write-Json -Path (Join-Path $appCardDestination "default\rearScreen.json") -Value $moduleAppCards

    $entryRoot = Join-Path $stagePath "appcard_entries"
    New-Item -ItemType Directory -Path $entryRoot -Force | Out-Null
    $appInfoEntries = @()
    foreach ($category in @($sourceAppCards.products)) {
        foreach ($item in @($category.items)) {
            $nameMap = [ordered]@{ fallback = [string]$item.appName.zh_CN }
            foreach ($property in $item.appName.PSObject.Properties) {
                if ($property.Name -ne "fallback") {
                    $nameMap[$property.Name] = $property.Value
                }
            }
            $entry = [ordered]@{
                resId = [string]$item.resId
                appName = ($nameMap | ConvertTo-Json -Compress -Depth 10)
                mtzPath = Convert-AppCardPath -Path ([string]$item.mtzPath)
                appIconPath = Convert-AppCardPath -Path ([string]$item.appIconPath)
                previewLightPath = Convert-AppCardPath -Path ([string]$item.previewLightPath)
                previewDarkPath = Convert-AppCardPath -Path ([string]$item.previewDarkPath)
                bindApp = [string]$item.bindApp
                isGame = $false
                isPreset = [bool]$item.isPreset
                appCardType = 1
            }
            $appInfoEntries += $entry
            Write-Json -Path (Join-Path $entryRoot ("{0}.json" -f $entry.resId)) -Value $entry
        }
    }
    if ($appInfoEntries.Count -ne 6) {
        throw "Expected 6 app card entries, found $($appInfoEntries.Count)"
    }
    # 由构建从 ROM 重新生成，只给校验脚本比对 6 张卡用；运行时不读它（源码目录里不再放这个文件）
    Write-Json -Path (Join-Path $stagePath "appcard_appInfo_patch.json") -Value $appInfoEntries

    $topLevelNames = @(Get-ChildItem -LiteralPath $stagePath -Force | ForEach-Object Name)
    if ($topLevelNames -contains "system" -or $topLevelNames -contains "product") {
        throw "The module staging root contains a mountable partition directory"
    }

    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $zipPath,
        [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $stageFull = (Resolve-Path -LiteralPath $stagePath).Path.TrimEnd('\') + '\'
        foreach ($file in Get-ChildItem -LiteralPath $stagePath -Recurse -File) {
            $entryName = $file.FullName.Substring($stageFull.Length).Replace('\', '/')
            $entry = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive,
                $file.FullName,
                $entryName,
                [System.IO.Compression.CompressionLevel]::Optimal)
            $isScript = $entryName.EndsWith('.sh') -or
                $entryName -eq 'META-INF/com/google/android/update-binary'
            $mode = if ($isScript) { 493 } else { 420 }
            $entry.ExternalAttributes = $mode -shl 16
        }
    } finally {
        $archive.Dispose()
    }

    $zipItem = Get-Item -LiteralPath $zipPath
    $zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash
    [PSCustomObject]@{
        Zip = $zipItem.FullName
        Version = $moduleVersion
        Files = (Get-ChildItem -LiteralPath $stagePath -Recurse -File).Count
        ModuleThemeItems = $moduleItems.Count
        ModuleThemeItemsByCategory = (($moduleItems | Group-Object Category |
            ForEach-Object { "{0}={1}" -f $_.Name, $_.Count }) -join " ")
        ZipBytes = $zipItem.Length
        Sha256 = $zipHash
    }
} finally {
    if (Test-Path -LiteralPath $stagePath) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
}
