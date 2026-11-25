[CmdletBinding()]
param(
  [ValidateSet('Inspect','Migrate','GrantAdmin','GrantMerchant','RevokeAdmin','RevokeMerchant')]
  [string]$Action='Inspect',
  [ValidatePattern('^1[3-9][0-9]{9}$')] [string]$Phone,
  [long]$ShopId=0
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'NeighborLife.Common.ps1')
$businessContainer=docker inspect neighborlife-dev-mysql-1 | ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $businessContainer.Config.Labels.'com.docker.compose.project' -ne 'neighborlife-dev'){throw 'Development MySQL identity mismatch'}
function Invoke-BusinessSql([string]$Sql) {
  $rows=@($Sql | docker exec -i neighborlife-dev-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --default-character-set=utf8mb4 --batch --skip-column-names hmdp')
  if($LASTEXITCODE -ne 0){throw 'Business SQL operation failed; backend must stay stopped until repaired'}
  return $rows
}
function Get-BusinessScalar([string]$Sql){$rows=@(Invoke-BusinessSql $Sql);return [long]$rows[-1]}
if($Action -eq 'Inspect') {
  Invoke-BusinessSql "SELECT CONCAT('orders=',COUNT(*),', pending=',SUM(status=1)) FROM tb_voucher_order; SELECT CONCAT('phase5_columns=',COUNT(*)) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND column_name IN ('request_id','seckill_voucher_id'); SELECT CONCAT('phase6_tables=',COUNT(*)) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('tb_order_detail','tb_payment','tb_user_voucher','tb_operation_audit');"
  exit 0
}
if($Action -ne 'Migrate') {
  if(!$Phone){throw 'Specify the phone of an already registered user; permissions are never inferred'}
  if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tb_operation_audit'") -ne 1){throw 'Run the migration first'}
  $businessUser=Get-BusinessScalar "SELECT COALESCE(MAX(id),0) FROM tb_user WHERE phone='$Phone'"
  if($businessUser -eq 0){throw 'User must log in/register before granting a role'}
  if($Action -in @('GrantMerchant','RevokeMerchant')){
    if($ShopId -le 0 -or (Get-BusinessScalar "SELECT COUNT(*) FROM tb_shop WHERE id=$ShopId") -ne 1){throw 'Specify an existing ShopId'}
    $businessMutation=if($Action -eq 'GrantMerchant'){"INSERT INTO tb_shop_member(shop_id,user_id) VALUES($ShopId,$businessUser) ON DUPLICATE KEY UPDATE role='OWNER';"}else{"DELETE FROM tb_shop_member WHERE shop_id=$ShopId AND user_id=$businessUser;"}
  }else{
    $businessMutation=if($Action -eq 'GrantAdmin'){"INSERT IGNORE INTO tb_admin(user_id) VALUES($businessUser);"}else{"DELETE FROM tb_admin WHERE user_id=$businessUser;"}
  }
  # operator_id=0 表示本机维护命令；网页操作记录实际登录用户。
  Invoke-BusinessSql "START TRANSACTION; $businessMutation INSERT INTO tb_operation_audit(id,operator_id,action,resource_id) VALUES(UUID(),0,'LOCAL_$Action','$businessUser`:$ShopId'); COMMIT;" | Out-Null
  Write-Host "ROLE_UPDATED action=$Action userId=$businessUser shopId=$ShopId (refresh the user center)"
  exit 0
}
Assert-NeighborLifePortFree
if(@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { Test-NeighborLifeProcess $_ }).Count){throw 'Stop the verified project backend before migration'}
if((Get-BusinessScalar "SELECT COUNT(*) FROM tb_voucher_order o LEFT JOIN tb_voucher v ON v.id=o.voucher_id WHERE v.id IS NULL OR v.type NOT IN(0,1)") -ne 0){throw 'Historical voucher references need manual review before migration'}
$businessBackup=Join-Path $script:NeighborLifeRoot ('backups\development-business-migration-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $businessBackup -ErrorAction Stop | Out-Null
# 备份只留在本机受限目录；SQL 数据不进入终端或聊天输出。
$businessAcl=New-Object System.Security.AccessControl.DirectorySecurity
$businessAcl.SetAccessRuleProtection($true,$false)
foreach($businessSid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'),(New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'))){
  $businessAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($businessSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
}
Set-Acl -LiteralPath $businessBackup -AclObject $businessAcl
$businessDump=docker exec neighborlife-dev-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --default-character-set=utf8mb4 --single-transaction --routines --triggers --set-gtid-purged=OFF hmdp'
if($LASTEXITCODE -ne 0 -or !$businessDump){throw 'Backup failed; migration was not started'}
$businessDump | Set-Content -LiteralPath (Join-Path $businessBackup 'before.sql') -Encoding utf8
$businessHistorySql="SELECT CONCAT_WS('|',id,user_id,voucher_id,status,COALESCE(pay_type,''),create_time,COALESCE(pay_time,''),COALESCE(use_time,''),COALESCE(refund_time,'')) FROM tb_voucher_order ORDER BY id;"
$businessHistory=@(Invoke-BusinessSql $businessHistorySql)
$businessHistory | Set-Content -LiteralPath (Join-Path $businessBackup 'historical-orders-before.txt') -Encoding utf8
if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_status_create'") -eq 0){Invoke-BusinessSql 'ALTER TABLE tb_voucher_order ADD INDEX idx_order_status_create(status,create_time);' | Out-Null}
if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tb_reliable_event'") -eq 0){
  $businessPhase4=Get-Content -LiteralPath (Join-Path $script:NeighborLifeRoot 'src\main\resources\db\migration\phase4-order-lifecycle-and-reliable-events.sql') -Raw -Encoding UTF8
  Invoke-BusinessSql ($businessPhase4.Substring($businessPhase4.IndexOf('CREATE TABLE'))) | Out-Null
}
foreach($businessColumn in @('request_id','seckill_voucher_id')){
  if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND column_name='$businessColumn'") -eq 0){
    $businessType=if($businessColumn -eq 'request_id'){'varchar(64) CHARACTER SET ascii COLLATE ascii_bin'}else{'bigint unsigned'}
    Invoke-BusinessSql "ALTER TABLE tb_voucher_order ADD COLUMN $businessColumn $businessType DEFAULT NULL;" | Out-Null
  }
}
foreach($businessIndex in @(@('uk_voucher_order_seckill','UNIQUE','user_id,seckill_voucher_id'),@('uk_voucher_order_request','UNIQUE','user_id,request_id'),@('idx_order_user_voucher','','user_id,voucher_id'))){
  if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='$($businessIndex[0])'") -eq 0){Invoke-BusinessSql "ALTER TABLE tb_voucher_order ADD $($businessIndex[1]) INDEX $($businessIndex[0])($($businessIndex[2]));" | Out-Null}
}
Invoke-BusinessSql 'UPDATE tb_voucher_order o JOIN tb_voucher v ON v.id=o.voucher_id SET o.seckill_voucher_id=o.voucher_id WHERE v.type=1 AND o.seckill_voucher_id IS NULL;' | Out-Null
if((Get-BusinessScalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_voucher_order_user_voucher'") -gt 0){Invoke-BusinessSql 'ALTER TABLE tb_voucher_order DROP INDEX uk_voucher_order_user_voucher;' | Out-Null}
Invoke-BusinessSql (Get-Content -LiteralPath (Join-Path $script:NeighborLifeRoot 'src\main\resources\db\migration\phase6-business-closure.sql') -Raw -Encoding UTF8) | Out-Null
Invoke-BusinessSql 'INSERT IGNORE INTO tb_voucher_policy(voucher_id,valid_days,ready) SELECT id,30,1 FROM tb_voucher; INSERT IGNORE INTO tb_shop_settings(shop_id,status) SELECT id,1 FROM tb_shop;' | Out-Null
# 只迁入 Redis 中实际存在、且用户仍在 DB 中的点赞关系。未知的历史汇总数保留，不能编造点赞用户。
$businessLikeImport=0
foreach($businessBlog in @(Invoke-BusinessSql 'SELECT id FROM tb_blog ORDER BY id;')){
  $businessBlog=[long]$businessBlog
  $businessPairs=docker exec neighborlife-dev-redis-1 redis-cli --json ZRANGE "blog:liked:$businessBlog" 0 -1 WITHSCORES | ConvertFrom-Json
  if($LASTEXITCODE -ne 0){throw 'Legacy like inspection failed; keep backend stopped'}
  for($businessI=0;$businessI -lt $businessPairs.Count;$businessI+=2){
    $businessLiker=[string]$businessPairs[$businessI];$businessScore=[string]$businessPairs[$businessI+1]
    if($businessLiker -notmatch '^[1-9][0-9]*$' -or $businessScore -notmatch '^[0-9]+(\.[0-9]+)?$'){continue}
    Invoke-BusinessSql "INSERT IGNORE INTO tb_blog_like(blog_id,user_id,create_time) SELECT $businessBlog,id,FROM_UNIXTIME($businessScore/1000) FROM tb_user WHERE id=$businessLiker;" | Out-Null
    $businessLikeImport++
  }
  Invoke-BusinessSql "UPDATE tb_blog SET liked=GREATEST(liked,(SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=$businessBlog)) WHERE id=$businessBlog;" | Out-Null
}
$businessHistoryAfter=@(Invoke-BusinessSql $businessHistorySql)
if(($businessHistory -join "`n") -cne ($businessHistoryAfter -join "`n")){throw 'Historical order fields changed; preserve backup and keep backend stopped for review'}
$businessManaged=Get-BusinessScalar 'SELECT COUNT(*) FROM tb_order_detail;'
Write-Host "BUSINESS_MIGRATION_PASS historicalOrders=$($businessHistory.Count) managedOrders=$businessManaged inspectedLikeRelations=$businessLikeImport backup=$businessBackup"
