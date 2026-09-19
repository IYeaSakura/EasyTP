# Compile the EasyTP RTP benchmark harness and deploy it into a local Paper server.
#
# Prerequisites: the EasyTP jar must be built first (mvn clean package), and the
# target server directory must contain a Paper jar. Pass its path as the first
# argument; the default matches the layout used for the measurements in the
# accompanying article and will not exist in a fresh clone.
#
#   .\benchmarks\build_bench.ps1 [-ServerDir "path\to\paper-server"] [-FreshWorld]

param(
    [string]$ServerDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'test\PaperMC 26.2 TEST'),
    [switch]$FreshWorld
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$srv  = $ServerDir

if (-not (Test-Path $srv)) {
    throw "server directory not found: $srv`nPass one explicitly: .\build_bench.ps1 -ServerDir <path>"
}
if (-not (Get-ChildItem $srv -Filter 'paper-*.jar' -ErrorAction SilentlyContinue)) {
    throw "no paper-*.jar found in $srv"
}

Write-Host '--- exporting compile classpath ---'
$cpFile = Join-Path $repo 'target\bench-cp.txt'
Remove-Item $cpFile -Force -ErrorAction SilentlyContinue
Push-Location $repo
try {
    # Resolve normally so a machine without the artifacts cached can download them;
    # fall back to offline mode, which is what a fully-cached build needs.
    mvn -q dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    if (-not (Test-Path $cpFile)) {
        Write-Host '    (no network or resolution failed, retrying offline)'
        mvn -o -q dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    }
} finally {
    Pop-Location
}
if (-not (Test-Path $cpFile)) {
    throw "could not resolve the compile classpath; run 'mvn dependency:build-classpath' manually to see why"
}
$cp = (Get-Content $cpFile -Raw).Trim() + ';' + (Join-Path $repo 'target\easytp-1.1.0.jar')

Write-Host '--- compiling ---'
$build = Join-Path $PSScriptRoot 'build'
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build | Out-Null
$src = (Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java).FullName
& "$env:JAVA_HOME\bin\javac.exe" @('-encoding', 'UTF-8', '-nowarn', '-cp', $cp, '-d', $build, $src)
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

Copy-Item (Join-Path $PSScriptRoot 'plugin.yml') $build -Force
$jar = Join-Path $PSScriptRoot 'EasyTPBench.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
& "$env:JAVA_HOME\bin\jar.exe" --create --file $jar -C $build .
if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }

Write-Host '--- deploying ---'
New-Item -ItemType Directory -Force -Path (Join-Path $srv 'plugins') | Out-Null
Copy-Item $jar (Join-Path $srv 'plugins') -Force
Copy-Item (Join-Path $repo 'target\easytp-1.1.0.jar') `
          (Join-Path $srv 'plugins\EasyTP-1.1.0.jar') -Force

Write-Host '--- clearing previous results and plugin state ---'
Remove-Item (Join-Path $srv 'bench-out') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $srv 'bench-out') | Out-Null
# The spiral index is persisted in data.db. A stale index makes the next run
# start mid-cycle and wrap, which tears a hole in the radial coverage and
# silently invalidates the measured distribution.
Remove-Item (Join-Path $srv 'plugins\EasyTP\data.db') -Force -ErrorAction SilentlyContinue
# Chunk acquisition costs an order of magnitude more when the terrain has to be
# generated. Delete the level directories to measure that case; keep them to
# measure a pre-generated world.
if ($FreshWorld) {
    $level = 'bench'
    $props = Join-Path $srv 'server.properties'
    if (Test-Path $props) {
        $line = Select-String -Path $props -Pattern '^level-name=(.+)$' | Select-Object -First 1
        if ($line) { $level = $line.Matches[0].Groups[1].Value.Trim() }
    }
    Write-Host "--- deleting world '$level' (fresh-world run) ---"
    foreach ($suffix in @('', '_nether', '_the_end')) {
        Remove-Item (Join-Path $srv ($level + $suffix)) -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'Deployed. Run the server with:'
Write-Host "  cd `"$srv`""
Write-Host "  java -Xms2G -Xmx2G -jar $(Split-Path -Leaf (Get-ChildItem $srv -Filter 'paper-*.jar' | Select-Object -First 1).FullName) --nogui"
Write-Host 'The harness shuts the server down when it finishes; results land in bench-out/.'
