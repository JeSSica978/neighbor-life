[CmdletBinding()]
param(
    [ValidateSet('prepare','reset','cleanup','inspect','observe')] [string]$Action = 'prepare',
    [Parameter(Mandatory=$true)] [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$')] [string]$RunId,
    [switch]$Execute,
    [int]$Users = 100, [int]$Vouchers = 1, [int]$Stock = 0, [int]$ExpectedAttempts = 0,
    [int]$DurationSeconds = 250, [int]$PollMilliseconds = 250
)
$ErrorActionPreference = 'Stop'
$loadtestProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$loadtestJava = 'C:\Program Files\Java\jdk-11\bin'
$loadtestClasspathFile = Join-Path $loadtestProject 'target\dependency-health-classpath.txt'
if (!(Test-Path -LiteralPath $loadtestClasspathFile)) { throw 'Existing runtime classpath missing. Resolve with Maven dependency:build-classpath separately; do not use clean or default Docker Start.' }
$loadtestClasspath = (Get-Content -LiteralPath $loadtestClasspathFile -Raw).Trim()
$loadtestClasses = Join-Path $loadtestProject 'target\loadtest-tool-classes'
New-Item -ItemType Directory -Path $loadtestClasses -Force | Out-Null
$loadtestSources = @(Join-Path $PSScriptRoot 'LoadtestEnvironment.java'; Join-Path $PSScriptRoot 'fixture\LoadtestFixture.java'; Join-Path $PSScriptRoot 'observer\LoadtestObserver.java')
& (Join-Path $loadtestJava 'javac.exe') '-encoding' 'UTF-8' '-cp' $loadtestClasspath '-d' $loadtestClasses @loadtestSources
if ($LASTEXITCODE -ne 0) { throw 'Loadtest tool compilation failed; no fixture action was executed.' }
$loadtestArgs = @($Action, '--run-id', $RunId)
if ($Execute) {
    $loadtestPrivateRoot = Join-Path $loadtestProject 'target\loadtest-private'
    $loadtestPrivate = [IO.Path]::GetFullPath((Join-Path $loadtestPrivateRoot $RunId))
    if (!$loadtestPrivate.StartsWith([IO.Path]::GetFullPath($loadtestPrivateRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Private output path escaped target.' }
    if ((Get-Item -LiteralPath (Join-Path $loadtestProject 'target')).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a linked target directory.' }
    foreach ($loadtestSecureDirectory in @($loadtestPrivateRoot, $loadtestPrivate)) {
        New-Item -ItemType Directory -Path $loadtestSecureDirectory -Force | Out-Null
        if ((Get-Item -LiteralPath $loadtestSecureDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a linked private directory.' }
        $loadtestAcl = New-Object System.Security.AccessControl.DirectorySecurity
        $loadtestAcl.SetAccessRuleProtection($true, $false)
        $loadtestUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
        foreach ($loadtestSid in @($loadtestUser, (New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'), (New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'))) {
            $loadtestRule = New-Object System.Security.AccessControl.FileSystemAccessRule($loadtestSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
            $loadtestAcl.AddAccessRule($loadtestRule)
        }
        Set-Acl -LiteralPath $loadtestSecureDirectory -AclObject $loadtestAcl
    }
    $loadtestArgs += '--execute'
}
if ($Action -eq 'observe') {
    $loadtestMain = 'loadtest.LoadtestObserver'
    $loadtestArgs += @('--duration-seconds', "$DurationSeconds", '--poll-ms', "$PollMilliseconds")
} else {
    $loadtestMain = 'loadtest.LoadtestFixture'
    $loadtestArgs += @('--users', "$Users", '--vouchers', "$Vouchers")
    if ($Stock -gt 0) { $loadtestArgs += @('--stock', "$Stock") }
    if ($ExpectedAttempts -gt 0) { $loadtestArgs += @('--expected-attempts', "$ExpectedAttempts") }
}
Push-Location -LiteralPath $loadtestProject
try {
    & (Join-Path $loadtestJava 'java.exe') '-Dfile.encoding=UTF-8' '-Dlogback.configurationFile=tools/health-logback.xml' '-cp' ($loadtestClasses + ';' + $loadtestClasspath) $loadtestMain @loadtestArgs
    $loadtestExit = $LASTEXITCODE
} finally { Pop-Location }
exit $loadtestExit
