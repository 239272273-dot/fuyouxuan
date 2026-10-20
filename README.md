# 福优选：企业内部福利抢购平台

福优选是一套可直接运行的企业员工福利抢购与内部信息社区。仓库包含完整前端、Nginx 网关、双 Spring Boot 后端，以及 MySQL、Redis Sentinel、RocketMQ 和 Canal。项目不包含管理员后台，访问者以普通员工身份登录和使用。

## 项目演示

### 福利中心

聚合展示员工可领取的各类福利，并提供分类筛选、余额查看和领取记录入口。

![福利中心](docs/screenshots/01-welfare-center.png)

### 福利详情与限量抢购

查看福利说明、活动时间、兑换价格和剩余库存，并在活动开放后参与限量抢购。

![福利详情与限量抢购](docs/screenshots/02-welfare-detail.png)

### 员工广场

员工可以发布内部动态、浏览同事分享，并进行点赞、关注和内容互动。

![员工广场](docs/screenshots/03-employee-square.png)

### 关注动态

集中展示已关注同事的最新内容，通过 Feed 时间线持续获取内部动态。

![关注动态](docs/screenshots/04-follow-feed.png)

### 个人中心

展示员工身份与个人资料，并统一管理自己发布的动态和互动数据。

![个人中心](docs/screenshots/05-profile.png)

## 已实现

- 企业员工登录、信息帖发布、点赞、关注和关注动态推送
- 福利商品浏览、秒杀、异步下单和个人订单查询
- Redis + Lua 原子完成库存校验、一人一单、扣减、分布式 ID 和订单入队
- 独立订单 Worker 使用 16 个分片任务批量投递 RocketMQ，与两个 Web 后端隔离；包含 ACK、消费幂等、补偿和死信处理
- Caffeine → Redis → MySQL 多级缓存，支持空值缓存、随机 TTL 和 Redisson Watchdog
- Canal 监听 MySQL ROW Binlog，使 Redis 与两个后端实例的本地缓存失效
- Nginx 负载均衡两个后端，并缓存公开福利列表

## 一键启动

### 运行前准备

一键启动脚本会下载并配置 MySQL、Redis、RocketMQ、Canal、Nginx 和后端镜像，但**不会自动安装 Docker 本身**。运行项目之前，电脑必须先具备 Docker Engine 和 Docker Compose。

- Windows / macOS：推荐安装并启动 Docker Desktop。
- Windows + WSL2：也可以在 WSL2 的 Linux 发行版中安装 Docker Engine 和 Compose 插件。
- Linux：安装 Docker Engine 和 Compose 插件，并确保当前用户有执行 Docker 命令的权限。

Windows 使用 Docker Desktop 时，请先打开它并等待 Linux 容器引擎就绪。在实际运行 Docker 的终端中检查：

```bash
docker --version
docker compose version
docker info
```

`docker info` 必须能返回服务端信息。只显示客户端版本号，不能证明引擎已经启动。如果 Docker Engine 装在 WSL2 中，请进入对应发行版执行这些检查；Windows 的 Docker Desktop 和 WSL 内独立安装的 Docker Engine 使用各自的容器、镜像和数据卷。

请使用支持 `docker compose up --wait --wait-timeout` 的 Docker Compose v2。Docker 方式会在镜像内完成 Java/Maven 构建，本机无需另外安装 Java、Maven 或 Node.js；克隆源码需要 Git。

如果电脑不能安装 Docker，就无法使用本仓库的一键启动方式。理论上可以手动安装并配置 Java 8、Maven、MySQL 8、Redis 7、RocketMQ 4.9、Canal 1.1 和 Nginx，但还需要自行完成账号、端口、网络及初始化配置，不建议用于首次体验。

首次启动需要联网下载镜像和构建后端，建议为 Docker 分配至少 8 GB 内存。

### 启动项目

先克隆源码并进入仓库根目录，后面的命令都在这个目录执行：

```bash
git clone https://github.com/239272273-dot/fuyouxuan.git
cd fuyouxuan
```

