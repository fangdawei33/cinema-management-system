$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
function Find-JavaHome {
    $candidates = @($env:JAVA_HOME, (Join-Path $projectRoot '.tools/jdk'), 'D:\java_develop\jdk')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin/javac.exe'))) { return $candidate }
    }
    $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCommand) { return Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
    throw 'JDK 17+ required. Set JAVA_HOME to a valid JDK folder.'
}
$jdkHome = Find-JavaHome
$javaExe = Join-Path $jdkHome 'bin/java.exe'
$javacExe = Join-Path $jdkHome 'bin/javac.exe'
$jarExe = Join-Path $jdkHome 'bin/jar.exe'
