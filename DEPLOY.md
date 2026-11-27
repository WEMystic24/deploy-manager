# deploy-manager 部署逻辑与发布配置说明

> 依据源码整理：`DeployService` / `DeployTarget` / `DeployController` / `SshSession` / 前端 `index.html` / `application.yml`。
> 本工具本身是 Spring Boot 2.7 + Java 8 + jsch 的单体应用，部署在操作机（内网）上，通过 SSH/SFTP 把产物发布到目标服务器。

---

## 一、整体模型：服务器（ServerConfig） × 发布配置（DeployTarget）

- **服务器**：只保存"怎么连上去"的信息（地址、端口、账号密码/私钥、su 提权）。一台服务器一条记录，密码 AES 加密存在 `~/.deploy-manager/servers.json`。
- **发布配置（发布目标 DeployTarget）**：保存"发布什么、放到哪、怎么启动"。**一台服务器可以挂多个发布配置**（如同一台机器上跑订单服务、网关服务两个 jar，或一个后端 + 一个前端站点），每个配置有独立的目录、jar 名、启动参数。
- 所有发布 / 启停 / 回滚接口都以 `targetId`（发布配置 id）为入参。

## 二、发布到服务器的完整逻辑（后端 jar 类型）

所有操作均为**异步任务**：接口立即返回 `taskId`，后台线程执行，前端通过 SSE（`/api/deploy/tasks/{taskId}/stream`）实时看到每个步骤的状态、上传百分比与速度。

后端发布的步骤序列（备份步骤固定存在，「是否重启」可选）：

```text
连接服务器 (SSH 普通账号登录；开启 su 时所有命令以 PTY 交互式 su 到目标用户执行)
  1. 准备远程目录     mkdir -p 发布目录、日志目录（备份目录在备份时按需创建）
  2. 备份旧版本      始终执行：cp -p app.jar <备份目录|jar 包目录>/app.jar.20260818120000
                     conf 目录下的配置文件同样各备份一份；
                     备份目录不可用 → 自动回退到 jar 包目录，不中断发布
  3. 上传 jar 包      SFTP 先传到中转目录 /tmp/deploy-staging/<uuid>.jar
                     （登录账号必定可写），再以提权身份
                     cp 到 发布目录/app.jar 并删除中转文件
                     —— 目的：目标目录（如 /opt）需要 root 权限时也不报 Permission denied
  4. 清理历史备份    始终执行，按 keep-backup-count 保留最近 N 个
  5. 停止应用        按「jar 名 + 进程 cwd 在发布目录内 / 命令行含 jar 绝对路径」精确定位 PID
                     → kill(SIGTERM) → 等待 stop-wait-seconds 秒 → 未退再 kill -9（不用 pkill -f，避免误杀同名 jar）
  6. 启动应用        cd 发布目录 && nohup <javaCmd> <jvmOpts> -jar <jarName> <appArgs>
                     >> info 日志文件 2>&1 &  ，写 pid 文件，
                     等待 start-wait-seconds 后复核进程确实存活（双保险判定）
```

要点：

- **进程定位**按 jar 文件名/完整路径匹配，并排除工具自身进程（`deploy-manager`），同机多应用不会误杀。
- 远程命令统一 `bash -l -c` 执行（加载 `/etc/profile`，避免 `java: command not found`）；su 模式下命令先写成 `/tmp/deploy-*.sh` 再 PTY 交互执行，**密码不落在命令与日志里**。
- **启动成功判定** = 脚本输出 `DEPLOY_STARTED_OK` 标记 **且** 独立探测进程存活，任一不满足即失败并提示查日志。
- 上传来源有三种：浏览器直传、分片上传（断点续传，合并后走本地文件流程）、本机路径直推。
- 未勾选重启时，发布止于"jar 已就位"，不触碰进程。

## 三、前端静态资源类型的发布逻辑

步骤：`连接 → 准备目录 → 备份 tar.gz（始终）→（清空目标目录）→ 上传 → 执行发布后命令`

