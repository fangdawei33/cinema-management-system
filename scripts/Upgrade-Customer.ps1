param([string]$Server='localhost')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
foreach ($file in @('004_customer_role.sql','002_procedures.sql')) {
    sqlcmd -S $Server -E -C -b -f 65001 -i (Join-Path 'database' $file)
    if ($LASTEXITCODE -ne 0) { throw "Customer upgrade failed: $file" }
}
Write-Host 'Customer registration and order-ownership upgrade applied. Existing data preserved.'
