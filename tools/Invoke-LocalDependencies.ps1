[CmdletBinding()]
param(
    [ValidateSet('Start', 'Status', 'Check', 'Stop')]
    [string]$Action = 'Status',
    [ValidateRange(10, 600)]
    [int]$EngineTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NeighborLife.Common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'docker\compose.yaml'
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$dockerPath = if ($dockerCommand) { $dockerCommand.Source } else {
    Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
}
if (!(Test-Path -LiteralPath $dockerPath)) {
    throw 'Docker Desktop is not installed.'
}
function Test-DockerEngine {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $dockerPath
    $info.Arguments = 'info --format {{.ServerVersion}}'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo = $info
    try {
        [void]$probe.Start()
        if (!$probe.WaitForExit(5000)) { $probe.Kill(); return $false }
        return $probe.ExitCode -eq 0
    } finally { $probe.Dispose() }
}
if (!(Test-DockerEngine)) {
    if ($Action -ne 'Start') { throw 'Docker Engine is unavailable. Run the Docker Start task or open Docker Desktop.' }
    $desktopPath = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (!(Test-Path -LiteralPath $desktopPath)) { throw 'Docker Desktop executable is missing. No installation was attempted.' }
    Write-Host 'Docker Engine is not ready. Starting the installed Docker Desktop and waiting...'
    Start-Process -FilePath $desktopPath -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds($EngineTimeoutSeconds)
    while (!(Test-DockerEngine)) {
        if ((Get-Date) -ge $deadline) { throw 'Docker Engine readiness timed out. Open Docker Desktop and inspect its WSL/engine error; then retry.' }
        Start-Sleep -Seconds 2
    }
}

# Reuse the configured datasource password in memory; never print it or put it in argv.
$previousPassword = $env:NEIGHBORLIFE_MYSQL_ROOT_PASSWORD
try {
    if (!$env:NEIGHBORLIFE_MYSQL_ROOT_PASSWORD) {
        if ($env:SPRING_DATASOURCE_PASSWORD) {
            $env:NEIGHBORLIFE_MYSQL_ROOT_PASSWORD = $env:SPRING_DATASOURCE_PASSWORD
        } else {
            $yaml = Get-Content -LiteralPath (Join-Path $projectRoot 'src\main\resources\application.yaml') -Raw
            $passwordMatch = [regex]::Match($yaml, '(?ms)^  datasource:\s*\r?\n.*?^    password: *([^\r\n]+)')
            if (!$passwordMatch.Success) {
                throw 'Set NEIGHBORLIFE_MYSQL_ROOT_PASSWORD locally before running this script.'
            }
            $configuredPassword = $passwordMatch.Groups[1].Value.Trim()
            if ($configuredPassword -match '["''#$\{\}]') {
                throw 'Complex YAML passwords require NEIGHBORLIFE_MYSQL_ROOT_PASSWORD to be set locally.'
            }
            $env:NEIGHBORLIFE_MYSQL_ROOT_PASSWORD = $configuredPassword
        }
    }
    & $dockerPath compose -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
    if ($Action -eq 'Stop') {
        & $dockerPath compose -f $composeFile stop mysql redis kafka
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the project containers.' }
        Write-Host 'DOCKER_STOP_PASS project=neighborlife-dev (persistent volumes retained; Docker Desktop remains running)'
    } elseif ($Action -eq 'Start') {
        # Refuse to occupy a port belonging to another application.
        $existingIds = @(& $dockerPath compose -f $composeFile ps -q)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect existing development containers.' }
        if ($existingIds.Count -eq 0) {
            $usedPorts = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
                Where-Object { $_.Port -in 3306, 6379, 9092 }
            if ($usedPorts) { throw 'A dependency port is occupied. Inspect the existing service before starting containers.' }
        }
        & $dockerPath compose -f $composeFile up -d --wait --wait-timeout 300
        if ($LASTEXITCODE -ne 0) { throw 'Development dependencies did not become healthy.' }
        & $dockerPath compose -f $composeFile exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --create --if-not-exists --topic seckill-voucher-order --partitions 1 --replication-factor 1
        if ($LASTEXITCODE -ne 0) { throw 'Kafka topic setup failed.' }
        # Order failures are retried without skipping; retention must not silently expire unconsumed orders.
        & $dockerPath compose -f $composeFile exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:19092 --entity-type topics --entity-name seckill-voucher-order --alter --add-config 'retention.ms=-1,retention.bytes=-1'
        if ($LASTEXITCODE -ne 0) { throw 'Kafka order topic retention configuration failed.' }
    }
    & $dockerPath compose -f $composeFile ps
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect development containers.' }
    if ($Action -in 'Start', 'Check') {
        Invoke-NeighborLifeHealth
        if ($Action -eq 'Start') { Write-Host 'DOCKER_START_PASS project=neighborlife-dev' }
    }
} finally {
    $env:NEIGHBORLIFE_MYSQL_ROOT_PASSWORD = $previousPassword
}