Windows PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\docker-up.ps1
```

如果 Docker Engine 安装在 WSL2 中，请使用“以管理员身份运行”的 PowerShell。脚本会在项目运行期间保持 WSL2 在线，并在必要时自动修复 `localhost` 转发。

脚本会先检查 Windows Docker 引擎是否可连接；如果 Docker Desktop 未运行，会尝试使用默认 WSL2 发行版中的 Docker Engine。仅能显示 Docker 版本号并不代表引擎已经运行；两个环境的引擎都不可用时，请先启动 Docker Desktop 或 WSL2 中的 Docker Engine。

Linux、WSL2 或 macOS：

```bash
bash scripts/docker-up.sh
```

脚本会在首次运行时生成本机 `.env`，其中的 MySQL 与 Canal 密码均为随机值，不会提交到 Git。所有服务就绪后访问：

<http://localhost:8080>

输入任意合法的 11 位手机号，点击“获取验证码”。演示模式会在页面上显示验证码，输入后即可登录；不需要管理员账号。

> 同一台电脑默认只运行一份福优选。若已经从另一个目录启动过本项目，再启动第二份同名副本会复用已有容器和数据卷，可能因为两份 `.env` 的随机密码不同而启动失败。首次体验请直接使用一份项目；需要并行运行时，应为第二份配置不同的 `COMPOSE_PROJECT_NAME` 和 `HTTP_PORT`。

## 停止与重启

停止服务并保留数据：

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File .\scripts\docker-down.ps1
```

```bash
# Linux / WSL2 / macOS
bash scripts/docker-down.sh
```

再次执行启动脚本即可复用数据。若要彻底清空演示数据并重新初始化，请确认无重要数据后执行：

```bash
docker compose down -v
```

常用排查命令：

```bash
docker compose ps
docker compose logs -f nginx app-1 app-2
```

如果 Windows 启动脚本提示 `Using Docker Engine inside WSL2`，请在同一 WSL 发行版排查；也可以在仓库目录的 PowerShell 中执行：

```powershell
$projectPath = (Get-Location).Path
wsl.exe --cd "$projectPath" docker compose ps
wsl.exe --cd "$projectPath" docker compose logs --tail 100 nginx app-1 app-2 order-worker
```

## 默认组成

| 服务 | 数量 | 说明 |
| --- | ---: | --- |
| Nginx + 前端 | 1 | 唯一对外入口，默认端口 `8080` |
| Spring Boot | 2 | 内嵌 Tomcat，由 Nginx 负载均衡 |
| 订单 Worker | 1 | 独立执行 Redis 队列投递和 RocketMQ 消费落库 |
| MySQL | 1 | 主库，开启 ROW Binlog |
| Redis | 2 | 一主一从 |
| Redis Sentinel | 3 | 监控 `mymaster` 并提供主节点发现 |
| RocketMQ NameServer | 1 | 消息路由服务 |
| RocketMQ Broker | 2 | 一主一从 |
| Canal | 1 | 订阅 MySQL Binlog |

容器网络中的数据库、中间件和后端端口不会暴露到宿主机；默认只公开 Nginx 的 `8080` 端口。

## 功能验收

服务启动后，可选执行全链路功能检查：

```bash
bash scripts/smoke-test.sh
```

验收脚本需要 Bash、Docker Compose、`curl` 和 `jq`。Ubuntu / WSL Ubuntu 可以先安装：

```bash
sudo apt-get update
sudo apt-get install -y curl jq
```

Windows PowerShell 用户可以在仓库目录执行：

```powershell
$projectPath = (Get-Location).Path
wsl.exe --cd "$projectPath" bash -lc "bash scripts/smoke-test.sh"
```

如果项目由 Docker Desktop 启动，需要为对应 WSL 发行版启用 Docker Desktop 的 WSL Integration，确保 WSL 中的 `docker compose ps` 能看到同一套项目容器。使用 WSL 独立 Docker Engine 时，在同一发行版运行验收与压测。

该脚本验证登录、Nginx 缓存、关注/动态、发帖/点赞、秒杀异步落单与虚拟豆支付、Canal 缓存失效和 Redis 主从复制，会创建验收用户、动态和订单，并更新一条演示福利信息；它不执行性能压测。

### 秒杀接口压测

只在独立的本地测试环境运行，避免同时操作演示页面或运行其他压测。脚本会创建专用用户、Token、福利和订单，成功后清理这些测试数据；失败时保留数据与待处理消息用于排查，不会清空共享订单队列。失败后应先检查输出和残留数据，再决定是否重新初始化测试环境。

Ubuntu / WSL Ubuntu 安装压测工具：

```bash
sudo apt-get update
sudo apt-get install -y wrk
```

启动项目后，在 Linux / WSL 中执行：

```bash
WRK_THREADS=8 WRK_CONNECTIONS=400 WRK_DURATION=15s WRK_USERS_PER_THREAD=20000 \
  bash scripts/benchmark-seckill.sh
```

