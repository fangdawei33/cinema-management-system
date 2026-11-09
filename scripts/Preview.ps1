. "$PSScriptRoot/Common.ps1"
& "$PSScriptRoot/Build.ps1"
New-Item -ItemType Directory -Path 'build/test-classes' -Force | Out-Null
& $javacExe --release 17 -encoding UTF-8 -cp 'build/classes;lib/*' -d 'build/test-classes' 'src/test/java/cn/edu/cinema/UiSmoke.java' 'src/test/java/cn/edu/cinema/CustomerUiSmoke.java'
if ($LASTEXITCODE -ne 0) { throw 'Preview compilation failed.' }
& $javaExe '--enable-native-access=ALL-UNNAMED' "-Djava.library.path=$projectRoot/lib" -cp 'build/classes;build/test-classes;lib/*' cn.edu.cinema.UiSmoke
if ($LASTEXITCODE -ne 0) { throw 'UI preview failed.' }
& $javaExe '--enable-native-access=ALL-UNNAMED' "-Djava.library.path=$projectRoot/lib" -cp 'build/classes;build/test-classes;lib/*' cn.edu.cinema.CustomerUiSmoke
if ($LASTEXITCODE -ne 0) { throw 'Customer UI preview failed.' }
