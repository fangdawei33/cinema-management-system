param([string]$Server='localhost')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
# Never replace an existing database. A repeated initialization is intentionally refused.
sqlcmd -S $Server -E -C -b -l 10 -Q "IF DB_ID(N'CinemaCourseDB') IS NOT NULL THROW 51000, N'CinemaCourseDB already exists. Initialization refused.',1; CREATE DATABASE CinemaCourseDB;"
if ($LASTEXITCODE -ne 0) { throw 'Database creation refused or failed; no scripts were applied.' }
foreach ($file in @('001_schema.sql','002_procedures.sql','003_seed.sql')) {
    sqlcmd -S $Server -E -C -b -f 65001 -i (Join-Path 'database' $file)
    if ($LASTEXITCODE -ne 0) { throw "Database script failed: $file. Do not rerun initialization; inspect the failure." }
}
Write-Host 'CinemaCourseDB initialized. Demo accounts: admin / cashier; password: Cinema@2026'
