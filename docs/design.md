# 设计说明

本文描述 java-chat-server 的线程模型、容量策略、并发语义与关键取舍，内容以 `main` 分支当前实现为准，不描述尚未实现的功能。协议与快速开始见根目录 [README.md](../README.md)。

## 1. 总体结构

```
                 accept 循环（main 线程）
                        │ submit
                        ▼
        ┌───────────────────────────────┐
        │  固定 30 线程池                │  每连接一个 ClientService 任务
        │  ClientService（读/解析/分发） │  每连接一个写线程（chat-writer-*）
        └──────────────┬────────────────┘
                       │ MessageSubmissionService.submit（offer）
                       ▼
        共享消息队列 LinkedBlockingQueue<Message>(1024)
                       │ take
                       ▼
        SendMsgService 转发线程（单线程）
                       │ dst.send()（offer 到目标出站队列）
                       ▼
        目标连接的出站队列 ArrayBlockingQueue<String>(256)
                       │ take
                       ▼
        目标连接写线程 → PrintWriter → Socket
```

组件职责：

- `Server`：绑定 23456 端口，accept 循环将每个连接提交给线程池；关闭时通知发送线程退出。
- `ClientService`：单个连接的会话编排——读取命令行、解析、调用账号或消息服务、管理在线状态，把响应交给本连接的写线程；不直接访问数据库。
- `AccountService`：承载用户注册查重、密码摘要、用户写入与登录凭据校验；通过 MyBatis 访问数据库，并返回保持既有协议文案的 `Result`。
- `MessageSubmissionService`：单聊与群发消息入队的唯一入口，负责登录状态与源身份校验、单聊接收者在线校验和队列满载拒绝。
- `SendMsgService`：唯一消费共享消息队列的转发线程，按 `message.isBroadcast()` 分流：单聊按目标 ID 查注册表投递；群发一次获取在线连接快照，排除发送者后向其余在线连接非阻塞投递。
- `OnlineRegistryUtil`：在线用户注册表（`ConcurrentHashMap<String, ClientService>`）。
- `OrderServiceImpl` / `MessageServiceImpl`：纯函数式的协议解析器。

## 2. 线程模型

| 线程 | 数量 | 生命周期 | 职责 |
|---|---|---|---|
| main | 1 | 进程启动到退出 | accept 循环；不直接处理任何连接 IO |
| 连接处理线程（池） | 固定 30 | 与连接等长（长任务） | 读命令、解析、数据库操作、submit 消息 |
| SendMsgService | 1 | 服务端启动到关闭 | 从共享队列取消息，投递到目标连接 |
| chat-writer-* | 每连接 1（守护线程） | 首次 `send()` 到 `close()` | 从出站队列取文本并写入 Socket |

几点推论与限制：

- 线程池为固定 30 线程的 `ThreadPoolExecutor`（配合 `SynchronousQueue` 与 `AbortPolicy`，零等待队列），且 `ClientService` 在工作线程内创建、写线程延后至首次 `send()` 启动。第 31 个及之后的连接在派发时会被立即拒绝并关闭 Socket，避免排队连接长期占用文件描述符、出站队列与写线程且无法触发读空闲超时。
- 连接处理线程与连接等长，因此 30 个线程意味着最多 30 个连接被**并发处理**。不应据此宣称支持大量活跃连接。
- 每连接写线程独立于读线程：阻塞写（对端不收数据导致 TCP 缓冲写满）只影响该连接自己，不会拖住转发线程或其他连接——这是"慢客户端隔离"修复（缺陷 4）的核心。

## 3. 在线注册表与并发登录

`OnlineRegistryUtil` 封装一个 `ConcurrentHashMap<String, ClientService>`：

- `register(id, client)` 使用 `putIfAbsent`，**单次原子操作**决定登录成败。两个连接同时登录同一账号时，恰好一个成功；注册成功后才设置 `ClientService` 的 `userId`，失败路径不会污染会话身份。
- `unregister(id, client)` 使用 `remove(id, client)` 双键校验连接所有权。旧连接（超时、断开）清理时只能移除自己注册的记录，无法误删后继新连接——这解决了"旧连接注销覆盖新登录"的问题（缺陷 1 的另一半）。
- `getClient` / `isOnline` / `getOnlineIds` / `snapshotOnlineClients` 为只读查询或快照拷贝：`getOnlineIds` 返回在线 ID 列表拷贝；`snapshotOnlineClients` 返回 `Map<String, ClientService>` 的独立浅拷贝（供群发转发遍历，修改返回 Map 不影响注册表结构，且已取得的连接引用不会因同账号重登换连接而被重定向）。

