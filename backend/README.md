# Spring Boot 本地服务

Java 21、Spring Boot、Spring MVC、Bean Validation、Spring JDBC、SQLite。使用构造器注入；
SQLite 查询集中在 Repository，事务由 Spring 管理。

## 代码结构

```text
src/main/java/com/dongran/work/
  DongranApplication.java       # 启动类
  config/                       # 数据源、迁移、启动恢复
  controller/                   # 按业务拆分的 REST 接口
  dto/                          # 请求对象和 Jakarta Validation
  model/                        # 领域对象
  service/                      # 业务规则、审批、任务编排、调度
  repository/                   # 参数化 SQL 与数据库读写
  infrastructure/               # 模型调用、凭据存储、JSON 辅助
  security/                     # 本机会话、Host / Origin 校验
  exception/                    # 业务异常和统一响应
src/main/resources/
  application.yml
  db/V001__initial.sql
  db/V002__model_providers.sql
src/test/java/                   # Spring Boot 集成测试与调度测试
```

Controller 负责请求接收与校验，不直接执行 SQL。Project 使用领域 record；任务、文件、
命令、知识库等主要写接口使用 DTO。设置和模型工具参数等动态 JSON 仍使用 Map，
通过服务端 SettingsValidator 和各业务方法验证，不把前端校验作为安全边界。

## 开发与验证

