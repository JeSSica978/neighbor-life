# 阶段四补充审计：Loadtest Schema 修复

时间：2026-11-24T20:07:17.929489+08:00（Asia/Shanghai）。项目：`D:\Development\neighbor-life`。本轮仅修改文件和静态核验；结构基准是上一轮真实开发库元数据，采集时间 `2026-11-24T19:18:16.906646800+08:00`。本轮没有连接数据库。

| 验收项 | 状态 | 证据与范围 |
| --- | --- | --- |
| LOADTEST_SCHEMA_STATIC_MATCH | PASS | 10 张业务表、90 个字段、13 个索引；对照保存的开发库字段/SHOW INDEX 元数据，剩余业务结构差异为 0 |
| LOADTEST_SCHEMA_INIT_SQL_READY | PASS | 初始化脚本通过 GNU Bash 仅解析检查；同一 mysql 输入流保存/放宽/导入/恢复 Session Mode，8 组字符串校验只移除零日期标志；这是静态准备结果 |
| LOADTEST_SCHEMA_RUNTIME_VERIFIED | UNKNOWN | 未启动独立 MySQL，未执行初始化、DDL/DML，也未检查新连接的运行时 SQL Mode |
| FORMAL_LOADTEST_READY | FAIL | MySQL 初始化运行验收尚未通过，当前不放行正式压测；静态通过不满足运行准入门槛 |

已修复此前两处默认值差异；本报告的 PASS 仅适用于当前文件与归档开发库元数据的静态一致性。原有报告和安全快照保留修复前的历史 FAIL 记录，未回写或覆盖。

## 1. 初始化机制核对

已阅读 [loadtest schema.sql](<D:/Development/neighbor-life/tools/loadtest/infra/schema.sql>)、[loadtest 10-init.sh](<D:/Development/neighbor-life/tools/loadtest/infra/10-init.sh>)、[loadtest Compose](<D:/Development/neighbor-life/tools/loadtest/infra/compose.loadtest.yaml>) 和 [开发库初始化脚本](<D:/Development/neighbor-life/docker/mysql/10-init.sh>)，并核对安全快照 README、schema-comparison.json、database-metadata.json、development-show-create.sql。

Compose 声明 MySQL 镜像 `mysql:8.0.44`、`MYSQL_DATABASE=hmdp_loadtest`。官方 MySQL entrypoint 在首次初始化时完成系统库/指定数据库等基础设置，再按初始化目录文件顺序处理自定义脚本。已有数据目录会跳过首次初始化，因此模板修改不能据此认定已经应用到任何现有数据库。本轮没有检查、启动或执行容器内 entrypoint。

本项目挂载关系为：

| 主机文件 | 容器路径 | 执行方式 |
| --- | --- | --- |
| tools/loadtest/infra/10-init.sh | /docker-entrypoint-initdb.d/10-init.sh | 本项目唯一挂载到自动初始化目录的脚本；官方 entrypoint 根据可执行属性运行或 source |
| tools/loadtest/infra/schema.sql | /opt/loadtest/schema.sql | 不在自动初始化扫描目录，由 10-init.sh 内 cat 输出给 mysql stdin |

官方 `.sql` 文件分发会调用 mysql 客户端；不能假定两个 `.sql` 文件共用连接。`.sh` 被 source 只说明 Bash 调用方式，也不意味着其 mysql 调用共享数据库 Session。当前专用脚本只有一条 `mysql --protocol=socket -uroot --database=hmdp_loadtest`，所有 Session Mode 与 schema 语句都经同一管道输入该进程，所以无需依赖其他脚本的 Session 状态。

开发脚本 `docker/mysql/10-init.sh` 已通过在历史 dump 前输出 `SET SESSION sql_mode = REPLACE(...)`，然后在同一次 mysql 调用中导入，兼容零日期。本次沿用其“同一输入流、仅会话兼容”的机制，进一步按完整模式名称移除两个标志，并在 schema 完成后显式恢复。

