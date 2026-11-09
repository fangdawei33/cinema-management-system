. "$PSScriptRoot/Common.ps1"
if (!(Test-Path -LiteralPath 'build/cinema.jar')) { & "$PSScriptRoot/Build.ps1" }
& $javaExe '--enable-native-access=ALL-UNNAMED' "-Djava.library.path=$projectRoot/lib" -cp 'build/cinema.jar;lib/*' cn.edu.cinema.App
