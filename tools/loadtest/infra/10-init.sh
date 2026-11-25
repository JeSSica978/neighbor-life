#!/bin/bash
set -euo pipefail
# Runs only on FIRST initialization of the dedicated volume. Never import the legacy dump.
# Hex encoding keeps arbitrary password characters out of shell/SQL interpolation.
app_hex=$(printf %s "$LT_MYSQL_APP_PASSWORD" | od -An -v -tx1 | tr -d ' \n')
observer_hex=$(printf %s "$LT_MYSQL_OBSERVER_PASSWORD" | od -An -v -tx1 | tr -d ' \n')
{
  # These statements and schema.sql share this single mysql process/session.
  # Keep every other SQL mode, including STRICT_TRANS_TABLES, unchanged.
  printf '%s\n' "SET @lt_init_sql_mode = @@SESSION.sql_mode;"
  printf '%s\n' "SET SESSION sql_mode = TRIM(BOTH ',' FROM REPLACE(REPLACE(CONCAT(',', @lt_init_sql_mode, ','), ',NO_ZERO_DATE,', ','), ',NO_ZERO_IN_DATE,', ','));"
  cat /opt/loadtest/schema.sql
  # Restore before account setup; global defaults and later application sessions are untouched.
  printf '%s\n' "SET SESSION sql_mode = @lt_init_sql_mode;"
  printf "SET @p=CONVERT(0x%s USING utf8mb4);\n" "$app_hex"
  printf "%s\n" "SET @q=CONCAT('CREATE USER ''lt_app''@''%'' IDENTIFIED WITH mysql_native_password BY ',QUOTE(@p)); PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;"
  printf "%s\n" "GRANT SELECT,INSERT,UPDATE,DELETE ON hmdp_loadtest.* TO 'lt_app'@'%';"
  printf "SET @p=CONVERT(0x%s USING utf8mb4);\n" "$observer_hex"
  printf "%s\n" "SET @q=CONCAT('CREATE USER ''lt_observer''@''%'' IDENTIFIED WITH mysql_native_password BY ',QUOTE(@p)); PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;"
  printf "%s\n" "GRANT SELECT ON hmdp_loadtest.* TO 'lt_observer'@'%';"
} | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --database=hmdp_loadtest
