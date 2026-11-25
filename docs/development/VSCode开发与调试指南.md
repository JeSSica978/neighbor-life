# NeighborLife VS Code 开发与调试指南

验证日期：2026-11-23，Asia/Shanghai。项目目录：`D:\Development\neighbor-life`。

2026-11-25 更新：F5 下拉框配置已命名为 **NeighborLife: Development (JDK 11)**，明确使用 `default` 和 8081；普通券多次购买及订单生命周期启用前的迁移、历史订单核对和重启步骤，见 [普通券多次购买与开发环境重启](普通券多次购买与开发环境重启.md)。

阶段三更新（2026-11-24）：下文描述阶段二的历史验证。当前秒杀 Kafka Consumer 已恢复，并完成真实后端链路测试；Chrome Network 和本轮 Consumer 异步断点仍待验证，详见[阶段三验收报告](阶段三前后端联调与秒杀链路验收.md)及[秒杀断点教程](阶段三秒杀断点调试.md)。

日常运行更新（2026-11-24）：普通启动可用 Ctrl+Shift+B 或 `Invoke-NeighborLife.ps1 -Action Run`；当前独立秒杀测试店、详情直达入口和日志开关见[日常运行与日志设置](日常运行与日志设置.md)。

本阶段七项验收全部通过：Docker 启动、MySQL、Redis、Kafka 基础连接、Maven 后端运行、VS Code Java Debug 和真实断点单步。Kafka 验收仅包含 Broker、Topic、Consumer 客户端元数据查询；现有业务 Consumer 尚未恢复，因此阶段一的完整业务 `STARTUP_PASS = NO` 结论仍保留。

最终停止了本轮 Maven/Java Debug 后端，8081 已释放；MySQL、Redis、Kafka 容器继续健康运行。下面的方法可以自行再次启动前后端。

## 1. 打开项目与安装扩展

在 VS Code 选择 File → Open Folder，打开 `D:\Development\neighbor-life`。本配置适用于直接打开该目录的单根工作区。

按 Ctrl+Shift+X，通过扩展 ID 查找并安装以下扩展。本机已通过 VS Code CLI 安装并实际使用：

| 扩展 | ID | 本次安装版本 |
| --- | --- | --- |
| Language Support for Java by Red Hat | `redhat.java` | 1.58.0，win32-x64 |
| Debugger for Java | `vscjava.vscode-java-debug` | 0.60.0 |
| Maven for Java | `vscjava.vscode-maven` | 0.46.0 |

推荐清单保存在 `D:\Development\neighbor-life\.vscode\extensions.json`。这三个扩展满足本阶段需求，没有另外安装整个 Java Extension Pack。等待底部 Java 状态变成 Ready，再开始 F5 调试。

## 2. JDK、Maven 与项目范围

| 配置 | 当前值 |
| --- | --- |
| 根项目 JDK | Oracle JDK 11，`C:\Program Files\Java\jdk-11` |
| Java Debug 可执行文件 | `C:\Program Files\Java\jdk-11\bin\java.exe` |
| Maven 3.9.9 | `C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd` |
| Spring Boot 主类 | `com.hmdp.HmDianPingApplication` |
| Java 项目名称 | `neighbor-life` |
| 默认配置 | `D:\Development\neighbor-life\src\main\resources\application.yaml`，没有新增 profile |
| 应用端口 | 8081 |

工作区配置在 `D:\Development\neighbor-life\.vscode\settings.json`。Java runtime 默认指向 JDK 11，Maven 工具复用 IDEA 已有 Maven，Maven 终端和新集成终端的 JAVA_HOME 指向 JDK 11。没有修改系统 PATH，已有终端需重新打开才会继承工作区终端设置；直接输入 `mvn` 仍可能提示找不到命令，优先使用任务或下面的脚本。