Docker 分发规则和 MySQL Session Mode 语义参考官方文档及 entrypoint 源码；获取时间、地址、函数与依据范围记录在 [官方资料索引](<D:/Development/neighbor-life-loadtest/work/20261124-schema-fix-200016/primary-sources.json>)。SQL 初始化的真实执行结果仍为 UNKNOWN。

## 2. 最小修改与 Session 边界

[schema.sql:47](<D:/Development/neighbor-life/tools/loadtest/infra/schema.sql:47>) 现在为：

```sql
`begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
`end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
```

其余字段定义、NULL、默认值、主键、索引、字符集、排序规则保持原字节；说明注释改为保留开发库零日期默认值。保留原有 schema.sql CRLF 换行格式。

[10-init.sh:10](<D:/Development/neighbor-life/tools/loadtest/infra/10-init.sh:10>) 的 SQL 输入顺序为：

```sql
SET @lt_init_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = TRIM(BOTH ',' FROM
  REPLACE(REPLACE(CONCAT(',', @lt_init_sql_mode, ','),
    ',NO_ZERO_DATE,', ','), ',NO_ZERO_IN_DATE,', ','));
-- 同一 mysql 进程中，此处接收 cat /opt/loadtest/schema.sql 的内容
SET SESSION sql_mode = @lt_init_sql_mode;
-- 然后接收原有应用账号、只读账号创建与授权语句
```

`@lt_init_sql_mode` 保存该连接的完整原始模式；两侧补逗号，按完整 token 替换，再去掉边界逗号，避免空段或误删其他模式。未出现 SET GLOBAL、SET PERSIST 或全局 sql_mode 赋值，未添加 server --sql-mode 参数。账号创建之前恢复 Session Mode；后续应用连接的全局默认配置没有被本次文件改动降低。脚本保持 LF 换行格式和原有 `set -euo pipefail`，语法检查不会执行其命令。

按归档开发库模式作静态字符串演算：

```text
保存/恢复：ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
仅导入期间：ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
```

保留 STRICT_TRANS_TABLES、ONLY_FULL_GROUP_BY、ERROR_FOR_DIVISION_BY_ZERO、NO_ENGINE_SUBSTITUTION。另核验标志顺序互换、单个标志、只有两个零日期标志、无需变更、空模式及 STRICT_ALL_TABLES/ANSI_QUOTES 组合，8 组均保留其余模式并恢复原字符串。这是字符串与脚本检查，没有向 MySQL 发出 SET 或建表语句。

## 3. 十张业务表静态核验

以归档 `schema-comparison.json` 的实际结构、`database-metadata.json` 的原始列/SHOW INDEX，以及 `development-show-create.sql` 为基准，重新解析当前 schema.sql。复核归档规范化列值和索引唯一性/顺序确实对应原始元数据，不只修改上一份结果的 FAIL 标签。

| 业务表 | 字段数 | 索引数 | 结构结果 | 保留的索引 |
| --- | --- | --- | --- | --- |
| tb_blog | 10 | 1 | PASS | 主键 PRIMARY(id) |
| tb_blog_comments | 10 | 1 | PASS | 主键 PRIMARY(id) |
| tb_follow | 4 | 1 | PASS | 主键 PRIMARY(id) |
| tb_seckill_voucher | 6 | 1 | PASS | 主键 PRIMARY(voucher_id) |
| tb_shop | 15 | 2 | PASS | 主键 PRIMARY(id)；普通 foreign_key_type(type_id) |
| tb_shop_type | 6 | 1 | PASS | 主键 PRIMARY(id) |
| tb_user | 7 | 2 | PASS | 主键 PRIMARY(id)；唯一 uniqe_key_phone(phone) |
| tb_user_info | 11 | 1 | PASS | 主键 PRIMARY(user_id) |
| tb_voucher | 11 | 1 | PASS | 主键 PRIMARY(id) |
| tb_voucher_order | 10 | 2 | PASS | 主键 PRIMARY(id)；唯一 uk_voucher_order_user_voucher(user_id,voucher_id) |

