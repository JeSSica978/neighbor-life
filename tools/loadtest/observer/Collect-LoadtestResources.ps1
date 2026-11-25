[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)] [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$')] [string]$RunId,
    [switch]$Execute,
    [int]$BackendPid = 0, [ValidateRange(1,3600)] [int]$DurationSeconds = 250,
    [string]$BackendLog = ''
)
$ErrorActionPreference = 'Stop'
if (!$Execute) { Write-Output 'DRY_RUN: resource collection disabled; all runtime metrics UNKNOWN.'; exit 0 }
$resourceProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$resourceDirectory = Join-Path $resourceProject "target\loadtest-private\$RunId"
$resourceManifestPath = Join-Path $resourceDirectory 'manifest.json'
if (!(Test-Path -LiteralPath $resourceManifestPath)) { throw 'Create and verify an isolated fixture first.' }
$resourceManifest = Get-Content -LiteralPath $resourceManifestPath -Raw | ConvertFrom-Json
if ($resourceManifest.runId -ne $RunId -or $resourceManifest.environment.database -ne 'hmdp_loadtest') { throw 'Manifest isolation mismatch.' }
if ($BackendPid -gt 0) {
    $resourceBackendPort = ([Uri]$resourceManifest.environment.baseUrl).Port
    $resourceOwner = @(Get-NetTCPConnection -State Listen -LocalPort $resourceBackendPort -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($resourceOwner -notcontains $BackendPid) { throw 'BackendPid does not own the isolated backend port.' }
}
if ($BackendLog) {
    $resourceLogPath = [IO.Path]::GetFullPath($BackendLog)
    if (!$resourceLogPath.StartsWith([IO.Path]::GetFullPath($resourceDirectory) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Only a run-scoped private backend log can be inspected.' }
}
$resourceOutput = Join-Path $resourceDirectory ("resources-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + '.jsonl')
$resourceStart = Get-Date
$resourceJdk = 'C:\Program Files\Java\jdk-11\bin'
while (((Get-Date) - $resourceStart).TotalSeconds -lt $DurationSeconds) {
    $resourceRow = [ordered]@{ timeMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); hostCpuPercent='UNKNOWN'; hostTotalMemoryKiB='UNKNOWN'; hostFreeMemoryKiB='UNKNOWN'; jvmHeap='UNKNOWN'; jvmGc='UNKNOWN'; mysqlPool='UNKNOWN: no authenticated Hikari JMX/Actuator endpoint currently exposed'; redisMemory='UNKNOWN'; docker='UNKNOWN'; publisherErrorCounters='UNKNOWN'; kafkaLag='UNKNOWN: use pipeline observer' }
    try { $resourceCpu = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"; $resourceRow.hostCpuPercent=$resourceCpu.PercentProcessorTime } catch { }
    try { $resourceOs = Get-CimInstance Win32_OperatingSystem; $resourceRow.hostTotalMemoryKiB=$resourceOs.TotalVisibleMemorySize; $resourceRow.hostFreeMemoryKiB=$resourceOs.FreePhysicalMemory } catch { }
    if ($BackendPid -gt 0) {
        try { $resourceHeap = & (Join-Path $resourceJdk 'jcmd.exe') $BackendPid 'GC.heap_info' 2>$null; if ($LASTEXITCODE -eq 0) { $resourceRow.jvmHeap=$resourceHeap } } catch { }
        try { $resourceGc = & (Join-Path $resourceJdk 'jstat.exe') '-gc' $BackendPid 2>$null; if ($LASTEXITCODE -eq 0) { $resourceRow.jvmGc=$resourceGc } } catch { }
    }
    try { $resourceRedis = & docker exec neighborlife-loadtest-redis redis-cli INFO memory 2>$null; if ($LASTEXITCODE -eq 0) { $resourceRow.redisMemory=@($resourceRedis | Where-Object { $_ -match '^(used_memory|used_memory_rss|maxmemory|mem_fragmentation_ratio):' }) } } catch { }
    try { $resourceDocker = & docker stats --no-stream --format '{{json .}}' neighborlife-loadtest-mysql neighborlife-loadtest-redis neighborlife-loadtest-kafka 2>$null; if ($LASTEXITCODE -eq 0) { $resourceRow.docker=$resourceDocker } } catch { }
    if ($BackendLog -and (Test-Path -LiteralPath $resourceLogPath)) {
        # Tail only counters, never output raw SQL/login/exception lines.
        $resourceLogTail = Get-Content -LiteralPath $resourceLogPath -Tail 5000
        $resourceRow.publisherErrorCounters=@{scope='last 5000 lines, snapshot count (not cumulative)'; outboxRetry=@($resourceLogTail | Select-String 'SECKILL_OUTBOX_RETRY').Count; outboxUnavailable=@($resourceLogTail | Select-String 'SECKILL_OUTBOX_UNAVAILABLE').Count; consumerRetry=@($resourceLogTail | Select-String 'SECKILL_CONSUME_RETRY').Count}
    }
    ($resourceRow | ConvertTo-Json -Depth 6 -Compress) | Add-Content -LiteralPath $resourceOutput -Encoding UTF8
    Start-Sleep -Milliseconds 1000
}
Write-Output "RESOURCE_COLLECTION_FINISHED runId=$RunId; unavailable fields remain UNKNOWN."