Java 版本更新（2026-11-25）：根 POM 的 `java.version` 和 `maven.compiler.release` 已统一为 11，编译语言级别、Java API 和产物目标与项目运行 JDK 一致。此前 POM 指定 1.8、工作区只有 JDK 11，会产生 JavaSE-1.8 无兼容 JRE及编译级别不匹配警告。修改后由工作区的自动构建配置更新重新导入；若旧警告仍保留，执行 **Java: Reload Projects**，必要时执行 **Java: Clean Java Language Server Workspace** 并重启语言服务器。

Java 语言服务器使用已安装 Red Hat 扩展内置 JRE 25。项目运行 JDK 与语言服务器运行时分开管理，当前不要把 `java.jdt.ls.java.home` 指向 JDK 11。

`D:\Development\neighbor-life\consultant` 是独立 Spring Boot 3.5 / Java 17 项目。Java 导入和 Maven 视图排除该目录；根 POM没有 modules 集成它，本阶段的任务只在根目录运行。

配置文件允许纳入 Git：本次将 `.gitignore` 的整个 `.vscode/` 忽略规则改为仅放行 settings、tasks、launch、extensions 四个文件，其他本地 VS Code 文件继续忽略。没有提交或 Push。

## 3. Docker 一键启动

按 Ctrl+Shift+P，选择 **Tasks: Run Task**，然后选择 **NeighborLife: Docker Start**。

任务调用已有依赖脚本 `D:\Development\neighbor-life\tools\Invoke-LocalDependencies.ps1 -Action Start`。执行顺序：

1. 检查已安装 Docker CLI 和 Docker Engine。
2. Engine 不可用时，检查 `C:\Program Files\Docker\Docker\Docker Desktop.exe`，尝试隐藏启动已安装的 Desktop，等待 Engine 就绪，默认最多 180 秒。
3. 对 `D:\Development\neighbor-life\docker\compose.yaml` 执行配置校验和 `up -d --wait --wait-timeout 300`，等待容器健康。
4. 确认已有 `seckill-voucher-order` Topic；只在不存在时创建，1 分区、1 副本，不改已有 Topic。
5. 使用根项目的实际 Java 依赖检查 JDBC、Redis、Kafka 客户端连接。

通过后终端输出：

```text
MYSQL_HEALTH_PASS categories=10
REDIS_HEALTH_PASS PING/SET_NX_EX30/GET
KAFKA_HEALTH_PASS broker/topic/consumer-metadata (no business messages)
DOCKER_START_PASS project=neighborlife-dev
```

MySQL 检查只读查询 `hmdp.tb_shop_type`。Redis 检查使用随机唯一键、SET NX EX30，读回后只清理成功创建的自身测试键。Kafka 检查 AdminClient 和 Consumer 客户端元数据，不发送订单、不 poll 业务消息、不提交 offset。

Compose 项目名称是 `neighborlife-dev`，依赖端口只绑定到本机：

| 服务 | 容器 | 本机地址 |
| --- | --- | --- |
| MySQL 8.0.44 | `neighborlife-dev-mysql-1` | `127.0.0.1:3306` |
| Redis 7.2 | `neighborlife-dev-redis-1` | `127.0.0.1:6379` |
| Kafka 3.9.1 | `neighborlife-dev-kafka-1` | `127.0.0.1:9092` |

本次实测 Engine 已经运行，验证了停止后的项目容器重新启动及全部健康检查。没有为了测试关闭整台机器的 Engine；Desktop 自动启动分支已配置，但本轮没有实测 Engine 冷启动。

脚本在内存中复用当前数据源密码供 Compose 校验，不把密码输出或写进 VS Code 配置。不要将密码/API Key 加到 tasks 或 launch。健康检查针对当前根项目默认配置；已有普通数据源、Redis 和 Kafka 环境变量会按检查器支持的对应项读取。切换 profile 或外部配置前需要同步调整检查流程，当前检测到 active profile 或外部配置位置会明确阻止启动。

## 4. 普通运行前后端