- 目标目录 = `发布目录 / 本机发布路径的末级目录名`（自动推导，如 `frontendPath=/opt/build/web` → 发布到 `remoteDir/web`）。
- **压缩成 zip**：勾选后把本机路径打成 `<folder>.zip` 上传，通常配套"发布后命令"里自动生成的 `unzip` 命令解压；上传的本身是 zip 则直接上传，避免套娃。
- **更新方式**：
  - 覆盖更新（默认）：同名文件覆盖，旧目录中已删除的文件残留；
  - 清空后更新：先 `rm -rf` 整个目标子目录再重建，得到干净目录。安全保护：目标即发布根目录（推导文件夹名为空）或文件夹名含 `..` 时拒绝清空，自动降级为覆盖更新。
- **发布后命令**：文件推送完成后在目标服务器执行（解压 / 复制到 nginx 目录等）。返回码非 0 **不**判失败，只在详情里记录（避免附加命令误报整体失败）。
- 回滚 = `tar xzf` 解压备份覆盖目标目录 + 重跑发布后命令；无进程启停。

## 四、回滚（后端）

从备份位置（配置的备份目录，或 jar 包目录 / 发布目录里的回退位置）选一个历史备份 → `cp -p <备份文件> → 发布目录/jar名` →（可选）停止 + 启动。备份名做合法性校验（禁 `/`、`..`），查找时两个位置都会尝试。

---

## 五、发布配置各项字段解释

### 5.1 通用字段（后端 / 前端都有效）

| 字段 | 必填 | 解释 |
| --- | --- | --- |
| 类型 type | 是 | `backend`（Java 应用，走 jar 发布+启停流程）或 `frontend`（静态资源，走目录/zip 上传+发布后命令流程） |
| 配置名称 name | 是 | 展示名，如「订单服务」；操作记录、看板均用它标识 |
| 发布目录 remoteDir | 是 | 服务器上的目标目录，必须绝对路径（如 `/opt/app/order`）。**后端界面上叫「jar 包目录」**（jar 所在目录，应用也在此启动）；前端 = 部署根目录（实际落在其下的子文件夹）。不存在自动 `mkdir -p` |
| 备份目录 backupDir | 否 | **留空 = 就地备份**：后端把旧 jar 备份在 jar 包目录、conf 文件备份在 conf 目录（文件名加时间戳后缀）；前端把发布目录打成 tar.gz 存到 `发布目录/backup`。**填写则统一备份到该目录**；该目录不存在或无写权限时自动回退到发布目录 / jar 包目录，发布不会因此失败 |
| 发布前备份 | — | **已取消开关，发布前始终备份**（旧 jar、conf 文件；前端是发布目录 tar.gz），每个位置保留最近 `deploy.keep-backup-count` 个（默认 10）。回滚列表可同时看到「备份目录」和「jar 包目录 / 发布目录」两处的备份 |

> **填路径不用手敲**：「发布目录 / conf 目录 / info 日志文件 / error 日志文件 / 备份目录」右侧都有「浏览」按钮，弹窗内直接浏览该服务器的目录：点目录进入、点「选此目录」或「选它」即回填，底部还能「复制路径」。字段里已有路径时从该路径开始（日志取其所在目录），目录不存在会自动回退到上级目录。

### 5.2 后端专属字段

后端类型下还有一层「部署方式」子分类（`deployMode`）：

| 部署方式 | 说明 |
| --- | --- |
| 默认部署 `default` | 与历史行为完全一致：单个 jar，自动生成 `nohup java -jar <jar名> >> <info 日志文件> 2>&1 &`，可选「本机路径发布」；另可选填「自定义启动命令」覆盖默认命令 |
| 自定义部署 `custom` | 可一次选择多个 jar 包统一上传更新；同样支持「自定义启动命令」；不含「本机路径发布」 |