比较项目包括字段名及顺序、类型和 signed/unsigned、NULL、默认值、ON UPDATE、自增属性、主键、普通/唯一索引、复合列顺序、索引类型/前缀/方向/可见性、列字符集/排序规则、InnoDB 与表排序规则/行格式。整数显示宽度按 MySQL 8 的显示差异规范化；varchar 长度、数值 signed/unsigned 等业务类型语义没有忽略。原有注释提取策略未改，不将显示宽度和注释文本当作字段约束差异。

两处时间 DEFAULT 均为零日期，其他默认值原样保留。业务表全部匹配，实质差异由 2 处降为 0。完整逐表、逐字段规范化定义及索引清单见 [本轮 schema-comparison-after.json](<D:/Development/neighbor-life-loadtest/work/20261124-schema-fix-200016/schema-comparison-after.json>)。

严格保留以下边界：

- `tb_voucher_order` 保留 PRIMARY(id) 和唯一 `uk_voucher_order_user_voucher(user_id,voucher_id)`，复合列顺序不变；原有普通索引和用户手机号唯一索引不变。
- 原有自增编号策略完全不变：原来指定 1000000 的表继续指定；tb_follow 原来未显式指定，仍不指定。对比时将下一计数差异作为压测隔离策略，不误报业务字段自增属性变化。
- `lt_environment(id INT PRIMARY KEY, marker VARCHAR(64) NOT NULL)` 与固定 marker 保留，仍用于 Fixture/Observer 的独立环境识别。没有执行 marker INSERT。
- 未增加 MySQL Outbox 表，未改秒杀业务逻辑或 Redis/Kafka 配置。

通过字节级预期变化检查，schema.sql 只允许两处默认值与对应说明注释变化；10-init.sh 只插入保存、放宽、恢复 Session 的语句与说明。原有导入路径、密码环境变量处理、应用/观察账号创建及授权内容未改变。

## 4. 静态检查与运行边界

实际执行宿主机自带 `sh.exe`（GNU bash, version 5.3.15(2)-release (x86_64-pc-cygwin)）的 `-n` 仅解析检查：退出码 0。没有运行 10-init.sh、其 mysql 子进程或 Docker/WSL。证据见 [bash-parse.log](<D:/Development/neighbor-life-loadtest/work/20261124-schema-fix-200016/bash-parse.log>)。

本轮没有数据库连接，因此没有建表成功、真实 SHOW CREATE TABLE、账号权限或应用新连接 SQL Mode 的运行证据。即使脚本语法和结构静态通过，LOADTEST_SCHEMA_RUNTIME_VERIFIED 仍为 UNKNOWN，不能给出运行 PASS。

FORMAL_LOADTEST_READY 为 FAIL：初始化运行验收门槛尚未满足，不能自动进入正式 JMeter 压测。后续人工审查并另行授权后，运行验收还需核对独立库初始化完成、实际两处零日期默认值及全量约束、环境 marker、应用新连接的严格 SQL Mode、独立账号权限；本轮没有执行这些动作。

## 5. 修改前后哈希与保护

开始时分支 `master`，HEAD `ae461186eac48327be67d34be75bd4a28cf70164`，暂存项 2 个，未跟踪项 146 个。与上一轮保护记录和快照全部校验项比较，没有预先漂移。先在项目外记录原始 235 项文件 SHA256 和 Git 状态；没有导出含默认配置密码的 diff 正文。

仅修改两份获准原文件：

| 文件 | 修改前 SHA256 | 修改后 SHA256 |
| --- | --- | --- |
| `tools/loadtest/infra/10-init.sh` | `09bb5e927a614f6b7591a7b033dfdbff10a65c4f3344f6e5028e10b9b1ce93c2` | `0163129f0078b280ea8e3d925398565e9d0899dfd47c7b271b528c46a29d688c` |
| `tools/loadtest/infra/schema.sql` | `ff7556a8da6a9a52b3ed742798e1b9433c7701478da168b9aa0633ce9354dceb` | `95f81e28a57f63ef5d8a0c95bb5a6a849f532937d1eb0f21e0f41e154925767e` |