按 Ctrl+Shift+P → **Tasks: Run Task** → **NeighborLife: Run Backend (Maven)**。它也是默认 Build Task，可以按 Ctrl+Shift+B 运行。

任务先检查后端端口，再启动并验证 Docker 依赖，再次检查端口，最后使用 JDK 11 执行 Maven：

```powershell
Set-Location D:\Development\neighbor-life
.\tools\Invoke-NeighborLife.ps1 -Action Run
```

已有 Maven实际命令是：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-11'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd' -B -ntp spring-boot:run
```

直接执行 Maven 命令不会替你执行脚本里的依赖与端口保护，所以日常使用 Run 任务或 Run 脚本。

启动完成后在浏览器访问 `http://localhost:8081/`。店铺/登录前端在 `D:\Development\neighbor-life\src\main\resources\static`，由同一个 Spring Boot 进程提供，无需另开 npm 前端服务。

仅编译可运行 `D:\Development\neighbor-life\tools\Invoke-NeighborLife.ps1 -Action Compile`，其原有行为是 `clean compile`。Maven clean 会删除 target 下的临时证据；本次最终复查采用 Maven `compile`，正式验收证据保存在 docs/development/evidence。

## 5. F5 Java Debug

配置在 `D:\Development\neighbor-life\.vscode\launch.json`：

- 类型 `java`，request `launch`，主类 `com.hmdp.HmDianPingApplication`。
- `projectName` 为根 Java 项目 `neighbor-life`，Java 扩展自动解析根项目 classpath。
- `javaExec` 明确指向 JDK 11，classpath 使用临时 `@argfile`，避免 Windows 命令行过长。
- 集成终端显示应用日志；未新增 profile 或敏感值。
- `preLaunchTask` 是 **NeighborLife: Prepare Debug**，顺序执行端口检查、Docker Start，最后再检查端口。
- 单步过滤 `$JDK`、`$Libraries` 和 synthetic 方法，减少进入框架/生成代码的次数。

操作：

1. 如已有普通后端运行，先执行 **NeighborLife: Stop Backend**。
2. 按 Ctrl+Shift+D，选择 **NeighborLife: Java Debug (JDK 11)**。
3. 按 F5，等待前置任务成功和应用开始监听。
4. 浏览器访问首页或真实业务接口。
5. 按 Shift+F5 停止当前 Java 调试进程。

这是 Java Debug 扩展实际启动的带 JDWP 进程，可以命中断点。普通 Maven Run任务用于运行应用，断点调试请用上面的 F5 配置。

## 6. 实际断点与单步教程

选择本次验证过的真实只读接口：`GET http://127.0.0.1:8081/shop/1`。

1. 打开 `D:\Development\neighbor-life\src\main\java\com\hmdp\controller\ShopController.java`，在第 36 行 `return shopService.queryById(id);` 的左侧点红点，或将光标放到该行按 F9。
2. 打开 `D:\Development\neighbor-life\src\main\java\com\hmdp\service\impl\ShopServiceImpl.java`，在第 48 行 `Shop shop = cacheClient` 设置第二个断点。
3. F5 启动并确认应用监听 8081 后，在浏览器新标签页的地址栏直接输入 `http://127.0.0.1:8081/shop/1`。Controller 第 36 行暂停；Variables 中有 `id`，请求参数为 1。此处使用直接访问接口的方式查看完整 JSON；首页点击店铺使用的是前端请求，有 12 秒超时限制。
4. 按 F11（Step Into）。最终配置实测进入 Service 第 48 行；调用栈还可以看到 Spring CGLIB 代理。
5. 按 F10（Step Over）。本次多行语句的实际停靠顺序为 **48 → 49 → 48 → 59**，需要三次 F10，并等待每次暂停状态出现。行号来自字节码映射，返回 48 不是重新发起请求。
6. 在第 59 行检查 Variables：`shop` 已经有值。Ctrl+Shift+Y 打开 Debug Console，点击底部表达式输入框，可以执行只读表达式：

