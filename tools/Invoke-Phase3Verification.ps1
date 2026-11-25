[CmdletBinding()]
param(
    [ValidateSet('Test','Prepare','Demo','Inspect','Submit','Reset','Cleanup')]
    [string]$Action = 'Test',
    [ValidateRange(0, 2)] [int]$VoucherIndex = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NeighborLife.Common.ps1')
$phase3Root = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $phase3Root
$phase3PreviousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $script:NeighborLifeJavaHome
    if ($Action -eq 'Test') {
        # Historical tests include bulk ID generation and mutation of existing shop cache keys.
        & (Get-NeighborLifeMaven) -B -ntp '-Dtest=Phase3IntegrationTest' test
    } else {
        $phase3Classpath = Join-Path $phase3Root 'target\dependency-health-classpath.txt'
        if (!(Test-Path -LiteralPath $phase3Classpath)) { throw 'Run Docker Check/Start first to resolve runtime dependencies.' }
        $phase3Dependencies = (Get-Content -LiteralPath $phase3Classpath -Raw).Trim()
        & (Join-Path $script:NeighborLifeJavaHome 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dlogback.configurationFile=tools/health-logback.xml' -cp $phase3Dependencies 'tools/Phase3Fixture.java' $Action $VoucherIndex
    }
    $phase3Exit = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $phase3PreviousJava
    Pop-Location
}
exit $phase3Exit
