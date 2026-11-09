. "$PSScriptRoot/Common.ps1"
& "$PSScriptRoot/Build.ps1"
New-Item -ItemType Directory -Path 'build/test-classes' -Force | Out-Null
$tests=@(Get-ChildItem -LiteralPath 'src/test/java' -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& $javacExe --release 17 -encoding UTF-8 -cp 'build/classes;lib/*' -d 'build/test-classes' @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
$configArgument = @()
if (Test-Path -LiteralPath '.tools/test.properties') { $configArgument = @('-Dcinema.config=.tools/test.properties') }
& $javaExe '--enable-native-access=ALL-UNNAMED' '-Dfile.encoding=UTF-8' @configArgument "-Djava.library.path=$projectRoot/lib" -cp 'build/classes;build/test-classes;lib/*' cn.edu.cinema.IntegrationTest
if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed.' }
& $javaExe '--enable-native-access=ALL-UNNAMED' '-Dfile.encoding=UTF-8' "-Djava.library.path=$projectRoot/lib" -cp 'build/classes;build/test-classes;lib/*' cn.edu.cinema.CustomerIntegrationTest
if ($LASTEXITCODE -ne 0) { throw 'Customer integration tests failed.' }
