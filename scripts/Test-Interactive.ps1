$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Start-Transcript -Path (Join-Path $projectRoot '.tools/integration-interactive.log') -Force
try { & "$PSScriptRoot/Test.ps1" } finally { Stop-Transcript }
