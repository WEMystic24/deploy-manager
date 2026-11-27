# deploy-manager — Java 包远程发布 / 重启管理工具

一个 Spring Boot 小工具：把 jar 包通过 SSH/SFTP 发布到目标服务器的指定目录，并一键停止 / 启动 / 重启远程 Java 进程。
服务器的账号密码在页面上配置，加密保存在本地，不需要每次手敲 scp 和 kill。

技术栈：Spring Boot 2.7.18 + Java 8 + Maven + jsch(SSH) + 原生前端页面（与本仓库其它工程保持一致）。

---

## 一、快速开始

```bash
# 1. 打包
cd deploy-manager
mvn -s maven-settings.xml -DskipTests clean package

# 2. 启动（默认端口 8099）
java -jar target/deploy-manager.jar

# 3. 打开浏览器
open http://localhost:8099
```

首次进入点「新增」录入一台服务器，点「连接测试」验证账号密码，然后就可以上传 jar 发布了。

## 二、核心功能

| 能力 | 说明 |
| --- | --- |
| 服务器账号密码配置 | 页面维护，密码 AES 加密后存到 `~/.deploy-manager/servers.json`（**绝对路径，重启工具配置不丢**）；也支持私钥登录 |
| 服务器分组（文件夹） | 左侧树按文件夹归类，可新建 / 重命名 / 删除；**拖拽服务器到文件夹即改分组，文件夹本身也能拖拽排序**（拖到目标标题条上半 / 下半，插到它之前 / 之后），顺序持久化到 `~/.deploy-manager/server-groups.json` |
| 实时进度（SSE） | 发布 / 启停 / 回滚改为异步执行，页面用 SSE 实时展示每一步进展、整体进度条、以及上传 jar 的百分比；可随时知道“进行到哪了” |
| 失败明确提示 | **任何一步报错都会在页面顶部弹出红色错误横幅 + 红色 toast，并定位到失败的那一步（✗）及其原因**；结果区对“未执行”的后续步骤标灰，不再卡在“执行中/上传中”转圈 |
| 发布 jar 到指定目录 | 支持浏览器上传，或直接填写本机 jar 路径（例如 `target/app.jar`）推送 |
| 配置时直接选远程路径 | 发布配置里「jar 包目录 / conf 目录 / info 日志文件 / error 日志文件 / 备份目录」都能点「浏览」在弹窗内浏览服务器目录，点一下即填入（也能复制路径）；日志目录还没创建时自动回退到上级目录 |
| 自动备份 | **发布前始终备份，无需开关**：旧 jar 与 conf 文件各留一份带时间戳的副本；未配置备份目录时后端就地备份（jar 在 jar 包目录、conf 在 conf 目录），配置了就集中存到该目录，目录不可用时自动回退；默认保留最近 10 个版本 |
| 原子替换 | 先传 `xxx.jar.uploading`，传完再改名覆盖，避免中断留下坏包 |
| 一键重启 | 先优雅 `kill`，超时未退再 `kill -9`，然后 `nohup java -jar` 重新拉起 |
| 状态查询 | PID、启动时间、运行时长、占用内存、远程 jar 大小与修改时间；**多 jar 的自定义部署可指定「主 jar 包」**，状态 / 看板 PID / 启动后存活判定只认它（留空则任一 jar 运行即算运行，并提示共几个进程） |
| 远程日志 | 页面拉取日志尾部，可 5 秒自动刷新；info / error 两个文件切换 + 关键词（空格分隔多条件）过滤 |
| SFTP 文件浏览 | 右侧面板浏览远程目录：上传、下载、新建文件夹、删除；**每个条目与当前目录都能一键复制完整路径**，默认宽度随视口自适应，窄时自动隐藏时间/大小列优先保证文件名可读 |
| 版本回滚 | 选历史备份一键回滚并重启（同样带实时进度） |
| 操作记录 | 每次发布 / 启停都有记录，同时落到 `~/.deploy-manager/operation.log` |

