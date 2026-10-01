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

启动后先调用 `POST /api/session`，再携带返回的 Cookie 访问业务接口。`GET /api/health` 是唯一无需会话的探活接口。

Tauri 桌面壳位于 `desktop/src-tauri`，会启动同目录后端、等待健康检查并在退出时清理 Java 进程。需要安装 Tauri 2 的 Rust 工具链后，在 `desktop` 目录运行 `cargo tauri dev` 或 `cargo tauri build`。

## 数据与安全边界

本地服务不监听外部网卡；项目文件访问会阻止绝对路径、`..`、越界符号链接和排除路径。命令执行继承当前用户权限，但 Agent 必须通过审批；环境变量中的模型密钥不会传给项目命令。Git 操作、MCP 连接和模型请求不会绕过权限配置。

前端原型仍可直接打开 `prototype/index.html` 体验视觉交互。桌面壳中的下一步工作是将前端 localStorage 存储替换为已实现的 `/api` 客户端，接通真实任务、知识库、定时任务和账户设置。
