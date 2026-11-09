$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Start-Transcript -Path (Join-Path $projectRoot '.tools/sql-login-setup.log') -Force
try {
    $tcpPath='HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL17.MSSQLSERVER\MSSQLServer\SuperSocketNetLib\Tcp'
    if ((Get-ItemProperty -LiteralPath $tcpPath).ListenOnAllIPs -ne 0) { throw 'Expected local-only TCP configuration.' }
    foreach ($key in Get-ChildItem -LiteralPath $tcpPath | Where-Object { $_.PSChildName -ne 'IPAll' }) {
        $ip=Get-ItemProperty -LiteralPath $key.PSPath
        if ($ip.Enabled -eq 1 -and $ip.IpAddress -ne '127.0.0.1') { throw 'Unexpected non-loopback SQL listener.' }
    }
    Set-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL17.MSSQLSERVER\MSSQLServer' -Name LoginMode -Value 2
    $wasRunning=@(Get-Service -Name MSSQLFDLauncher,MSSQLLaunchpad,SQLSERVERAGENT -ErrorAction SilentlyContinue | Where-Object Status -eq Running | Select-Object -ExpandProperty Name)
    Restart-Service -Name MSSQLSERVER -Force
    foreach ($service in $wasRunning) { Start-Service -Name $service }
    $connection=New-Object System.Data.SqlClient.SqlConnection('Server=localhost;Database=master;Integrated Security=True;TrustServerCertificate=True;Connect Timeout=20')
    $connection.Open()
    try {
        $check=$connection.CreateCommand()
        $check.CommandText="SELECT COUNT(*) FROM sys.server_principals WHERE name IN ('cinema_app_local','cinema_test_local')"
        if ([int]$check.ExecuteScalar() -ne 0) { throw 'Project SQL accounts already exist; refusing to replace credentials.' }
        $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
        $utf8=New-Object System.Text.UTF8Encoding($false)
        foreach ($kind in @('app','test')) {
            $bytes=New-Object byte[] 32
            $rng.GetBytes($bytes)
            $secret='C!'+[Convert]::ToBase64String($bytes)
            $login='cinema_'+$kind+'_local'
            $command=$connection.CreateCommand()
            $command.CommandText="CREATE LOGIN [$login] WITH PASSWORD=N'$secret', CHECK_POLICY=ON, DEFAULT_DATABASE=[CinemaCourseDB]; USE [CinemaCourseDB]; CREATE USER [$login] FOR LOGIN [$login];"
            if ($kind -eq 'app') {
                $command.CommandText+="ALTER ROLE db_datareader ADD MEMBER [$login]; GRANT EXECUTE ON SCHEMA::dbo TO [$login]; GRANT UPDATE (PasswordHash) ON dbo.AppUsers TO [$login];"
                $configPath=Join-Path $projectRoot 'config/application.properties'
            } else {
                $command.CommandText+="ALTER ROLE db_owner ADD MEMBER [$login];"
                $configPath=Join-Path $projectRoot '.tools/test.properties'
            }
            [void]$command.ExecuteNonQuery()
            $config="db.url=jdbc:sqlserver://127.0.0.1:1433;databaseName=CinemaCourseDB;encrypt=true;trustServerCertificate=true`ndb.user=$login`ndb.password=$secret`n"
            [System.IO.File]::WriteAllText($configPath,$config,$utf8)
            $connection.ChangeDatabase('master')
        }
        $rng.Dispose()
    } finally { $connection.Dispose() }
    'SUCCESS: local app and test accounts created; no server roles granted; secrets saved only in ignored config files.'
    Get-NetTCPConnection -LocalPort 1433 -State Listen | Format-Table LocalAddress,LocalPort
} finally { Stop-Transcript }
