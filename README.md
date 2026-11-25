# 邻享生活（NeighborLife）

本地生活服务项目，Spring Boot 后端同时提供静态前端。支持登录、店铺检索、内容互动、普通券和秒杀券购买、订单、模拟支付、发券、退款与商家核销。

2026-11-25：非 AI 功能已实现并完成独立集成验收，开发库增量迁移后重新启动。原有 8 笔历史订单保持原状态且只读。预约和 AI 顾问暂不启用；支付/退款使用本地模拟渠道，不产生真实扣款。

先看 [代码阅读指南](.cursor/rules/ai-readme/manual/非AI功能代码阅读指南.md)，再看 [最终实现与验收报告](docs/development/非AI功能实现与开发环境启用验收-20261125.md)。关键事务、Lua、消息、缓存及位运算已补中文注释。

## 本机运行

根目录为 `D:\Development\neighbor-life`；JDK 11，Spring Boot 2.3.12。脚本复用本机 IntelliJ IDEA 的 Maven；开发使用 `default` 配置和 `127.0.0.1:8081`。前端无需另启 Node 或 Live Server。

```powershell
Set-Location D:\Development\neighbor-life
.\tools\Invoke-NeighborLife.ps1 -Action Run
```

运行会占用当前终端，浏览器访问 [邻享生活](http://127.0.0.1:8081)。另一个终端可执行：

```powershell
.\tools\Invoke-NeighborLife.ps1 -Action Stop
.\tools\Invoke-NeighborLife.ps1 -Action Compile
.\tools\Invoke-LocalDependencies.ps1 -Action Status
.\tools\Invoke-BusinessMigration.ps1 -Action Inspect
```

VS Code 调试配置为 **NeighborLife: Development (JDK 11)**。Docker 开发依赖使用 MySQL `3306`、Redis `6379`、Kafka `9092`，独立验收使用 `3307/6380/9093`。停止后端不会删除容器数据卷。

`Compile` 当前只运行 `compile`。AGENTS.md 中的 `mvn clean compile`、`mvn clean package -DskipTests` 会清除对应构建目录；需要保留 `target/` 诊断记录时不要加 `clean`。

## 功能入口

- “发现好店”：分类、搜索、全局排序、定位附近、详情和普通券/秒杀券购买。
- “邻里探店”：真实笔记、图片、点赞、关注、评论/回复；用户中心可发布与管理自己的笔记、查看关注动态。
- “我的收藏”：登录后按账号存入数据库；访客浏览器收藏保留原行为。
- “我的订单”：状态查询、取消、模拟支付、退款和核销后评价；“我的持有券”显示核销凭证。
- 用户中心：资料/头像、签到日历、我的券；已授权商家/管理员显示对应入口。
- 商家：所属店铺/券维护、上下架、核销和统计；管理员可授权商家并查看/重投阻塞任务。

本地验证码由模拟渠道提供，页面有明确提示；登录成功后验证码一次性消费。退出会撤销当前 Redis Token。商家/管理员角色需要显式授权，维护命令见阅读指南；没有为历史账号自动授予角色。

## 验证与数据边界

```powershell
.\tools\Invoke-BusinessVerification.ps1
```

该脚本要求专用 `neighborlife-loadtest` 环境，并核验容器身份、端口、数据库 URL 和 marker。综合测试核对 HTTP、MySQL、Redis、Kafka offset、幂等账目及故障恢复；输出隔离在 `target/business-verification/`。最新结果为 1 个综合测试通过，内部覆盖多条交易/权限/并发/恢复链路，详情见最终报告。

不要在开发数据上直接运行含大量写入的旧测试，不要向已有数据库重导初始化 SQL。历史订单缺少新快照，保留只读；只有拥有 `tb_order_detail` 的新订单进入支付/退款/自动关单。

迁移工具会先停机检查、备份再增量升级。本次受限本机备份在 `backups/development-business-migration-20261125-152737/`，不进入 Git。

原技术栈中的 Redis Lua、Kafka、Redisson、Caffeine、逻辑过期、限流、GEO、Bitmap、HyperLogLog 和策略工厂继续使用。`consultant/` 是独立 AI 工程，本轮未启动。本次验收没有进行新的性能压测，也不使用参考 PDF 的数字作为实测性能。

## GitHub 与本机文件

源码仓库为 [JeSSica978/neighbor-life](https://github.com/JeSSica978/neighbor-life)。本机仅保留 GitHub 的 `origin` 远端，已移除 Gitee 备用远端，以避免 Git 工具访问 Gitee 时弹出登录提示。

数据库备份、运行日志、验收原始证据和压缩包保留本机。`consultant/` 原型配置使用 `application.example.yml` 作为示例；本机 `application.yml` 不上传，模型密钥由环境变量提供。根项目运行不依赖该 AI 工程。

## 历史资料

[系统闭环方案](.cursor/rules/ai-readme/manual/系统业务闭环调研与实施方案-20261125.md) 与 [验收矩阵](docs/development/系统业务闭环验收矩阵-20261125.md) 保留实施前状态；当前事实以最终报告为准。[阶段一记录](.cursor/rules/ai-readme/manual/阶段一启动检查.md)、[阶段三联调](docs/development/阶段三前后端联调与秒杀链路验收.md)、[普通券迁移教程](docs/development/普通券多次购买与开发环境重启.md) 可用于追溯。