在仓库根目录执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl backend spotless:check
.\mvnw.cmd package
.\mvnw.cmd -pl backend spring-boot:run
```

格式化使用 `.\mvnw.cmd -pl backend spotless:apply`。
开发启动也可执行 `.\scripts\start-dev.ps1 -Build -JavaHome <JDK21目录>`。
脚本从 `.runtime/live` 的 JAR 副本启动，后续 Maven 构建不会被正在运行的 JAR 占用。
默认数据目录为用户主目录下的 `.dongran-work`；通过 `DONGRAN_DATA_DIR` 或
`--dongran.data-dir=...` 指定测试目录。端口通过 `DONGRAN_PORT` 或 `--server.port` 配置。

前端静态资源由同一个 Spring Boot 进程提供，不需要另启 Node 服务。浏览器端到端测试：

```powershell
node prototype/verify-backend.cjs
node prototype/verify-workspace-pages.cjs
```

`verify-backend.cjs` 启动隔离的 SQLite、本地模型测试服务和真实后端，验证账户、
模型配置、任务 SSE、审批、实际文件写入、实际命令输出、继续对话、知识库、
计划保存以及后端重启后的数据恢复。测试不会使用个人模型密钥，也不把测试服务当作真实云端模型。
Windows 默认使用已安装的 Edge；其他系统使用 Playwright Chromium。

## 接口分组

| 模块 | 主要路径 |
| --- | --- |
| 会话与初始化 | `GET /api/health`、`POST /api/session`、`GET /api/bootstrap` |
| 设置与账户 | `/api/settings`、`/api/account`、`/api/settings/export`、`/api/settings/import` |
| 项目与文件 | `/api/projects`、`/api/directories`、`/api/projects/{id}/files`、`/api/projects/{id}/file` |
| 任务 | `/api/tasks`、`/api/tasks/{id}/messages`、`/api/tasks/{id}/cancel`、`/api/tasks/{id}/events` |
| 审批与命令 | `/api/approvals/{id}`、`/api/commands`、`/api/commands/{id}/cancel` |
| 模型与凭据 | `/api/models/status`、`/api/models/test`、`/api/credentials/{id}` |
| 模型供应商 | `/api/model-providers`、`/api/model-providers/{id}`、`/{id}/activate`、`/{id}/duplicate`、`/{id}/test`（后面三个路径均相对于 `/api/model-providers`） |
| 记忆与知识库 | `/api/memories`、`/api/knowledge`、`/api/knowledge/upload` |
| 定时任务 | `/api/schedules`、`/api/schedules/{id}/run`、`/api/schedules/{id}/runs` |
| Git 与工作树 | `/api/projects/{id}/git`、`/api/projects/{id}/worktrees` |
| Git 图形工作区 | `/api/projects/{id}/git/workspace`、`/api/projects/{id}/git/file-diff`、`/api/projects/{id}/git/operations` |
| 扩展与连接 | `/api/extensions`、`/api/extensions/{id}/run`、`/api/connections/{id}/test` |
| 活动统计 | `/api/activity` |

正常响应保留现有前端使用的 JSON 结构；错误响应包含 `code` 与 `message`。
`400` 表示输入无效，`401` 表示没有会话，`403` 表示权限或来源检查失败，
`404` 表示记录不存在，`409` 表示版本、状态或文件内容冲突。

## 执行与安全边界

- 只监听回环地址。会话引导端点无需已有 Cookie，但要求本机 Host、可信 Origin（若提供）、
  POST 和自定义客户端请求头。其他业务接口要求 Cookie 或 Bearer token；
  修改请求还要求 `X-Dongran-Client: desktop`。
- `runtime.json` 包含本机会话凭据，不应提交到版本库或分享。
- 文件 API 限制在注册项目范围内，检查越界路径、符号链接和排除路径；已有文件写入要求 SHA-256。
- Shell 命令经 Rust 助手在项目快照中隔离执行，默认禁止联网，成功后检查冲突并同步文件。
  Windows 使用 AppContainer，Linux 依赖 bubblewrap；macOS 尚未启用，隔离不可用时拒绝执行。
  Java 服务、Git 工具及外部模型连接不在命令沙箱内，仍遵循各自的应用权限。
- 规划模式在服务端拒绝工具调用；命令、钩子和写入遵守任务模式及审批设置。
- 任务取消会中断执行、结束子进程、使待审批请求失效；退出后未结束的记录在下次启动时恢复为中断状态。
- 调度只在服务存活时执行，不补跑应用离线期间错过的计划。手动执行和计划执行均有记录。
- 终端提供两种明确区分的模式：本机 PTY（持久 Shell、真实项目目录、系统用户权限），以及沙箱命令（逐条执行、项目快照、禁止联网）。本机 PTY 不提供给 Agent 工具调用。

## Agent 团队编排

主 Agent 先对任务意图做轻量分类，再在模型上下文中暴露匹配的专业角色：产品分析、架构设计、开发实现、代码审查、测试验证、安全审查、资料研究、文档整理、交互设计和工程运维。主 Agent 通过 `delegate` 工具按需分发，子 Agent 使用独立上下文，结果回传给主 Agent 汇总。

每个角色都有工具权限边界：产品、架构、研究、文档和交互角色默认只读；开发和工程角色可以修改项目；测试可以运行验证；审查和安全角色不能写文件。子 Agent 不能继续向下委派，避免递归失控；每个 Agent 最多 16 轮工具调用。任务事件会记录 `team_plan`、Agent 状态和工具调用，前端团队面板可读取 `/api/tasks/{id}/team`。

当前分发是同一 Spring Boot 进程内的虚拟线程协作，多个顶层任务可并发，单个任务内按依赖串行。后续可增加并行子任务、独立工作树、角色级模型和结果验收门禁。

## 当前未验证或未完成

### Git 工作区与供应商配置

右下角分支按钮打开 Git 工作区：本地更改、工作区 / 暂存区差异、暂存 / 取消暂存、
提交、单文件还原、本地分支创建与切换、最近 60 条提交的父子关系图，
以及 Fetch / 快进 Pull / 普通 Push。提交只读取暂存区，保留未暂存修改。
还原要求差异读取时的文件与索引指纹；不自动删除未跟踪文件，不进行强制推送。
选择目录须为仓库根目录；子目录项目会提示打开根目录。原有工作树管理入口保留在设置页。
目前没有图形化冲突合并、Rebase、Cherry-pick 或 Stash，不能视为完整 IDEA Git 功能替代。

模型设置支持独立供应商卡片、分类搜索、新增、编辑、复制、删除、单独测试和启用切换。
每套配置保存 API 根地址、模型 ID、Temperature、上下文上限和独立凭据标识。
API 根地址会追加 `/chat/completions`。目前仅支持兼容的 Chat Completions 协议；
不修改外部 Claude Code / Codex 的配置文件。测试非当前供应商不会切换当前配置。
切换后从下一次模型请求起使用新配置；正在进行的 HTTP 请求仍使用原配置。
复制不复制密钥；更新要求 revision 防止覆盖其他窗口的修改。
V002 自动迁移原有模型设置及凭据引用，普通偏好导入导出不包含供应商和密钥。

`DeveloperToolsIntegrationTest` 使用临时真实 Git 仓库验证索引与工作区隔离、还原指纹、
重命名取消暂存、首次提交前取消暂存、分支及路径限制、供应商 CRUD 和密钥隔离。
`verify-backend.cjs` 同时通过浏览器验证上述入口、模型请求地址 / 模型 / 独立密钥路由、
浅色与深色桌面布局、进程重启后的配置恢复。网络模型使用本地确定性测试服务。

### 后续边界

- 尚未使用用户的真实云端模型凭据进行联调。
- MCP / GitHub 的真实外部服务需要用户配置连接和凭据后验证。
- 工作树支持手动管理；自动按任务创建、初始化和清理策略尚未启用。
- 自动提炼记忆、跨应用桌面宠物、桌面托盘及三系统安装包仍属于后续桌面集成工作。
- 尚未进行冷启动、空闲内存、长时间运行或三系统性能验收。


### 知识库：本机导入、预览与检索

知识库支持多文件上传（每次最多 20 个文件，每个最多 10 MB），保留原文件，并按全局 / 项目范围保存。PDF、DOCX、DOC、XLS/XLSX、PPT/PPTX、ODT/ODS/ODP、RTF 和 UTF-8 文本由本机解析；扫描 PDF 可保存预览，但尚未集成 OCR。

预览为可关闭的独立弹窗：PDF 支持分页和缩放，DOCX 提供浏览器排版预览，Markdown 提供安全渲染，其余 Office 文件提供提取文本预览及原文件下载。DOCX 的复杂字体、分页可能与 Word 不同；不是所有格式都提供原始版式。预览组件按需加载，不使用在线预览服务。Markdown / DOCX 在禁用脚本和外部资源的 iframe 中呈现。

检索优先使用 SQLite 本地 Float32 向量（流式精确余弦 Top-K，不依赖独立服务），Embedding 未启用、无有效索引、接口失败或维度不匹配时回退 SQLite FTS5 BM25。中文采用字与双字分词，文档按段落切片。检索结果携带文档及片段序号，尚无 PDF 页码引用。更新、删除资料会同步更新索引，检索遵守项目与全局范围。BM25 无须模型或密钥。

“知识库 → 检索设置”分别配置 Embedding / Rerank 的 API 根地址、模型 ID、凭据（或复用供应商凭据）。兼容 `/embeddings` 的 OpenAI float 格式以及 `/rerank` 的 `results[index,relevance_score]` 格式。启用时确认具体地址、模型和发送范围；修改地址或模型后需重新勾选。Embedding 发送选中文档片段及搜索词，Rerank 发送搜索词和候选片段。保存设置不会自动上传资料。

模型配置各自提供“检验连通性”，调用 `POST /api/knowledge/retrieval/test/{embedding|rerank}`。检测当前草稿，优先使用刚填写的密钥，否则使用所选凭据；仅发送固定英文测试样例，不保存或启用配置、不发送资料。Embedding 校验向量数量、维度与有限非零数值，Rerank 校验结果索引和分数，显示耗时及输出规格。

切分规则：提取纯文本后以约 1200 个 Java UTF-16 字符为上限，在截断点往前寻找换行；换行位置超过当前片段起点 700 字符时优先使用该位置，相邻片段重叠约 160 字符，并避开 Unicode 代理对中间位置。这是字符窗口切分，不是 token 或语义切分；尚未按标题层级、表格行、代码函数或 PDF 页码保留结构。Embedding 与 BM25 使用同一片段集合。

在文档预览点击“建立向量索引”，后台以 16 个片段一批、单并发处理，界面显示已索引数量和错误；接口为 `POST/GET /api/knowledge/{id}/index`。索引队列最多 8 项，完全退出后停止，重开后可点击重建。向量持久化在 SQLite，文档修改/删除后失效；地址/模型改变后需重建。当前采用精确扫描，适合桌面中小知识库，大规模资料尚未使用 ANN 加速。

Rerank 可选，不配置也能使用向量检索；重排失败时保留原始检索排序。BM25 是本地词项检索，无需模型。查询接口返回实际模式及降级原因。测试仅使用本机模拟模型，真实服务兼容性需使用者填入自己的配置验证。

组件与许可：
- Apache Tika 3.2.3 Office 模块（Apache-2.0）：https://tika.apache.org/3.2.3/formats.html
- PDF.js 6.3.289（Apache-2.0）：https://mozilla.github.io/pdf.js/
- docx-preview 0.4.1（Apache-2.0）：https://github.com/VolodymyrBaydalka/docxjs
- Marked 18.0.14（MIT）、DOMPurify 3.4.16（Apache-2.0 或 MPL-2.0）、JSZip（MIT 或 GPL-3.0）。打包应保留依赖许可文件。

验证：`KnowledgeIntegrationTest` 覆盖文件解析、原始字节下载、无文本 PDF、异常文件、中文 BM25、项目隔离、索引删除、配置授权、本机模型接口、向量排序、重排和异常降级。构建后执行 `node prototype/verify-knowledge.cjs`（仓库根目录），在临时数据目录验证 10 种格式批量导入、PDF 翻页缩放、DOCX / Markdown 隔离预览、桌面布局、重启恢复和零外部网络请求。


### Rust 命令沙箱

Agent 的 `run_command`、任务钩子及内置终端的沙箱命令模式通过同一 `CommandService → SandboxExecutor → dongran-sandbox` 路径。图形化 Git 固定操作、文件工具、模型请求和 MCP 不在该进程沙箱内，继续遵循各自的授权规则。Agent 发起 Git 状态 / 差异查询需要单独的宿主 Git 审批，因为 Git 配置中的过滤器或文件监视器可能启动程序。`GET /api/sandbox/status?refresh=true` 返回实际能力；缺失助手或隔离自检失败会阻止执行，没有隐式宿主机回退。

每条命令建立独立快照，排除 Git 元数据、环境文件、凭据目录、链接 / Windows reparse points 及配置中的排除路径；上限 20000 个文件、单文件 32 MB、合计 256 MB。只对退出成功的命令回写，回写前核对所有目标的原始 SHA-256，逐文件原子替换；检测到冲突时停止并保留快照。多文件写入不是文件系统事务：回写期间的外部改动或 I/O 错误仍可能让部分文件先完成回写，结果会标记失败，用户需结合差异复核。取消与开始回写互斥，已经进入回写阶段的操作完成后才结束取消请求。

执行记录增加 `executionMode`、`sandboxBackend`、`workspacePath`、`syncStatus`。成功和失败的快照均保存在应用数据目录的 `sandboxes` 下，当前尚无自动清理策略。运行时磁盘总用量尚无跨平台硬配额；快照导入及结果扫描有大小限制，不等同于运行中的磁盘配额。

Windows 使用零网络能力的 AppContainer、独立 SID、仅限快照的文件权限及 Job Object。命令继承经过清理的环境，不获取模型密钥。默认 512 MB 作业内存、32 个进程、最长 3600 秒、合并输出最多 2 MiB。Linux 使用 bubblewrap，其内存限制为单进程虚拟地址空间（RLIMIT_AS），与 Windows 的作业内存上限不同；JVM/V8 等工具可能需要更大的地址空间，当前尚未完成 Linux 工具链兼容性验收。macOS 后端未启用。

默认禁网意味着 Maven/npm 不能联网安装依赖；排除 node_modules 等目录也会影响依赖可用性。Windows 仅声明可用工具目录，不修改宿主机工具链 ACL，自定义目录中未授权 AppContainer 读取的程序会失败。这一版本完成受限命令执行基础，不能当作所有开发工具都可直接运行的完整构建环境。

开发机先执行 `scripts/build-sandbox.ps1`（Linux 使用同名 `.sh`），再运行 Maven 测试。`SandboxIntegrationTest` 在 Windows 使用真实助手验证越界文件访问、成功 / 失败回写、冲突、取消和超时；`SandboxWorkspaceServiceTest` 验证链接、敏感路径与合并；`SandboxProtocolTest` 验证畸形消息不会被当成成功。发布包用户无需安装 Rust 或 Docker。

PDF 提取检查实际字形到 Unicode 的映射，不以符号数量判定乱码。发现不可映射字形或无文本层时保留原文件，标记 needs_ocr 并阻止错误片段入库；数学符号具有合法映射时正常保留。预览中的“重新提取文本”（POST /api/knowledge/{id}/reextract）更新提取状态并移除旧文本/向量索引。当前尚未集成 OCR，缺失映射的文件需要 OCR 才能恢复文字。

### 知识库引用与网页工具

检索结果的 `chunkId` 可通过 `GET /api/knowledge/{id}/fragments/{chunkId}` 获取实际文本范围，使用 UTF-16 偏移与前端字符串对齐。文档更新后旧 ID 失效并提示重新检索。列表结果及对话来源卡片直接打开提取文本预览，滚动到片段，使用浅色标记整段、深色标记匹配搜索词。PDF 当前定位到提取文本，不声称具有原页面坐标。

Agent 的 `search_knowledge` 结果以来源消息持久化，回答可使用 `[资料名称](knowledge://文档id/片段id)` 引用。刷新或重开任务后仍可点击。网页工具 `web_fetch(url)` 读取公开 HTML/文本正文，`web_search(query)` 使用 Bing RSS 返回最多 5 条标题、原始链接及摘要；只有“设置 → 权限 → 允许工具网络访问”开启时暴露。网页资料不能改变用户指令或权限。读取有超时、响应大小、跳转次数限制，不执行网页脚本，不访问本机或私网地址。搜索依赖外部 Bing 服务可用性，抓取不支持需要登录或 JavaScript 渲染的页面。

聊天默认跟随最新内容；向上翻阅会暂停跟随并显示圆形“回到最新”按钮，发送消息或点击按钮后恢复跟随。

### 意图路由与能力检测

`IntentRouter` 输出本轮目标、必要动作、查询、URL、禁止联网/只读约束、路由方式及完成条件。明确的知识库、URL、时效信息和项目结构请求走规则；特定含糊的上下文追问/对比/调研使用当前模型进行 JSON 分类，输出经过动作白名单校验。分类失败保守退回，不自动把内部资料作为网络搜索输入。提及已导入资料的完整名称时也会匹配本地检索。当前不是训练过的通用意图分类器，否定、隐含指代和复杂多目标仍需持续评测。

每轮从最新用户消息路由，修正旧实现沿用首次任务 prompt 的问题。必要只读步骤由后端先执行，不依赖原生工具调用；读取结果加入本轮参考上下文，来源/路由持久化并显示在对话中。基础检索回答无需额外模型配置。失败/权限未开放会返回真实错误；识别为执行任务但没有成功写入或命令记录时标为未完成。目前完成检查是最低执行证据门槛，并不是所有复杂任务验收条件的形式化证明。专业团队仅对执行型任务提供角色建议，角色选择仍有规则模板；复杂任务由主 Agent 按需调用 delegate。

供应商“能力检测”分别探测普通回答、JSON 结构输出、原生工具调用，发送固定测试样例，不执行返回的工具。结果按配置 revision 保存在本机，配置改变后显示未检测。`POST/GET /api/model-providers/{id}/capabilities` 用于执行/读取检测。“未验证”不等于断言服务永不支持该能力，连接成功也不等于工具能力通过。

点击回到最新和发送后的定位使用约 340ms ease-out 动画，尊重减少动态效果偏好；流式 token 不逐次重复启动动画，用户上滚可立即停止跟随。`verify-citations.cjs` 验证真实动画中间帧、用户打断、来源跳转、能力按钮和重启保留。


## 交互式终端

`TerminalService` 使用 pty4j（Windows ConPTY / Unix PTY），前端采用 xterm.js；不需要单独运行 Node 服务，也不要求用户安装 Docker。Windows 提供已安装的 PowerShell、PowerShell 7、CMD、Git Bash；Unix 提供可用的 Bash、Zsh、sh。PowerShell 使用无 profile 会话，并由新进程计算标准模块目录，不继承启动器注入的 PSModulePath。

- `/api/terminals`：列出、新建、关闭所有本机会话；新建必须携带明确选择标记 `confirmed=true`、有效项目与 Shell 配置。
- `/api/terminals/profiles`：本机可用 Shell；`/{id}/input`、`/{id}/resize`、`PATCH /{id}`、`DELETE /{id}`：输入、尺寸、重命名和关闭。
- `/api/terminals/poll`：批量增量读取输出，前端按 cursor 回放；内存限制 512 Ki 字符/会话、最多 12 个本机会话，终端滚动缓冲 10,000 行。进程输出不写入 SQLite。
- 本机模式保留目录、环境变量、交互输入和彩色输出，不受沙箱模式 120 秒命令超时限制。关闭面板继续运行；垃圾桶终止会话。页面刷新可重新连接后端保留的会话，应用重启不恢复进程。
- Tauri 退出前通过已认证本机接口关闭 PTY，再结束 Java；Spring 正常关闭同样回收会话。系统强制杀进程或应用崩溃不等于正常关闭，尚未做所有平台的崩溃恢复验收。
- 本机执行直接修改真实项目，联网能力取决于操作系统和网络配置；允许用户本机终端不等于给 Agent 解除沙箱。

验证：`TerminalServiceTest` 使用真实 PTY 测试目录/环境保持、交互读取、独立会话、Ctrl+C、尺寸调整及退出；`node prototype/verify-terminal.cjs` 使用隔离项目验证打包后的原生库、鉴权、多会话分屏、搜索、重命名、刷新恢复和真实沙箱命令。Windows 已执行验证，macOS/Linux 尚待实机验收。
