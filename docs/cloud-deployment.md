# 24 小时私有云部署

最后更新：2026-07-11

## 选定架构

生产目标是单一 owner 的个人研究系统：云主机负责 24 小时运行，Tailscale Serve 提供 tailnet 内 HTTPS，
浏览器不直接访问 Java API、PostgreSQL 或 Redis。Web BFF 每次请求生成 60 秒 HS256 Bearer JWT，API 校验
issuer、audience、签名、有效期和 owner email。所有宿主端口只绑定 `127.0.0.1`。

共享服务器若已有服务占用默认端口，可在 `.env.production` 中设置 `WEB_HOST_PORT`、`API_HOST_PORT` 和
`ANALYTICS_HOST_PORT`；只改变宿主机回环端口，不改变容器间通信，也不得把绑定地址改成公网 IP。

首选 Oracle Cloud Always Free Ampere A1（2 OCPU/12 GB 免费额度内），因为五容器在 1 GB Micro 上余量
不足；若所选区域没有免费容量，使用 Hetzner 4 GB shared VPS 作为付费后备。部署文件保持标准
ARM64/AMD64 Docker 兼容，不绑定云厂商。

## 需要项目负责人操作一次

1. 创建 Oracle Cloud 与 Tailscale 个人账户；Oracle 注册通常要求手机号和信用卡验证，但保持 Always Free
   额度不会自动产生超额资源费用。
2. 创建 Ubuntu 24.04 ARM64 VM：2 OCPU、12 GB RAM、至少 50 GB boot volume；只临时开放 SSH，应用端口
   不进入云防火墙。
3. SSH 登录后安装 Git、Docker Engine、Compose plugin、Tailscale、OpenSSL 和 rclone（使用远程备份时）。
4. 把仓库 clone 到 `/opt/ai-quant-research-assistant`，创建非 root 用户 `aiquant` 并加入 docker group。
5. 运行：

```bash
sudo -u aiquant ./scripts/init-production-secrets.sh
sudo -u aiquant chmod 600 .env.production .secrets/backup-passphrase
```

6. 只在服务器终端编辑 `.env.production`，填入旋转后的 LanYi Key、Tiingo Token、FRED Key 和 LanYi
   计价字段。密钥不得进入聊天、截图、Shell history 或 Git。
7. 验证并启动：

```bash
sudo -u aiquant ./scripts/production-preflight.sh
sudo -u aiquant docker compose --env-file .env.production \
  -f docker-compose.yml -f compose.production.yml up --build -d
```

8. 登录 Tailscale 后只启用私网 Serve，不使用 Funnel：

```bash
sudo tailscale up
sudo tailscale serve --bg http://127.0.0.1:3000
tailscale serve status
```

从同一 tailnet 的设备打开 `https://<server-name>.<tailnet>.ts.net`。不要把地址或导出报告分享给其他人，
否则会超出 Tiingo Individual 的内部使用边界。

## 每日备份

安装 `deploy/systemd/ai-quant-backup.service` 和 `.timer` 到 `/etc/systemd/system/`，确认路径和用户后启用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ai-quant-backup.timer
sudo systemctl start ai-quant-backup.service
sudo systemctl status ai-quant-backup.service
```

备份使用 AES-256-CBC/PBKDF2 加密并生成 SHA-256 与 Git commit sidecar；本地和可选 `rclone` 私有远程
副本均执行 90 天保留。首次上线后运行 `./scripts/verify-backup.sh <backup.dump.enc>` 验证 checksum、解密
和 `pg_restore --list`；完整恢复仍必须在隔离 VM 进行，不能覆盖生产库验证。

## 发布与回滚

每次只部署已经在 `main` 通过全仓 CI 的 commit。部署前执行 preflight，部署后检查 Web、API、Analytics
health 与 Provider status。回滚应用镜像时不回滚 Flyway migration；数据库迁移只前进。LanYi、Tiingo 或
FRED 出现异常时保持 circuit breaker 和显式失败/安全报告，不得切回 Mock 伪装 REAL 成功。