重要保护文件的哈希如下，前后相同：

| 文件 | 前后 SHA256 | 结果 |
| --- | --- | --- |
| `src/main/resources/application.yaml` | `fb438754823591c9bec5894f75ff395de1dc4210cd7c59f286e747d46818b7d2` | 未变 |
| `docker/compose.yaml` | `2d3d6962f8eead695ca802c6baea284de41d2b5609b8cb3ec457ce2b95b5781f` | 未变 |
| `tools/loadtest/infra/compose.loadtest.yaml` | `492640d349efb74ae4a8a169c9c43a72765e3f49646b03228f8629fa3eb05aec` | 未变 |
| `docker/mysql/10-init.sh` | `387d3497038d93aa604de45a839f75a4cbe7df6d6679125e96c0ed317d2968f3` | 未变 |
| `tools/loadtest/fixture/LoadtestFixture.java` | `d8dbd34b4425f54e0bc9ccf8c821c1f71df873c6be152369b95ca883ac9bc4d6` | 未变 |
| `tools/loadtest/observer/LoadtestObserver.java` | `e80aaf04777cbf37f6586067bd579d134600f03cc4d871b6ab9e203bddd376ec` | 未变 |
| `tools/loadtest/LoadtestEnvironment.java` | `625852e9df793aa21acdef275f52532f3b116b0437ba48da124422489ee410ec` | 未变 |
| `tools/loadtest/observer/Collect-LoadtestResources.ps1` | `e1b38f54fa3766a93f85e25e4ee5173d22de09ba0df1e1281ff4ff22bd9c5797` | 未变 |
| `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | `588087b51b0209540c11c0482885b71e676359399f1337d4551bdcda5f04d470` | 未变 |
| `src/main/java/com/hmdp/consumer/SeckillVoucherConsumer.java` | `384f04b73c7591c2d18fe2731d93ce88c965db4b67b27a4841a39cba57b3d244` | 未变 |
| `src/main/java/com/hmdp/config/KafkaConfig.java` | `78f07ac020d2cdc1274c4e1acb79e11262d56025de4e09fbc624536f0685bf81` | 未变 |
| `src/main/java/com/hmdp/config/RedissonConfig.java` | `43c9342175e47083e088c87930ed7ef6e61e45830f9eb5d29a9b5afff0377aa8` | 未变 |

其余原有文件、源码和报告同样逐项核验。暂存 diff SHA256 仍为 `7114c9f18a97a237f4d46a128ab71a97d90f916430195529b3424f97a8098dba`；HEAD diff 与未暂存 diff 也未变，因为本次两份 infra 文件原本均未跟踪，不能仅用 Git diff 判断它们是否修改。全部原始文件哈希才是本次范围检查依据。

本轮只新增本报告，静态工具/证据位于 `D:\Development\neighbor-life-loadtest\work\20261124-schema-fix-200016`。最终保护记录见 [protection-check.json](<D:/Development/neighbor-life-loadtest/work/20261124-schema-fix-200016/protection-check.json>)；其中记录修改范围、所有前后哈希、Git 状态和旧快照校验结果。旧 `20261124-recovered` 中 162 个文件保留原样，161 条校验记录仍匹配；本轮修复没有回写旧快照，其修复前 schema 和历史 FAIL 记录继续作为审计证据。

没有修改默认 application.yaml、开发 Compose/开发初始化脚本、loadtest Compose、业务源码、Fixture、Observer；没有操作 PID 25052、调用 Docker、连接数据库、修改 Redis/Kafka、创建业务数据、运行 Maven/JMeter 或 Git commit/push/reset/checkout/stash。完成后停止，等待人工审查。