注意：`ConcurrentHashMap` 的单操作原子性不等于组合操作原子性，本项目的正确性依赖"注册用 putIfAbsent、清理用 remove(id, client)"这一约定，而不是 Map 本身。

## 4. 容量与背压

两级有界队列把"消息过多"的拒绝点放在明确的位置：

### 4.1 共享消息队列（1024）

`MessageSubmissionService.submit(currentUserId, message)` 是唯一入队入口，顺序执行以下检查与分流：

1. **登录与源身份校验**：`currentUserId` 为 null 时直接返回 `用户未登录`；`message.srcId` 必须等于 `currentUserId`，防止伪造发送者；
2. **单聊与群发分流（`message.isBroadcast()`）**：
   - **单聊（`broadcast = false`）**：先检查 `dstId` 是否在线，不在线直接返回 `接收者离线`；在线则向共享队列 `offer`；
   - **群发（`broadcast = true`）**：跳过单个目标的在线检查，直接将一条广播任务向共享队列 `offer` 一次（满载时整体拒绝，不会发生部分入队）；
3. **满载拒绝**：`offer()` 返回 false 时返回 `服务器繁忙`，**不阻塞**连接处理线程。

成功时返回 `消息已加入发送队列`。注意语义边界：这只表示消息进入服务端内部队列，**不等于写入 Socket，更不等于对端收到**。群发消息在 `SendMsgService` 消费时通过 `snapshotOnlineClients()` 取得当次在线连接集合、排除发送者 `srcId` 后逐个调用 `send()`，慢接收者或满载接收者同样由各自的 256 出站队列隔离，并复用按接收者聚合的丢弃日志。

### 4.2 每连接出站队列（256）

`ClientService.send(text)` 将文本 `offer` 进本连接的出站队列：

- 写线程消费队列并以 `print` + `'\n'` + 显式 `flush` 写出（服务端输出固定 LF，不依赖服务器 OS）；`PrintWriter` 会吞掉 IO 异常，因此每次写入后用 `checkError()` 检测写失败，失败则停止写循环并关闭连接。
- 队列满时 `send` 返回 false，两种调用方的处理不同：
  - 本连接自己的响应发送失败 → 读循环直接 break，进入清理（连接本就要关闭）；
  - `SendMsgService` 转发他人消息失败 → **该条消息被丢弃**，转发线程继续服务其他消息。丢弃日志按接收者聚合：首条 WARN，此后每累计 1000 条输出一条汇总，接收者恢复入队、离线或转发线程退出时报告未汇总尾数与累计总数。压测实测（6 发送端 × 10 秒灌同一慢接收端）约 1.9 万次丢弃仅产生 20 条丢弃日志；丢弃语义不变，仍为"入队成功不等于送达"。

### 4.3 为什么不用无界队列

原始实现使用无界 `LinkedBlockingQueue`，慢客户端会使消息在队列中无限堆积（内存风险），且单线程逐个同步写会让一个慢客户端阻塞所有其他用户的消息转发（队头阻塞）。改为"有界 + offer 快速失败"后，过载表现为**明确的部分拒绝**而非进程不可用，这是演示项目选择简单拒绝策略（而非等待、降级或持久化重试）的原因。

## 5. 连接生命周期与清理

```
accept → 发欢迎语 → 客户端空行 → 命令循环 ─┬→ 003 登出成功 → 跳出循环
                                          ├→ readLine() == null（EOF）
                                          ├→ SocketTimeoutException（60 秒读空闲）
                                          └→ IOException
                                                  │ finally
                                                  ▼
                              unregister(userId, this)（已登录时）
                                                  │
                                                  ▼
                                        close()（幂等）
```

- **读空闲超时与单行读取限制**：`Socket.setSoTimeout(60_000)` 单独使用时只约束单次 `read()` 的阻塞等待，无法限制整条命令的累计完成时间，且原生 `readLine()` 不限制单行长度。`ClientService.readBoundedLine` 在读取首字符前保持 `readTimeoutMillis` 空闲超时；收到首字符后，以 `readTimeoutMillis` 作为整行剩余完成期限动态收紧 `setSoTimeout`，超时抛出 `SocketTimeoutException`；同时将不含行尾分隔符的单行最大长度限制为 `1024` 字符（兼容 LF 与 CRLF），超限立即抛出 `IOException` 关闭连接。欢迎阶段与命令循环均复用同一读取逻辑。注意它**不是**写超时，写阻塞由独立写线程隔离解决。
- **close() 幂等**：`AtomicBoolean.compareAndSet` 保证无论从哪条路径（读循环 finally、写线程错误检测、外部测试）进入，清理只执行一次。
- **写线程回收**：close 时先向出站队列投递哨兵 `"\u0000"`（256 容量已满时 offer 失败，哨兵丢失），等待 300ms；随后关闭 Socket 并中断写线程再等 1s；写线程仍未退出则记录 warn。哨兵 + 中断 + Socket 关闭三重机制保证正常情况下写线程必然退出，已验证断连后 0 残留。
- **登出安全**：只能登出 `userId` 与参数一致的自己；登出成功后 `userId` 置空，登出响应入队后即退出命令循环并关闭连接。若需继续通信，客户端必须重新建立连接并登录。

