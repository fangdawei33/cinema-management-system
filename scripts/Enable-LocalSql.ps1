$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Start-Transcript -Path (Join-Path $projectRoot '.tools/sql-network.log') -Force
try {
    # Explicit local-only scope; no firewall changes and no remote interfaces.
    $tcpPath = 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL17.MSSQLSERVER\MSSQLServer\SuperSocketNetLib\Tcp'
    $ipKeys = Get-ChildItem -LiteralPath $tcpPath | Where-Object { $_.PSChildName -ne 'IPAll' }
    $loopback = @($ipKeys | Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath).IpAddress -eq '127.0.0.1' })
    if ($loopback.Count -ne 1) { throw 'Expected one IPv4 loopback entry; configuration unchanged.' }
    Set-ItemProperty -LiteralPath $tcpPath -Name ListenOnAllIPs -Value 0
    foreach ($key in $ipKeys) { Set-ItemProperty -LiteralPath $key.PSPath -Name Enabled -Value 0 }
    Set-ItemProperty -LiteralPath $loopback[0].PSPath -Name TcpPort -Value '1433'
    Set-ItemProperty -LiteralPath $loopback[0].PSPath -Name TcpDynamicPorts -Value ''
    Set-ItemProperty -LiteralPath $loopback[0].PSPath -Name Enabled -Value 1
    Set-ItemProperty -LiteralPath $tcpPath -Name Enabled -Value 1
    $wasRunning = @(Get-Service -Name MSSQLFDLauncher,MSSQLLaunchpad,SQLSERVERAGENT -ErrorAction SilentlyContinue | Where-Object Status -eq Running | Select-Object -ExpandProperty Name)
    Restart-Service -Name MSSQLSERVER -Force
    foreach ($service in $wasRunning) { Start-Service -Name $service }
    $listeners = @(Get-NetTCPConnection -LocalPort 1433 -State Listen -ErrorAction SilentlyContinue)
    if ($listeners | Where-Object { $_.LocalAddress -ne '127.0.0.1' }) { throw 'Unexpected listener address; review SQL configuration.' }
    $listeners | Format-Table LocalAddress,LocalPort
    'SUCCESS: SQL Server TCP is configured for 127.0.0.1 only.'
} finally { Stop-Transcript }
