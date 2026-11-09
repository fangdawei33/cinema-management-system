. "$PSScriptRoot/Common.ps1"
$outputDir = Join-Path $projectRoot 'build/classes'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& $javacExe --release 17 -encoding UTF-8 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
& $jarExe --create --file (Join-Path $projectRoot 'build/cinema.jar') --main-class cn.edu.cinema.App -C $outputDir .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
Write-Host 'Build complete: build/cinema.jar'
