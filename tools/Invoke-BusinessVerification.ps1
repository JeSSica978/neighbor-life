[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NeighborLife.Common.ps1')
$businessContainer = docker inspect neighborlife-loadtest-mysql | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $businessContainer.Config.Labels.'com.docker.compose.project' -ne 'neighborlife-loadtest') { throw 'Dedicated verification MySQL container identity mismatch.' }
$businessPasswordLine = $businessContainer.Config.Env | Where-Object { $_.StartsWith('LT_MYSQL_APP_PASSWORD=') }
if (!$businessPasswordLine) { throw 'Dedicated app password is unavailable.' }
$businessEnvironment = @{
  JAVA_HOME = $script:NeighborLifeJavaHome
  LT_MYSQL_PORT = '3307'; LT_REDIS_PORT = '6380'; LT_KAFKA_PORT = '9093'
  LT_MYSQL_APP_PASSWORD = $businessPasswordLine.Substring('LT_MYSQL_APP_PASSWORD='.Length)
  LT_RUN_ID = 'business-verification'
}
$businessPrevious = @{}
Push-Location -LiteralPath $script:NeighborLifeRoot
try {
  foreach ($businessName in $businessEnvironment.Keys) {
    $businessPrevious[$businessName] = [Environment]::GetEnvironmentVariable($businessName, 'Process')
    [Environment]::SetEnvironmentVariable($businessName, $businessEnvironment[$businessName], 'Process')
  }
  & (Get-NeighborLifeMaven) -B -ntp '-Pbusiness-verification' '-Dtest=BusinessClosureIntegrationTest' '-DargLine=-Duser.timezone=UTC -Dfile.encoding=UTF-8' test
  $businessExit = $LASTEXITCODE
} finally {
  foreach ($businessName in $businessPrevious.Keys) { [Environment]::SetEnvironmentVariable($businessName, $businessPrevious[$businessName], 'Process') }
  Pop-Location
}
exit $businessExit