```java
"id=" + id.longValue() + ", shop.id=" + shop.getId() + ", shop.name=" + shop.getName()
```

本次实际输出：

```text
"id=1, shop.id=1, shop.name=103茶餐厅"
```

7. Call Stack 的当前帧为 `ShopServiceImpl.queryById(Long):59`，下面是代理/框架帧，可以切换到 Controller 帧检查调用来源。
8. 将焦点切回 VS Code，按 F5 继续，确认当前请求线程已恢复运行；如果又命中断点，继续执行到请求结束。切回直接访问 `/shop/1` 的浏览器标签页查看完整 JSON，预期包含 `success=true`、`data.id=1`、`data.name=103茶餐厅`。如果原请求已经超时或取消，先禁用这两个断点，再刷新该接口标签页，发起新的请求验证响应。
9. 按 Shift+F5停止。实测调试 PID 25044 消失、8081 释放，三个 Docker 依赖继续运行。

暂停时直接访问接口的浏览器标签页会等待响应，检查当前 HTTP 线程是否停在断点，继续后再判断接口结果。首页的 `src/main/resources/static/assets/app.js` 在 `api()` 中通过 `AbortController` 设置了 12000 毫秒超时；断点停留超过 12 秒，请求会被取消，店铺弹窗显示“店铺暂时无法打开 / 连接服务失败，请稍后重试。”。继续后端执行不会恢复已取消的前端请求，需要禁用断点后关闭并重新打开店铺弹窗，或直接访问 `/shop/1` 重新验证。首页的 `api()` 返回的是 `result.data`，弹窗展示店铺信息；完整的 `success`、`data` 包装 JSON 应在直接访问接口的标签页或浏览器 Network 响应中查看。不要在调试表达式里调用下单、写数据或其他有副作用的方法。

截图和原始 UI 记录：

- `D:\Development\neighbor-life\docs\development\evidence\controller-breakpoint.png` / `.txt`
- `D:\Development\neighbor-life\docs\development\evidence\service-step-into.png` / `.txt`
- `D:\Development\neighbor-life\docs\development\evidence\service-step-over.png` / `.txt`
- `D:\Development\neighbor-life\docs\development\evidence\service-variables.png` / `.txt`

Kafka Consumer 阶段三调试：当前 `SeckillVoucherConsumer.processMessage` 已注册真实监听，Redis/MySQL/Kafka 链路已验证，断点位置与独立数据步骤见[秒杀断点教程](阶段三秒杀断点调试.md)。监听方法运行在独立异步线程，HTTP Controller 的单步不会直接进入 Consumer；需在 Call Stack 选择对应 Consumer 线程。阶段二没有恢复 Consumer；阶段三仍没有实际操作 Consumer 的 VS Code 断点，因此不声称该项通过。

## 7. 停止项目与 Docker

| 目标 | 方法 | 行为 |
| --- | --- | --- |
| 停止当前 F5 会话 | Shift+F5 | 停止当前 Java Debug进程 |
| 停止普通后端 | Run终端 Ctrl+C，或 **NeighborLife: Stop Backend** | Stop任务核对主类及本项目 `target/classes`，支持 Debug `@argfile`，只关闭本项目 Java进程 |
| 停止依赖 | **NeighborLife: Docker Stop** | `compose stop mysql redis kafka`，保留容器和数据卷 |
| 查看依赖 | **NeighborLife: Docker Status** | 显示本项目容器状态 |
| 退出 Docker Desktop | 需要时自行从 Docker Desktop菜单 Quit | Docker Stop任务保持 Desktop运行，避免影响其他项目 |

建议先停止后端再停止依赖。Stop Backend若发现端口归其他程序所有会报错并拒绝关闭。它是进程终止操作，Maven可能在随后记录 `Application finished with exit code: -1` 和 BUILD FAILURE；本次这类记录出现在主动停止之后，启动前与真实接口检查均通过。需要应用正常接收终端中断时，可在自己的 Run终端使用 Ctrl+C。

