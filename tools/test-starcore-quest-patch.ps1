param(
    [Parameter(Mandatory = $true)][string]$StarCoreRoot,
    [Parameter(Mandatory = $true)][string]$VerifiedStarCoreJar,
    [string]$MavenExecutable = "mvn.cmd",
    [string]$OutputDirectory = ""
)
$ErrorActionPreference = "Stop"
$sourceRoot = (Resolve-Path -LiteralPath $StarCoreRoot).Path
$verifiedJar = (Resolve-Path -LiteralPath $VerifiedStarCoreJar).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\run\starcore-quest-tests"
}
$harnessRoot = [IO.Path]::GetFullPath($OutputDirectory)
$packagePath = "dev\starcore\starcore\quest"
$productionFiles = @("PlayerQuest.java", "QuestService.java", "QuestPersistenceHandler.java", "QuestStateStorage.java", "PersistenceQuestStateStorage.java")
$testFiles = @("DeferredQuestRestoreTest.java", "QuestStorageIsolationTest.java", "QuestPersistenceHandlerTest.java")
foreach ($kind in @("main", "test")) {
    $destination = Join-Path $harnessRoot "src\$kind\java\$packagePath"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $files = if ($kind -eq "main") { $productionFiles } else { $testFiles }
    foreach ($file in $files) {
        $source = Join-Path $sourceRoot "src\$kind\java\$packagePath\$file"
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing patched upstream source: $source" }
        Copy-Item -LiteralPath $source -Destination (Join-Path $destination $file) -Force
    }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "starcore-quest-test-pom.xml") -Destination (Join-Path $harnessRoot "pom.xml") -Force
$previousMavenOptions = $env:MAVEN_OPTS
try {
    $env:MAVEN_OPTS = "-Xmx384m -XX:MaxMetaspaceSize=256m"
    Push-Location -LiteralPath $harnessRoot
    try {
        & $MavenExecutable "-B" "-ntp" "-Dverified.starcore.jar=$verifiedJar" "test"
        if ($LASTEXITCODE -ne 0) { throw "Targeted StarCore quest verification failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally { $env:MAVEN_OPTS = $previousMavenOptions }