| 字段 | 必填 | 解释 |
| --- | --- | --- |
| jar 包名称 jarName | 默认部署必填 | 远端统一文件名（如 `order-service.jar`），上传的任何文件名都会改成它。同时是进程定位（pgrep/pkill）与备份文件名前缀的依据 |
| 部署方式 deployMode | 否 | `default` / `custom`，仅后端类型有效，默认 `default` |
| jar 包列表 jarNames | 自定义部署 | **由上传的 jar 文件自动识别，页面不提供手填**。上传时**保持原文件名**（不重命名）；启停识别、备份与回滚都以该列表为依据，可在弹窗里点标签的 × 剔除 |
| 主 jar 包 primaryJar | 否（自定义部署可选） | **手工填写**（如 `order-api.jar`），多 jar 时**状态查询 / 看板 PID / 启动后存活判定只认这个 jar**（一般填主服务的 jar）；留空则不指定，任一 jar 运行即算运行、PID 取定位到的第一个。**停止、备份、回滚始终覆盖全部 jar**。填完整路径时只取末级文件名 |
| 自定义启动命令 startCommand | 否 | **默认部署 / 自定义部署通用**，填写后**完全覆盖**默认启动命令：不自动 cd 到 jar 包目录、不校验 jar 是否存在，按原文执行（需后台运行）；留空仍用默认命令。如需在 jar 目录下启动请自行写 `cd {remoteDir} && …`。支持占位符 `{remoteDir} {confDir} {jarName} {jarPath} {jars} {logFile} {pidFile} {javaCmd} {jvmOpts} {appArgs} {backupDir}`，`{jars}` 展开为全部 jar 的完整路径 |
| conf 目录 confDir | 否 | yml / properties 等配置文件所在目录，必须绝对路径；留空默认 `jar 包目录/conf`。发布时若已配置会自动 `mkdir -p` |
| 上传 conf 文件 | 否 | 选择后在弹窗里点「保存并上传文件」才会上传到 conf 目录（**同名覆盖，不重启应用**，只取文件名不保留子目录）；只点「保存」不会上传。接口 `POST /api/deploy/{id}/conf-upload` |
| java 命令 javaCmd | 否 | 默认 `java`。远端 java 不在 PATH 时写绝对路径，如 `/usr/local/jdk1.8/bin/java` |
| JVM 参数 jvmOpts | 否 | 拼在 `-jar` 之前，如 `-Xms512m -Xmx1024m -Dfile.encoding=UTF-8` |
| 启动参数 appArgs | 否 | 拼在 jar 之后，如 `--spring.profiles.active=test --server.port=8080` |
| info 日志文件 infoLogFile | 否 | 留空默认 `jar 包目录/logs/<jar名去扩展名>.log`。默认启动命令的输出重定向到这里，运行日志页「info」页签读它；占位符 `{logFile}` 等同 `{infoLogFile}` |
| error 日志文件 errorLogFile | 否 | 留空默认 `jar 包目录/logs/<jar名去扩展名>-error.log`。通常由应用日志框架（logback 等）单独写入，运行日志页「error」页签读它；显式填写时发布会为其创建所在目录 |
| pid 文件 pidFile | 否 | 留空默认 `jar 包目录/<jar名去扩展名>.pid`。启动时写入 PID，停止时优先据此优雅 kill |
| 启动前命令 preStartCmd | 否 | 在 nohup 启动前执行，如 `source /etc/profile`、通知脚本 |
| 启动后命令 postStartCmd | 否 | 启动成功并确认存活后执行 |
| 本机路径 localPath | 否 | **仅默认部署**：本机 jar 路径（如 `target/app.jar`），保存后可一键"用本机路径发布"，适合本地 Maven 打包后直推 |
| 发布后重启 restartLocal | 否 | **仅默认部署**：本机路径发布后是否自动走停止+启动，默认勾选 |
| 选择 jar 文件上传 | 否 | 默认部署选单个 jar、自定义部署可多选；选择后需点弹窗里的「保存并上传文件」才会上传（**只更新文件，不备份、不停止、不重启**）。只点「保存」仅保存配置，不会上传也不会部署；完整发布请用卡片上的「一键发布」 |

页面另有「启动命令预览」：随以上字段实时生成最终启动命令，尖括号 `<…>` 表示该项尚未填写。

### 5.3 前端专属字段

