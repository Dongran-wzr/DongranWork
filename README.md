# Dongran Work

面向产品与研发团队的桌面 Agent 工作台。当前仓库包含前端原型、Spring Boot 本地服务和 Tauri 2 桌面壳。

## 技术方案

- **桌面端**：Tauri 2，使用系统 WebView，支持 Windows、macOS、Linux；窗口最低 1024 × 680。
- **本地服务**：Java 21 + Spring Boot 3.5.16，默认只监听 `127.0.0.1:3210`。
- **数据**：SQLite 单文件 + WAL，默认存放在 `~/.dongran-work`；服务退出时会中断未完成任务。
- **模型**：OpenAI-compatible Chat Completions 流式接口；模型密钥不写入 SQLite，优先使用 Windows DPAPI、macOS Keychain 或 Linux Secret Service。
- **通信**：本机短期 Cookie / Bearer token、JSON REST、任务 SSE 事件流。业务接口没有有效会话时返回 401。

## 后端能力

- 项目目录注册、浏览、文件树、文本读取与带 SHA-256 乐观锁的原子写入。
- Git 状态、差异、初始化、提交、远端获取和受限工作树。
- 主 Agent 调度产品、开发、测试 Agent；文件写入、命令执行、记忆写入、MCP 调用均支持逐次审批。
- 可取消任务、执行超时、任务事件持久化、SSE 增量输出和应用退出中断。
- 全局／项目记忆、需求文档与代码知识库；知识库支持 UTF-8 文本、Markdown、代码、PDF、DOCX 导入及 SQLite FTS 检索。
- 定时任务的每日、每周、单次计划；仅在应用运行或驻留托盘时调度，完全退出后停止。
- 扩展、钩子、MCP HTTP/SSE、GitHub 连接配置接口；钩子和连接仍受权限设置与审批控制。

## 构建和运行

仓库提供跨平台 Maven Wrapper。首次执行会从 Maven Central 下载 Maven 3.9.11，并校验 SHA-512：

```powershell
.\mvnw.cmd -DskipTests package
```

后端 JAR 位于 `backend/target/dongran-backend-0.1.0-SNAPSHOT.jar`。直接运行：

```powershell
$env:DONGRAN_DATA_DIR = "$PWD/.runtime/dev"
java -jar backend/target/dongran-backend-0.1.0-SNAPSHOT.jar
```

浏览器页面会自动建立本机会话。手动调用 `POST /api/session` 时需提供 `X-Dongran-Client: desktop`，服务会校验 Host / Origin；之后携带 Cookie 访问业务接口。`GET /api/health` 是公开探活接口。

当前推荐体验方式是运行本地服务，再打开 `http://127.0.0.1:3210`。可使用 `scripts/start-dev.ps1 -JavaHome <JDK21目录>` 后台启动，前后端由同一进程提供。Tauri 桌面壳位于 `desktop/src-tauri`，尚未完成三系统打包、托盘及生命周期验收。

## 数据与安全边界

本地服务不监听外部网卡；项目文件访问会阻止绝对路径、`..`、越界符号链接和排除路径。Agent 命令通过 Rust 原生执行助手运行，沙箱不可用时阻止执行，不自动降级为宿主机命令。应用自身、受控文件／Git 工具、MCP 连接和模型请求仍在宿主侧运行，分别遵循应用权限；不能将命令沙箱理解为整个桌面应用的隔离。环境变量中的模型密钥不会传给项目命令。

## Agent 命令沙箱

Java 负责授权与任务调度，`sandbox/` 中的 Rust 助手负责创建操作系统隔离环境、启动进程、转发输出、限制资源和终止任务。通信采用标准输入／输出的 JSON 行协议，不新增监听端口。默认禁止命令联网，执行前进行能力自检；状态接口为经过本机会话认证的 `GET /api/sandbox/status`，`?refresh=true` 可以重新检查。

Windows 开发环境构建助手：

```powershell
.\scripts\build-sandbox.ps1
.\scripts\start-dev.ps1 -Restart -JavaHome 'C:\path\to\jdk21'
```

macOS／Linux 开发环境构建助手：

```sh
sh scripts/build-sandbox.sh
```

构建机需要 Rust；最终用户不需要安装 Rust 或 Docker。可通过启动配置 `dongran.sandbox-helper`（环境变量 `DONGRAN_SANDBOX_HELPER`）指定受信任的助手绝对路径。桌面壳在发布模式下始终传递安装目录中的助手路径；开发启动脚本将已构建助手复制到 `.runtime/live`。未构建助手仍可使用页面与其他业务功能，但不能执行要求隔离的命令。

当前 Windows 后端使用 AppContainer + Job Object；Linux 后端依赖 bubblewrap 和系统允许的命名空间能力，尚待 Linux 实机验收；macOS 原生后端暂未启用，会明确返回不可用并阻止命令。默认禁网也意味着 Maven／npm 无法在线下载依赖。原生沙箱有平台与工具链差异，不能用 Windows 上的一次测试代表三系统全部验收。

具体构建、桌面资源准备与发布边界见 [desktop/README.md](desktop/README.md)。尚未完成的 JVM 内嵌、三系统安装包签名及系统实机验收不应视为已交付。

通过 HTTP 打开的工作台已经接入真实项目、任务 SSE、审批、命令、账户设置、记忆、知识库、定时任务与 Git 操作。直接以 `file://` 打开 `prototype/index.html` 则保留原型演示模式；HTTP 模式失败不会自动切回演示结果。

后端已按 `controller / dto / model / service / repository / infrastructure / security / config / exception` 分层。架构、接口分组、测试方式和未完成事项见 [backend/README.md](backend/README.md)。

验证命令：`./mvnw.cmd test`、`node prototype/verify-backend.cjs`、`node prototype/verify-workspace-pages.cjs`。端到端测试使用隔离数据和本地模型测试服务，文件、进程、数据库都是真实执行，尚未使用个人云端模型凭据验证。


### 网页搜索与知识库问答

- 设置 → 网页搜索：可选择 Bing RSS（无需密钥）、Tavily、Brave。Bing 的公开 RSS 在部分网络/地区可能返回无关页面或空结果；推荐配置搜索 API。API 密钥独立于对话模型密钥，使用现有系统凭据存储；未勾选记住或系统存储不可用时仅本次运行有效。
- 开启设置 → 权限与安全 → 允许工具网络访问，再点击“检验连通性”。检验保存当前配置，并发送固定的公开 Java 文档查询，不发送知识库内容。切换 API 后不会在失败时偷偷切回其他服务。
- 网页工具清理输出格式指令、检查免费搜索入口的结果主题；无相关结果时明确说明，不把已有知识当作搜索结果。搜索摘要不等于读取网页全文。
- 知识库对话先召回，再按文档标题相关性筛选上下文并按片段顺序组织，交给对话模型生成回答。回答末尾只展示实际引用且经过文档/片段 ID 校验的资料；点击可定位原文高亮。只列来源而未生成实质内容时重试一次，仍不合格则报告失败。
- Tavily/Brave 已有请求协议与响应映射测试；真实服务可用性、额度和鉴权需通过用户自己的密钥检验。