> **配置持久化说明**：从相对路径 `./data/` 改为用户主目录下的绝对路径 `~/.deploy-manager/`，彻底解决“从不同目录启动 jar 导致配置消失”的问题。旧版本若已在 `./data/servers.json` 存过配置，首次启动会自动迁移过来，无需手动处理。

> **上传权限提示**：jar 会先 SFTP 到远程 `/tmp/deploy-staging/`（登录账号必定可写），再用远端命令移动到目标目录。若目标目录（如 `/opt`）需要 root 权限，请务必在服务器配置中开启「登录后 su 切换到 root」——否则移动步骤会报 `Permission denied`，页面会明确提示你去开启 su。

## 三、服务器配置项

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| 名称 | 是 | 例如「测试环境-订单服务」 |
| 服务器地址 / 端口 | 是 | SSH 地址，端口默认 22 |
| 认证方式 | 是 | 账号密码（默认）或私钥文件 |
| 登录账号 / 密码 | 是 | 编辑时密码留空表示沿用原密码 |
| 登录动态码 | 否 | **见 3.2**。开启后首次连接前弹窗输入动态码，拼接到密码末尾用于登录，动态码不落盘 |
| 私钥路径 / 口令 | 否 | 认证方式选私钥时填写，路径指本工具所在机器 |
| 提权（su） | 否 | **见下方说明**。很多生产机只能用普通账号 SSH 登录，需切 root 才有 `/opt` 等目录权限 |
| 发布目录 | 是 | jar 上传目录，必须绝对路径，如 `/opt/app/order`，不存在自动创建 |
| jar 包名称 | 是 | 远程统一文件名，如 `order-service.jar`（上传的文件名会被改成它） |
| java 命令 | 否 | 默认 `java`，也可写 `/usr/local/jdk1.8/bin/java` |
| JVM 参数 | 否 | 如 `-Xms512m -Xmx1024m -Dfile.encoding=UTF-8` |
| 启动参数 | 否 | 如 `--spring.profiles.active=test --server.port=8080` |
| info 日志文件 | 否 | 默认 `发布目录/logs/jar名.log`，默认启动命令的输出重定向到这里 |
| error 日志文件 | 否 | 默认 `发布目录/logs/jar名-error.log`，运行日志页「error」页签读取 |
| 备份目录 | 否 | 留空：后端在 jar 包目录、conf 目录就地备份（同名 + 时间戳）；前端存 `发布目录/backup`。填写则统一存到该目录；目录不可用时自动回退到发布目录 / jar 包目录 |
| 启动前 / 后命令 | 否 | 需要额外 `source` 环境变量或做通知时使用 |

### 3.1 登录后切换用户（su 提权）

不少生产环境**禁止 root 直接 SSH 登录**，只能拿普通账号（如 `deploy`）连上去，再 `su - root` 输入密码切过去才能动 `/opt`、`/usr/local` 这类目录。本工具支持这个流程：

- 勾选「登录后使用 su 切换到其他用户再执行操作」；
- 填「目标用户」（默认 `root`）和「su 密码」（即 su 时要输入的那个密码，与登录密码相互独立，同样 AES 加密保存，编辑时留空表示不改）；
- 之后所有操作（建目录、上传、启停、状态、日志、备份、回滚）都会在 **普通账号登录后自动 su 过去以目标用户身份执行**。

实现要点：

- 远程命令先以脚本形式 SFTP 写到 `/tmp/deploy-*.sh`，再用 **PTY 交互式 su**（`su - 目标用户 -c 'bash 脚本'`）执行——密码在监测到 `Password:` 提示后通过通道写入，并关闭终端回显，**密码不会出现在命令里，也不会进日志**。这种方式同时兼容 Debian/Ubuntu 的 util-linux `su` 和 RHEL/CentOS 的 `shadow` `su`（单纯管道喂密码在 Ubuntu 上会失败）。
- 上传时因为普通账号对 `/opt/app` 通常没有写权限，会先把 jar 传到 `/tmp/deploy-staging/` 中转，再 su 移动过去。
- 连接测试会额外验证一次 su 是否成功，并在页面回显当前身份。