## 6. 协议解析语义

- `OrderServiceImpl.splitOrder`：`split(",", 2)`，只对命令码 trim，剩余部分整体保留为 content。空串 / null / 空白命令码返回 null；只有命令码（未出现逗号，如 `004`、`006`）时 `content` 为 `null`，带逗号但后续为空（如 `006,`）时 `content` 为空串 `""`，以此区分缺省参数与空字符串参数；未知命令码返回 `order=null` 的 Order 对象，由 `ClientService` 转为 `000,FAIL,命令解析错误`。
- `MessageServiceImpl.splitMessage`：对 `null` 输入或 `split(",", 3)` 不足 3 段的输入记录 WARN 并返回 `null`；正文中的后续逗号全部保留；`Alice,Bob,` 会得到空正文消息（字段是否为空由后续业务判断，解析器不校验）。

## 7. 数据访问

- MyBatis 3.5.6，POOLED 数据源，连接参数来自 `mysql.properties`（本地文件，已忽略；仓库提供 `.example` 示例）。Java 应用目前不读取环境变量。
- `user` 表：`id VARCHAR(32)` 主键、`password CHAR(32)`（MD5 摘要）。`AccountService` 负责注册与凭据校验，二者均使用 `openSession(true)` 自动提交的短会话；`ClientService` 只负责调用服务与编排登录注册表，不再直接依赖 `SqlSession`、`UserDao` 或 `Md5Util`。
- 密码仅 MD5 摘要存储是课程项目口径，已在 README 已知限制中声明。

## 8. 关键取舍回顾

| 问题 | 方案 | 取舍 |
|---|---|---|
| 连接准入上限与写线程生命周期 | 30 线程 + `SynchronousQueue` + `AbortPolicy`，超额立即关闭；写线程延后至首次 `send()` 启动 | 不保留等待连接队列，换取超额连接不占用 Socket/写线程且不绕过读超时 |
| 重复登录竞态 | `putIfAbsent` 原子注册 | 不加锁；正确性依赖注册表封装，而非调用方约定 |
| 慢客户端阻塞全局转发 | 每连接写线程 + 256 出站队列 | 多一个线程开销，换取转发线程永不被单个连接卡住 |
| 消息队列无界 | 1024 共享队列 + offer 满载拒绝 | 过载时丢新消息（返回"服务器繁忙"），不做等待或持久化 |
| 在线群发扇出 | 共享队列单任务入队 + 转发线程取连接快照扇出 | 提交阶段单次 `offer` 避免部分入队；转发阶段取独立 Map 浅拷贝并按每连接写队列隔离慢接收者，不承诺全表原子时刻快照 |
| 饱和期丢弃日志刷屏 | 丢弃日志按接收者聚合（首条 + 每 1000 条汇总 + 尾数上报） | 尾数在无后续触发点时，统计状态会保留至该接收者下一次转发处理或转发线程退出；换来自洽的丢弃计数与饱和期可读日志 |
| 发送线程退出 | 停止标志 + Server 中断 | 收到中断即退出，不承诺排空；完整优雅关闭未实现 |
| 失联连接与慢速/超长输入 | 60 秒读空闲超时 + 首字符后整行读取期限 + 1024 字符单行上限 | 防止无换行超长输入撑爆内存或慢速滴灌绕过 `SO_TIMEOUT` 长期占用连接线程 |
| PrintWriter 吞异常 | 每次写后 `checkError()` | 检测有延迟（写失败在下一次 checkError 才被发现），但足够驱动清理 |

## 9. 已验证行为与边界

自动化测试（`mvn test`，50 项，全部不依赖数据库）：

