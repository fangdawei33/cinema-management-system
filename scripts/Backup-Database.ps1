param([string]$Server='localhost')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$backupDir=Join-Path $projectRoot 'backups'
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$backupFile=Join-Path $backupDir ('CinemaCourseDB_'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.bak')
if (Test-Path -LiteralPath $backupFile) { throw 'Backup target already exists; refusing to overwrite.' }
$sqlPath=$backupFile.Replace("'","''")
sqlcmd -S $Server -E -C -b -Q "BACKUP DATABASE CinemaCourseDB TO DISK=N'$sqlPath' WITH COPY_ONLY,CHECKSUM; RESTORE VERIFYONLY FROM DISK=N'$sqlPath' WITH CHECKSUM;"
if ($LASTEXITCODE -ne 0) { throw 'Backup or verification failed. Check SQL Server service permissions on the backups directory.' }
Write-Host "Verified backup: $backupFile"