本次 Docker Stop前后均存在三个持久卷：

```text
neighborlife-dev_kafka-data
neighborlife-dev_mysql-data
neighborlife-dev_redis-data
```

任务不删除持久卷，不使用 `down -v`、volume prune 或 system prune。

## 8. 常见错误排查

| 现象 | 检查与处理 |
| --- | --- |
| `mvn` 找不到 | 使用 Run任务/脚本，或 Maven实际绝对路径；无需另装 Maven |
| Docker Engine readiness timed out | 手动打开已安装 Desktop，查看 WSL/Engine错误；就绪后重试 Docker Start；脚本不会重新安装 |
| Docker healthcheck超时 | Run **Docker Status**；在 Desktop查看对应项目容器日志，修复真实异常后重试 |
| MYSQL_HEALTH_FAIL | 检查当前 datasource地址、认证与已存在表；不要重新导入含 DROP的历史 SQL |
| REDIS_HEALTH_FAIL | 检查当前 Redis地址和认证；不要清空已有 Redis数据 |
| KAFKA_HEALTH_FAIL | 检查 Broker 9092、advertised listener和 `seckill-voucher-order` Topic；此检查不代表业务 Consumer运行 |
| CONFIG_FAIL | 当前检查器仅支持已核验的根默认配置；先核对 profile/外部配置与检查器，再启动 |
| `Port 8081 is occupied` | 先停止已有 NeighborLife后端；其他程序占用则自行核对 PID，任务不会擅自关闭 |
| Java项目未找到/断点空心 | 打开根目录，等待 Java Ready，确认主类、projectName和源文件已保存；必要时执行 Java: Reload Projects后重试 |
| JavaSE-1.8 或编译级别不匹配警告 | 根 POM 已统一 Java 11；执行 Java: Reload Projects，必要时清理 Java 语言服务器工作区 |
| 单步进入CGLIB/生成代码 | 已设置单步过滤；框架代理仍可出现在调用栈，继续到真实Service断点即可 |
| 浏览器请求一直等待 | 确认断点状态；按F5继续。停止Java进程会中断当前等待的请求 |
| 旧框架 illegal reflective access提示 | Java Debug首次触发Spring CGLIB时观察到JDK11警告，真实查询仍成功；本阶段未升级业务依赖 |
| Windows防火墙提示 | 由用户按自己的网络权限选择处理；本轮用户已处理，本任务没有修改安全设置 |
| 启动consultant报Java版本错误 | consultant需要独立Java17环境；本阶段仅启动根Java11项目 |

## 9. 每个任务的实际执行结果

F5及其前置任务通过 VS Code UI实际执行。其余任务使用 tasks.json完全相同的 Windows PowerShell可执行文件、参数和脚本进行实测；没有把这些命令行核验描述为逐个点击任务菜单。

统一任务宿主：`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File <脚本> -Action <动作>`。ExecutionPolicy参数仅作用于这次脚本进程，不修改机器策略。