| 测试类 | 数量 | 验证目标 |
|---|---|---|
| `ServerTest` | 2 | 连接准入满载拒绝并立即关闭、不新增写线程、已有连接继续服务、释放容量后恢复接入、单连接初始化异常不中断后续接入（真实 loopback TCP） |
| `MessageTest` | 3 | 三参数构造器默认单聊；`Message.broadcast` 群发标志、null 目标与含逗号/空正文保留 |
| `OnlineRegistryUtilTest` | 4 | putIfAbsent 只允许一个并发登录；旧连接清理不影响新连接；在线连接快照结构隔离与连接替换后原快照保持 |
| `MessageSubmissionServiceTest` | 9 | 单聊与群发满载限时拒绝、未登录拒绝、身份不匹配拒绝、单聊离线拒绝、成功恰好入队一次、日志上下文回归 |
| `SendMsgServiceTest` | 8 | 慢客户端写阻塞时单聊与群发其他用户正常收发；群发扇出且不回显发送者；仅发送者在线边界；满队列拒绝；关闭排空与写线程退出；构造不启动写线程、关闭后不再启动、并发首次发送仅启动单写线程 |
| `ClientServiceBroadcastCommandTest` | 2 | 已登录 `006` 群发命令入队与 ACK（含逗号、`006,` 空正文与 `006` 缺省参数拒绝）；未登录 `006` 群发命令拒绝（真实 loopback TCP） |
| `ClientServiceProtocolLineEndingTest` | 1 | 服务端协议输出固定 LF、原始字节无 `\r`（真实 loopback TCP） |
| `SendMsgServiceDropLogAggregationTest` | 3 | 丢弃日志按接收者聚合：首条、1000 条汇总、恢复 / 转发线程退出 / 离线尾数 |
| `ClientServiceReadTimeoutTest` | 5 | 读空闲超时清理；EOF 清理；正常活动与 1024 字符边界保持在线；慢速滴灌无换行超时清理；超长输入行立即清理 |
| `OrderServiceImplTest` | 8 | 命令解析的正常（含 `006` 群发命令）、无逗号 `null` 与带逗号空串边界、异常输入 |
| `MessageServiceImplTest` | 5 | 消息字段映射、正文逗号保留、空正文、默认单聊标志、`null` 输入与字段不足 |

手工与端到端验证：注册 / 重复注册 / 登录 / 错误密码 / 重复登录 / 在线查询 / 含逗号消息转发 / 登出 / 离线列表更新；双实例端口冲突退出；读超时约 60 秒生效；端到端登出响应送达、断连后写线程 0 残留。AccountService 的重复注册补验使用两个独立 TCP 连接与真实 MySQL，第二次返回 `101,FAIL,用户ID已存在`。

### 9.1 压测与容量观测

2026-10-31 在 `main@a4d80f3` 完成首轮压测，2026-11-03 在日志聚合实现上复测 saturation。服务端与负载生成器均在本机，全部数字是**单机 loopback + 单进程负载生成器**的观测值，不是公网或多机容量承诺。

| 场景 | 观测结果 | 说明 |
|---|---|---|
| capacity：40 并发连接 | 30 个立即收到欢迎语，10 个排队；释放 10 个活跃连接后，排队连接 10/10 获得服务 | 当时使用无界任务队列的观测值；后续已改为 `SynchronousQueue` + `AbortPolicy` 零等待准入，超额连接立即关闭（由 `ServerTest` 验证） |
| accounts：24 账号 | 注册与登录均 24/24 成功；重复登录与错误密码被拒绝；在线列表与登出正确 | 经 AccountService 与真实 MySQL；重复注册另于 2026-11-03 补验 |
| throughput：10 对客户端 × 15 秒 | ACK 与送达均为 58,755，约 3,916 msg/s；p50 0.3 ms、p95 0.6 ms、p99 1.2 ms；入站队列满载 0 次 | 正常负载下未接近两级队列饱和，ACK 与送达一致只适用于该轮场景 |
| saturation：6 发送端 × 10 秒（聚合前） | 23,678 次 ACK，约 73% 消息被丢弃，产生 17,254 条逐条丢弃 ERROR | 复现慢接收端的丢弃语义与日志刷屏问题 |
| saturation：同场景复测（聚合后） | 24,294 次 ACK，送达 5,423，丢弃 18,871；18 次千条汇总 + 871 尾数，共 20 条丢弃 WARN | 计数闭环；两轮是独立运行，不把 ACK、送达或吞吐差异解释为性能提升 |

压测同时确认：慢接收端停止读取会使其出站队列及本机 TCP 缓冲逐渐饱和，转发线程在持续丢弃该接收者的消息时仍能继续消费共享队列；入站 1024 队列的满载拒绝未在这些端到端场景中触发，仅有单元测试覆盖。

未验证 / 未覆盖：

- 公网或跨机网络、TLS、非本机客户端行为；
- 大量活跃连接、长时间老化与连接反复进出；
- 1024 入站队列满载的端到端复现；
- 服务器完整优雅关闭。
