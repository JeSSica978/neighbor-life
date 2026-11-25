$ErrorActionPreference = 'Stop'
$script:NeighborLifeRoot = Split-Path -Parent $PSScriptRoot
$script:NeighborLifeJavaHome = 'C:\Program Files\Java\jdk-11'

function Get-NeighborLifeMaven {
    $bundled = Join-Path $env:ProgramFiles 'JetBrains\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd'
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'Maven is unavailable. Check the existing Maven path in .vscode/settings.json.'
}

function Get-NeighborLifePort {
    if ($env:SERVER_PORT) { $port = $env:SERVER_PORT } else {
        $yaml = Get-Content -LiteralPath (Join-Path $script:NeighborLifeRoot 'src\main\resources\application.yaml') -Raw
        $match = [regex]::Match($yaml, '(?m)^  port:\s*(\d+)\s*$')
        if (!$match.Success) { throw 'Cannot resolve server.port from the existing application.yaml.' }
        $port = $match.Groups[1].Value
    }
    $number = 0
    if (![int]::TryParse($port, [ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw 'Invalid SERVER_PORT/server.port.' }
    return $number
}

function Get-NeighborLifeListeners {
    param([int]$Port)
    @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq $Port)
}

function Assert-NeighborLifePortFree {
    $port = Get-NeighborLifePort
    $listeners = @(Get-NeighborLifeListeners -Port $port)
    if ($listeners.Count) {
        $owners = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
        throw "Port $port is occupied (PID $owners). Stop the existing project backend, or inspect the owner manually. No process was terminated."
    }
    Write-Host "BACKEND_PORT_FREE_PASS port=$port"
}

function Test-NeighborLifeProcess {
    param($Process)
    if (!$Process -or $Process.Name -ne 'java.exe') { return $false }
    $arguments = [string]$Process.CommandLine
    # Java Debug may use a temporary @argfile to shorten its Windows classpath.
    foreach ($match in [regex]::Matches($arguments, '@(?:"([^"]+)"|([^\s]+))')) {
        $argumentFile = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
        if (Test-Path -LiteralPath $argumentFile -PathType Leaf) {
            $arguments += "`n" + (Get-Content -LiteralPath $argumentFile -Raw)
        }
    }
    $normalized = $arguments.Replace('/', '\').Replace('\\', '\')
    $mainPattern = '(?:^|[\s"])com\.hmdp\.HmDianPingApplication(?:$|[\s"])'
    $classesPattern = [regex]::Escape((Join-Path $script:NeighborLifeRoot 'target\classes')) + '(?:[;"\r\n]|$)'
    return [regex]::IsMatch($normalized, $mainPattern) -and
        [regex]::IsMatch($normalized, $classesPattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Invoke-NeighborLifeHealth {
    $java = Join-Path $script:NeighborLifeJavaHome 'bin\java.exe'
    if (!(Test-Path -LiteralPath $java)) { throw 'The configured project JDK 11 does not exist.' }
    $classpathFile = Join-Path $script:NeighborLifeRoot 'target\dependency-health-classpath.txt'
    $pom = Join-Path $script:NeighborLifeRoot 'pom.xml'
    $previousJavaHome = $env:JAVA_HOME
    Push-Location -LiteralPath $script:NeighborLifeRoot
    try {
        $env:JAVA_HOME = $script:NeighborLifeJavaHome
        if (!(Test-Path -LiteralPath $classpathFile) -or (Get-Item -LiteralPath $pom).LastWriteTime -gt (Get-Item -LiteralPath $classpathFile).LastWriteTime) {
            & (Get-NeighborLifeMaven) -B -ntp dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
            if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the root Maven runtime classpath for dependency checks.' }
        }
        $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
        & $java '-Dlogback.configurationFile=tools/health-logback.xml' -cp $classpath 'tools/DependencyHealthCheck.java'
        if ($LASTEXITCODE -ne 0) { throw 'Dependency connection checks failed. Backend launch was blocked; see the PASS/FAIL lines above.' }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
        Pop-Location
    }
}