| 任务 | 实测方式与结果 | 证据 |
| --- | --- | --- |
| NeighborLife: Docker Start | F5前置任务UI执行成功；最终Maven Run再次从已停止容器启动，三项健康后输出DOCKER_START_PASS | `D:\Development\neighbor-life\docs\development\evidence\backend-maven.log`，24–27行健康结果先于69行Java启动 |
| NeighborLife: Docker Stop | 相同任务命令执行成功，三个项目容器停止；持久卷前后名称一致 | evidence目录的 `docker-stop.log`、`volumes-before-stop.txt`、`volumes-after-stop.txt` |
| NeighborLife: Docker Status | 相同任务命令成功；最终三个项目容器均healthy | evidence目录的 `docker-status.log` |
| NeighborLife: Check Backend Port | F5前置UI执行；空闲时成功；PID25424占用时失败并保留进程 | evidence目录的 `port-free.log`、`duplicate-start-protection.log` |
| NeighborLife: Prepare Debug | VS Code F5实际依次执行端口检查、Docker Start、二次端口检查，然后启动真实Java Debug | F5进程与断点截图；`debug-stop.json` |
| NeighborLife: Run Backend (Maven) | 相同任务命令成功；最终PID25424，JDK11，监听[::]:8081；四个基础页面/API均200 | evidence目录的 `backend-maven.log`、`final-backend-process.json`、`final-http.json` |
| NeighborLife: Stop Backend | 相同任务命令停止验证过的PID25424，8081释放；自建非Java监听测试被拒绝且仍存活，最后自行释放探针 | evidence目录的 `backend-stop.log`、`foreign-process-protection.log`、`port-free.log` |

最终Maven编译：2026-11-23 22:17:58，`-B -ntp compile` 返回 BUILD SUCCESS。第一次执行受到Codex沙箱连接/缓存读取限制，获执行权限后通过，未改POM或业务代码。日志 `D:\Development\neighbor-life\target\phase2-final-compile.log`，并复制到正式evidence目录。

最终普通运行HTTP核验：2026-11-23 22:23:31，PID25424，随后停止该进程。

| 请求 | 结果 |
| --- | --- |
| `/` | HTTP200，真实static首页 |
| `/shop-type/list` | HTTP200，success=true，10分类 |
| `/shop/of/name?current=1` | HTTP200，success=true，10店铺 |
| `/shop/1` | HTTP200，success=true，id=1，103茶餐厅 |

运行期间未发现应用ERROR/WARN/Exception；最终Maven日志尾部ERROR来自主动Stop Backend导致的进程退出。Java Debug出现过上述旧Spring CGLIB反射警告，断点和业务响应仍通过。没有使用固定成功值演示接口。

| 验收项 | 结论 | 范围 |
| --- | --- | --- |
| DOCKER_START_PASS | PASS | Engine可达、当前项目容器启动并healthy |
| MYSQL_HEALTH_PASS | PASS | 使用应用JDBC配置只读查询10分类 |
| REDIS_HEALTH_PASS | PASS | PING及独立临时键读写清理 |
| KAFKA_HEALTH_PASS | PASS | Broker、Topic、Consumer客户端元数据；不包含业务消费 |
| BACKEND_RUN_PASS | PASS | 实际Maven/JDK11进程、监听和四项HTTP响应 |
| VSCODE_DEBUG_PASS | PASS | VS Code F5带JDWP真实Java进程及Shift+F5停止 |
| BREAKPOINT_PASS | PASS | Controller、Service、F11、F10、参数/局部变量/调用栈及完整响应 |

停止脚本另外校验了主类与本项目 `target/classes` 的完整路径边界，防止相似目录名称被误判；读取 `@argfile` 时保留参数分隔。本次直接classpath与生成的转义argfile识别核验通过。由于VS Code会清理已结束会话的临时argfile，最后新增一次真实F5会话复测：2026-11-23 22:39:01，PID23900使用JDK11和JDWP，实际argfile识别通过，分类接口HTTP200返回10条；Stop Backend任务成功关闭该调试进程并释放8081。记录在evidence目录的 `live-debug-ownership.json`、`debug-script-stop.log`、`argfile-ownership.log`。

Git分支仍为 `master`，HEAD仍为 `ae461186eac48327be67d34be75bd4a28cf70164`。已有暂存、未暂存和未跟踪内容保留。阶段二只修改启动脚本、添加VS Code配置和本指南/证据、放行四个可共享VS Code配置；未修改秒杀业务、恢复Consumer或运行压力测试。KafkaConfig、SeckillVoucherConsumer、VoucherOrderServiceImpl与阶段一原始备份SHA256一致，记录在evidence目录的 `preserved-business-files.json`。