> 若服务器装的是 `sudo` 且希望走 sudo，当前版本用 su 语义实现，把「目标用户 + su 密码」对应填成 sudo 场景下的账号即可；如需 `sudo -S` 直连可再定制。

### 3.2 登录动态码（MFA / 二次验证）

有些服务器登录除了密码还要一次性动态码（短信、OTP、谷歌验证码等）。在「登录密码」下方勾选 **开启动态码** 即可：

- **首次连接前弹窗**：打开/切换到该服务器、发布、拉日志等任何要连 SSH 的操作，都会先弹窗要求输入当前动态码，**输入前不会发起任何连接**；提交后自动继续原来的操作，不需要重新点一遍。
- **认证方式**：实际提交给服务器的密码 = 登录密码 + 动态码（不做分隔符插入）。动态码输入框接受 4~16 位字母或数字。
- **不落盘**：动态码只在本服务内存中按服务器保存，**不会写入 `servers.json`**。它**只在「新建连接」时参与认证**：连接一直存活（持续操作、心跳保活）就直接复用，不会再索要；只有连接断开需要重新登录、而缓存的动态码又已超过 5 分钟有效期时，才会重新弹窗。换言之「一直在操作就一直不用再输码」，空闲超过 5 分钟断连后才需要重新输入。
- **失效自动重输**：若动态码错误导致认证失败，服务端会清掉已存的动态码，下次操作重新弹窗。
- **改密码 / 关闭开关**：保存时会清空已输入的动态码，强制下次重新输入。
- 私钥登录不使用密码，因此选「私钥文件」时该开关会自动置灰关闭。

> 接口约定：需要动态码时后端返回 `code: 428`，`data` 为 `{dynamicCodeRequired, serverId, serverName}`；前端提交 `POST /api/servers/{id}/dynamic-code`（`{"code":"123456"}`）后重试原请求。

## 四、application.yml 关键配置

```yaml
server:
  port: 8099                      # 本工具自身端口

deploy:
  data-file: ${user.home}/.deploy-manager/servers.json   # 配置持久化（绝对路径，密码密文）
  upload-dir: ${user.home}/.deploy-manager/uploads       # 页面上传的 jar 在本机的留存目录
  operation-log-file: ${user.home}/.deploy-manager/operation.log
  secret-key: ChangeMe@...        # 密码加密密钥，务必改；改了旧密码要重新录入
  keep-backup-count: 10           # 远程保留几个历史版本
  stop-wait-seconds: 20           # 优雅停止等待秒数，超时 kill -9
  start-wait-seconds: 8           # 启动后几秒检查进程存活
  auth:
    enabled: false                # 页面访问认证（Basic），建议开启
    username: admin
    password: admin123
  servers: []                     # 可预置服务器，首次启动导入，之后以页面数据为准
```

也可以不改 yml，用命令行覆盖：

```bash
java -jar target/deploy-manager.jar --server.port=9000 --deploy.auth.enabled=true
```

## 五、发布时到底做了什么

```text
连接服务器 (ssh，普通登录账号)
  → [若开启 su] 所有后续步骤均以 su 切换到目标用户（如 root）后执行
  → mkdir -p 发布目录 / 日志目录
  → 备份旧版本（始终执行）：cp -p app.jar app.jar.20260818120000；conf 目录里的配置文件同样各备份一份
      · 未配置备份目录 → jar 备份在 jar 包目录、conf 备份在 conf 目录
      · 配置了备份目录 → 都存到该目录；该目录不存在/不可写 → 自动回退到发布目录，发布不中断
  → SFTP 上传（su 场景先传到 /tmp 中转再 su 移动）到 app.jar.uploading，成功后 rename 覆盖 app.jar
  → 清理超过 keep-backup-count 的历史备份
  → （勾选重启时）ps 定位 "-jar /发布目录/app.jar" 的进程 → kill → 等待 → 必要时 kill -9
  → cd 发布目录 && nohup java $JVM_OPTS -jar app.jar $APP_ARGS >> 日志 2>&1 &
  → sleep 后再次确认进程存活，失败则回显日志尾部
```

