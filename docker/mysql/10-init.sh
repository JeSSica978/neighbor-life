#!/bin/bash
set -euo pipefail

# The historical dump contains zero-date defaults. Relax only this import session.
{
    printf '%s\n' "SET SESSION sql_mode = REPLACE(REPLACE(@@SESSION.sql_mode, 'NO_ZERO_DATE', ''), 'NO_ZERO_IN_DATE', '');"
    cat /opt/neighborlife/hmdp.sql
} | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --database="$MYSQL_DATABASE"
