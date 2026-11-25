[CmdletBinding()]
param(
    [ValidateSet('Compile', 'Run', 'CheckPort', 'Stop')]
    [string]$Action = 'Run'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NeighborLife.Common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Action -eq 'CheckPort') {
    Assert-NeighborLifePortFree
    exit 0
}
if ($Action -eq 'Stop') {
    $port = Get-NeighborLifePort
    $listeners = @(Get-NeighborLifeListeners -Port $port)
    foreach ($owner in ($listeners | Select-Object -ExpandProperty OwningProcess -Unique)) {
        if (!(Test-NeighborLifeProcess (Get-CimInstance Win32_Process -Filter "ProcessId = $owner"))) {
            throw "Port $port belongs to another process (PID $owner). Refusing to terminate it."
        }
    }
    $backends = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { Test-NeighborLifeProcess $_ })
    foreach ($backend in $backends) {
        $fresh = Get-CimInstance Win32_Process -Filter "ProcessId = $($backend.ProcessId)"
        if (!$fresh) { continue }
        if ($fresh.CreationDate -ne $backend.CreationDate -or !(Test-NeighborLifeProcess $fresh)) { throw 'Process identity changed. Refusing to stop it.' }
        Stop-Process -Id $backend.ProcessId -ErrorAction Stop
        Write-Host "Stopped verified NeighborLife Java PID $($backend.ProcessId)."
    }
    if (@(Get-NeighborLifeListeners -Port $port).Count) { throw "Backend stop did not release port $port. Inspect the listener manually." }
    Write-Host "BACKEND_STOP_PASS port=$port (Docker containers and volumes unchanged)"
    exit 0
}
$mavenPath = Get-NeighborLifeMaven
if ($Action -eq 'Run') {
    Assert-NeighborLifePortFree
    & (Join-Path $PSScriptRoot 'Invoke-LocalDependencies.ps1') -Action Start
    # Recheck after Docker startup, since another terminal could have launched the backend.
    Assert-NeighborLifePortFree
}

Push-Location -LiteralPath $projectRoot
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $script:NeighborLifeJavaHome
    Write-Host "Project: $projectRoot"
    Write-Host "Maven: $mavenPath"
    if ($Action -eq 'Compile') {
        & $mavenPath -B -ntp compile
    } else {
        & $mavenPath -B -ntp spring-boot:run '-Dspring-boot.run.profiles=default'
    }
    $mavenExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
exit $mavenExitCode