> 回滚只针对 jar / 前端 tar.gz 备份；conf 的备份文件用于人工恢复（同名 + 时间戳，直接 `cp` 回去即可）。

进程定位用的是完整 jar 路径（`-jar /opt/app/order/order-service.jar`），因此同一台机器上跑多个 Java 应用不会误杀。
远程命令统一用 `bash -l -c` 执行，会加载 `/etc/profile`，可避免常见的 `java: command not found`。

## 六、REST 接口

发布 / 启停 / 回滚均为**异步**：接口立即返回 `taskId`，前端用 SSE 订阅实时进度。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/servers` | 服务器列表（不含密码） |
| POST | `/api/servers` | 新增 / 修改（带 id 为修改） |
| DELETE | `/api/servers/{id}` | 删除配置 |
| POST | `/api/servers/{id}/test` | 连接测试 + 远端 java 版本（需要动态码时返回 428，前端输入后自动重试） |
| GET | `/api/servers/{id}/dynamic-code` | 动态码状态：`{required, valid, ttlSeconds}` |
| POST | `/api/servers/{id}/dynamic-code` | 提交登录动态码 `{"code":"123456"}`，仅内存保存（5 分钟） |
| POST | `/api/deploy/{id}/upload` | 上传 jar 发布，form-data: `file`、`restart`，返回 `taskId` |
| POST | `/api/deploy/{id}/upload-multi` | 一次上传多个 jar 统一发布（自定义部署），form-data: `files`（可多个）、`restart`，返回 `taskId` |
| POST | `/api/deploy/{id}/conf-upload` | 上传 conf 文件到 conf 目录（同名覆盖、不重启），form-data: `files`（可多个），返回 `{confDir, files}` |
| POST | `/api/deploy/{id}/local?path=&restart=` | 用本机路径发布，返回 `taskId` |
| POST | `/api/apps/{id}/start\|stop\|restart` | 启动 / 停止 / 重启，返回 `taskId` |
| POST | `/api/apps/{id}/rollback?name=&restart=` | 回滚指定备份，返回 `taskId` |
| GET | `/api/apps/{id}/status` | 运行状态 |
| GET | `/api/apps/{id}/log?lines=200&type=info&q=关键词` | 运行日志：`type=info/error` 切换日志文件；`q` 为关键词（空格分隔多个条件，需同时命中；含空格用双引号），不传则看尾部 |
| GET | `/api/apps/{id}/backups` | 历史备份列表 |
| GET | `/api/deploy/tasks/{taskId}` | 任务当前快照（轮询 / 重连兜底） |
| GET | `/api/deploy/tasks/{taskId}/stream` | **SSE 实时进度流**（`text/event-stream`） |
| GET | `/api/logs?limit=50` | 操作记录 |

SSE 事件类型：`snapshot`（连接即推送完整步骤列表）→ 每步 `step`（含进度）→ 结束 `done`（最终结果）或 `error`。

命令行触发发布（拿到 taskId 后，可再轮询快照确认结果）：

```bash
TASK=$(curl -s -X POST "http://localhost:8099/api/deploy/{serverId}/local?path=/path/target/app.jar&restart=true" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'])")
curl -N "http://localhost:8099/api/deploy/tasks/$TASK/stream"
```

## 七、SSH 连接复用

程序不会每次操作都重新登录服务器，而是按「账号@主机:端口」缓存已登录的连接：

| 场景 | 行为 |
|---|---|
| 同机同账号的连续操作 | 复用同一条连接，只开新的执行通道（省掉 ~500ms 的握手认证） |
| 连接空闲超过 5 分钟 | 后台线程主动断开，下次操作时重新登录（避免服务器侧堆积会话） |
| 连接被服务器/网络断开 | 借用时自动重建；若在执行中才发现，自动重连一次并重试该命令 |
| 服务器密码、私钥、登录方式改动 | 配置指纹变化，下次操作重建连接 |
| 建连失败 | 3 秒内同机请求快速失败，不再各自等满连接超时 |
| 应用关闭 | 断开所有缓存连接 |

由于复用了长连接，代码里保留了 30 秒的 SSH 心跳（`setServerAliveInterval`），中间网络设备不会掐掉空闲会话。操作记录里的 `CONNECT` 只在真正建连时出现，条数会明显少于操作次数。

并发访问同一台服务器时共用这条连接（JSch 允许一条连接上开多个通道），首次并发只会登录一次。

## 八、安全提醒

- 本工具持有服务器账号密码，**只部署在内网**，不要暴露到公网。
- 建议开启 `deploy.auth.enabled=true` 并改掉默认用户名密码。
- `deploy.secret-key` 请改成自己的值；`~/.deploy-manager/servers.json` 内是密文，但密钥泄露即可解密，注意文件权限。
- `~/.deploy-manager/` 已在 `.gitignore` 中，不会把密码文件提交到仓库。

## 九、常见问题

**启动失败提示 `java: command not found`**
远端 java 不在 PATH 里。在配置里把「java 命令」写成绝对路径，或在「启动前命令」里 `source /etc/profile`。

**状态显示未运行，但服务器上进程确实在跑**
说明进程启动命令里的 jar 路径与配置的「发布目录 + jar 包名称」不一致（例如之前是用相对路径 `java -jar app.jar` 启的）。用本工具重启一次即可统一。

**同机生产和测试两套同名 jar，PID 显示成一样 / 停止时两套一起停了**
旧版本只按 jar 文件名匹配进程，同名 jar 会撞在一起。现在按「进程工作目录是否在发布目录内」和「命令行里是否出现该 jar 的绝对路径」定位，两套环境能分开，停止时也只 kill 属于本配置的 PID（不再用 `pkill -f jar名`）。若仍不对，检查两套配置的「jar 包目录」是否填成了同一个路径。

**上传大 jar 报 413 / 文件过大**
调大 `spring.servlet.multipart.max-file-size`，默认已放到 1GB；或改用「本机路径发布」，不经浏览器上传。

**停止时提示 KILL_FAILED**
当前登录账号没有权限杀该进程（进程属于别的用户）。换成启动该进程的账号，或给账号 sudo 免密后在「启动前命令」里调整。

**开了 su 但提示 su 切换失败 / 一直要密码**
- 确认「目标用户」和「su 密码」填的是对的（是 su 要输入的密码，不是 SSH 登录密码）。
- 有些系统限制了 `su` 的使用（如只允许 `wheel` 组、`pam_wheel` 限制），先用同一账号手动 `su - root` 验证一下。
- 若服务器 `su` 被策略禁掉而只能用 `sudo`，可把目标用户/密码对应成 sudo 场景，或在代码里改造成 `sudo -S`。
- 极少数定制 PAM 要求 su 必须从真实终端读取（完全禁用非 tty），本工具的 PTY 方式仍可能失败，需在目标机放开相应限制。

**开了动态码后一直提示认证失败 / Too many authentication failures**
- 动态码是**拼在密码后面**提交的（密码 + 动态码），确认服务器的 MFA 约定就是这样（有的系统要求先密码后验证码两次交互，本工具不支持，需要网关侧调整）。
- 动态码通常 30~60 秒失效，输入后尽快操作；5 分钟内连接复用不会重复索要，超过 5 分钟空闲会重新弹窗。
- 认证失败时服务端会自动清掉已输入的动态码，重新操作会再次弹窗，重新输入最新的码即可。