Windows PowerShell 在仓库目录执行同一组参数：

```powershell
$projectPath = (Get-Location).Path
wsl.exe --cd "$projectPath" bash -lc "WRK_THREADS=8 WRK_CONNECTIONS=400 WRK_DURATION=15s WRK_USERS_PER_THREAD=20000 bash scripts/benchmark-seckill.sh"
```

这些也是脚本的默认值：8 个线程合计维护 400 条并发连接，持续施压约 15 秒，预置 `8 × 20000 = 160000` 个独立用户 Token 和对应库存。16 万是测试用户数，不是固定请求总量。每个线程使用自己的 2 万个 Token；任一线程耗尽时会报错终止，不会循环复用 Token，这次运行不能作为有效吞吐结果。更高吞吐或更长时间的测试需要提前扩大 Token 池，总用户数最多 999999。

脚本输出 `WRK_SUMMARY` 和 `BENCHMARK_RESULT`：

| 字段 | 含义 |
| --- | --- |
| `qps` | wrk 的 HTTP 请求吞吐，包含业务失败响应，不能直接当作成功下单或数据库落库 TPS |
| `requests` / `elapsed_seconds` | 完成的 HTTP 请求数 / wrk 实际压测秒数 |
| `accepted_per_sec` | Redis 成功受理订单数除以实际压测秒数，表示受理速率 |
| `accepted` / `mysql_orders` | Redis 受理订单数 / 等待异步消费后查询到的 MySQL 订单数 |
| `drain_seconds` | wrk 结束后等待异步链路收敛的秒数，按整秒统计，不是单笔订单延迟 |
| `redis_stock` / `mysql_stock` | 两侧剩余库存，均应等于初始库存减去受理订单数 |
| `redis_queue_depth` / `mq_diff` | Redis 队列长度 / RocketMQ 消费积压，收敛时应为 0 |
| `http_errors` / `connect_errors` / `read_errors` / `write_errors` / `timeouts` | wrk 记录的 HTTP 错误、连接、读写及超时错误；HTTP 200 中的业务失败需要结合受理数判断 |

15 秒是请求施压阶段的时长，数据库可能在它结束后继续落库。脚本额外等待异步链路收敛并校验订单与库存，因此最终落库数除以 15 秒不能称为数据库实时写入 TPS。机器配置、Docker 资源限制和是否发生错误都会影响结果；本仓库不预设或保证 6000 QPS，性能结论应保留实际输出与测试环境。

可选验证未支付订单的五分钟延迟关闭与库存补偿：

```bash
WRK_WAIT_FOR_TIMEOUTS=1 bash scripts/benchmark-seckill.sh
```

该选项会额外等待延迟消息，脚本最多等待 15 分钟。环境变量名称是 `WRK_WAIT_FOR_TIMEOUTS`。

### 开发检查

Java 单元及契约测试需要本机 Java 8 和 Maven，或在对应开发环境中执行：

```bash
mvn test
```

脚本回归检查使用模拟命令，不会启动项目或发送压测请求：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\test-docker-scripts.ps1
```

Linux / WSL 的脚本回归检查需要 Python 3；安装 LuaJIT 库后会同时校验 Token 不复用、耗尽报错和精确统计：

```bash
sudo apt-get install -y python3 libluajit-5.1-2
python3 scripts/tests/test-cli.py
```

GitHub Actions 会自动执行 Java 测试、脚本回归、镜像构建，并在独立 Linux Runner 中从空数据卷启动项目、运行功能验收；Windows Runner 单独检查 PowerShell 启停逻辑。CI 不执行性能压测，也不部署或重启已有云服务器。

## 部署到域名

在云服务器安装 Docker 后，克隆仓库并执行同一个启动脚本即可。将 `.env` 中的 `HTTP_PORT` 改为 `80`，或在容器前增加云负载均衡/HTTPS 反向代理，再把域名解析到服务器公网 IP。生产环境还应配置 HTTPS、短信服务、备份和访问控制；仓库的验证码展示仅用于体验。

## 目录

```text
frontend/                         企业员工前端与本地图片资源
src/main/java/com/fuyouxuan/      后端业务代码
src/main/resources/db/            演示数据库初始化脚本
docker/                            Nginx、Redis、RocketMQ、MySQL 容器配置
scripts/                           一键启停与功能验收脚本
compose.yaml                       完整服务编排
```

## 许可

本项目按 [MIT License](LICENSE) 发布。仓库内 Vue、Axios、Element UI 等第三方文件仍遵循各自的许可证。
