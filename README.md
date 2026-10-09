# 福优选：企业内部福利抢购平台

福优选是一套可直接运行的企业员工福利抢购与内部信息社区。仓库包含完整前端、Nginx 网关、双 Spring Boot 后端，以及 MySQL、Redis Sentinel、RocketMQ 和 Canal。项目不包含管理员后台，访问者以普通员工身份登录和使用。

## 已实现

- 企业员工登录、信息帖发布、点赞、关注和关注动态推送
- 福利商品浏览、秒杀、异步下单和个人订单查询
- Redis + Lua 原子完成库存校验、一人一单、扣减、分布式 ID 和订单入队
- 16 个 Worker 分片拉取订单，批量投递 RocketMQ；包含 ACK、消费幂等、补偿和死信处理
- Caffeine → Redis → MySQL 多级缓存，支持空值缓存、随机 TTL、逻辑过期和 Redisson Watchdog
- Canal 监听 MySQL ROW Binlog，使 Redis 与两个后端实例的本地缓存失效
- Nginx 负载均衡两个后端，并缓存公开福利列表

## 一键启动

需要 Docker Desktop，或已安装 Docker Engine 与 Docker Compose 的 Linux/WSL2。首次启动需要下载镜像和构建后端，建议为 Docker 分配至少 8 GB 内存。

Windows PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\docker-up.ps1
```

Linux、WSL2 或 macOS：

```bash
bash scripts/docker-up.sh
```

脚本会在首次运行时生成本机 `.env`，其中的 MySQL 与 Canal 密码均为随机值，不会提交到 Git。所有服务就绪后访问：

<http://localhost:8080>

输入任意合法的 11 位手机号，点击“获取验证码”。演示模式会在页面上显示验证码，输入后即可登录；不需要管理员账号。

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

## 默认组成

| 服务 | 数量 | 说明 |
| --- | ---: | --- |
| Nginx + 前端 | 1 | 唯一对外入口，默认端口 `8080` |
| Spring Boot | 2 | 内嵌 Tomcat，由 Nginx 负载均衡 |
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

该脚本只验证登录、Nginx 缓存、关注/动态、发帖/点赞、秒杀异步落单、Canal 缓存失效和 Redis 主从复制，不执行性能压测。简历中的 TPS 和 RTT 数据应以独立压测环境与原始报告为准。

Java 单元及契约测试：

```bash
mvn test
```

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
