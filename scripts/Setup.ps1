. "$PSScriptRoot/Common.ps1"
New-Item -ItemType Directory -Path 'lib' -Force | Out-Null
$baseUrl = 'https://repo.maven.apache.org/maven2/com/microsoft/sqlserver'
$files = @{
    'mssql-jdbc-13.4.0.jre11.jar' = "$baseUrl/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar"
    'mssql-jdbc_auth-13.4.0.x64.dll' = "$baseUrl/mssql-jdbc_auth/13.4.0.x64/mssql-jdbc_auth-13.4.0.x64.dll"
}
foreach ($file in $files.Keys) {
    $target = Join-Path 'lib' $file
    if (!(Test-Path -LiteralPath $target)) { Invoke-WebRequest -Uri $files[$file] -OutFile $target -UseBasicParsing }
}
if (!(Test-Path -LiteralPath 'config/application.properties')) {
    Copy-Item -LiteralPath 'config/application.example.properties' -Destination 'config/application.properties'
}
& "$PSScriptRoot/Build.ps1"