| 字段 | 必填 | 解释 |
| --- | --- | --- |
| 本机发布路径 frontendPath | 是 | 本机静态资源目录或产物路径（如 `/Users/me/project/dist`）。**其末级目录名自动成为服务器上的部署子文件夹名**（发布到 `remoteDir/dist`） |
| 压缩成 zip zip | 否 | 勾选后打成 `<末级目录名>.zip` 上传，并自动生成在服务器解压到发布目录的「发布后命令」；不勾选则逐文件递归上传整目录（带总进度） |
| 更新方式 clearBeforeDeploy | 否 | 覆盖更新（默认，同名覆盖、已删文件残留）或清空后更新（先删整个目标子目录再重建，目录干净；目标为发布根目录时为安全自动降级为覆盖） |
| 发布后执行命令 frontendPostCmd | 否 | 文件推送完成后在目标服务器执行的命令（解压、`cp -r ./* /usr/share/nginx/html/` 等）；返回码非 0 不影响发布结果判定 |
| 备份（前端） | — | 始终执行，无需开关：把发布目录打成 `<folder>.<时间戳>.tar.gz`，未填备份目录时存 `发布目录/backup`，填了则存到该目录；目录不可用回退到发布目录根 |

### 5.4 服务器级字段（在"服务器配置"里维护，发布配置依赖它们）

| 字段 | 必填 | 解释 |
| --- | --- | --- |
| 名称 / 地址 / SSH 端口 | 是 | 连接信息，端口默认 22 |
| 认证方式 / 账号密码 / 私钥 | 是 | 密码或私钥登录；密码 AES 加密存储，编辑留空 = 沿用 |
| 登录动态码（动态码开关） | 否 | 开启后首次连接前弹窗输入动态码，实际提交给服务器的密码 = 登录密码 + 动态码；动态码只存内存（缓存 5 分钟），不落盘。缺码且没有可复用连接时后端返回 428，前端弹窗后自动重试原操作。连接一直存活时不会因缓存过期而重复索要 |
| su 提权（目标用户 + su 密码） | 否 | 生产机禁 root 直连时开启：所有远端操作（建目录/上传移动/启停/备份/回滚）自动以 su 后身份执行；上传借 `/tmp` 中转。su 密码与登录密码独立 |
| 所属分组 | 否 | 服务器分组管理 |

---

## 六、与发布行为相关的全局配置（application.yml）

| 配置项 | 默认 | 解释 |
| --- | --- | --- |
| `deploy.staging-dir` | `/tmp` | 远程中转根目录。上传先落到其子目录再 cp 到目标路径，绕开登录账号对 `/opt` 等目录无写权限的问题 |
| `deploy.keep-backup-count` | 10 | 勾选备份时，每个应用保留的历史备份个数，超出自动清理 |
| `deploy.stop-wait-seconds` | 20 | 停止时等优雅退出的秒数，超时 `kill -9` |
| `deploy.start-wait-seconds` | 8 | 启动后等待几秒再复核进程存活 |
| `deploy.connect-timeout-seconds` / `exec-timeout-seconds` | 15 / 180 | SSH 连接与单条命令执行超时 |
| `deploy.upload-dir` | `~/.deploy-manager/uploads` | 浏览器上传的 jar 在本机的落地目录 |
| `deploy.secret-key` | 需修改 | 密码 AES 加密密钥，改后旧密码需重新录入 |
| `spring.servlet.multipart.max-file-size` | 1024MB | 浏览器直传单文件上限（大包建议走分片或本机路径发布） |

## 七、一次典型发布的时序

```text
页面点击「发布」
 → POST /api/deploy/{targetId}/upload（或 local / chunk-finish / frontend）
 → 立即返回 taskId；DeployService 在线程池中执行
 → 前端 EventSource 订阅 /api/deploy/tasks/{taskId}/stream
    snapshot（步骤清单）→ 每步 step（RUNNING/SUCCESS/FAIL + 进度/速度）→ done/error
 → 任一步失败：当前步骤标 ✗ 并给出原因（如 Permission denied 提示开 su），
   后续步骤标灰；操作记录写入 ~/.deploy-manager/operation.log
```
