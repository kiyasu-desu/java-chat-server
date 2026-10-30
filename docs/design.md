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
- `ClientService`：单个连接的会话编排——读取命令行、解析、执行（含数据库访问）、把响应交给本连接的写线程。
- `MessageSubmissionService`：消息入队的唯一入口，负责身份校验、在线校验和满载拒绝。
- `SendMsgService`：唯一消费共享消息队列的转发线程，按目标 ID 查注册表并投递。
- `OnlineRegistryUtil`：在线用户注册表（`ConcurrentHashMap<String, ClientService>`）。
- `OrderServiceImpl` / `MessageServiceImpl`：纯函数式的协议解析器。

## 2. 线程模型

| 线程 | 数量 | 生命周期 | 职责 |
|---|---|---|---|
| main | 1 | 进程启动到退出 | accept 循环；不直接处理任何连接 IO |
| 连接处理线程（池） | 固定 30 | 与连接等长（长任务） | 读命令、解析、数据库操作、submit 消息 |
| SendMsgService | 1 | 服务端启动到关闭 | 从共享队列取消息，投递到目标连接 |
| chat-writer-* | 每连接 1（守护线程） | 连接建立到 close | 从出站队列取文本并写入 Socket |

几点推论与限制：

- 线程池为 `Executors.newFixedThreadPool(30)`，任务队列无界。第 31 个及之后的连接 TCP 层会连接成功，但要等前面有连接退出才能被处理——这是有意保持的简单性，不是生产级容量设计。
- 连接处理线程与连接等长，因此 30 个线程意味着最多 30 个连接被**并发处理**。不应据此宣称支持大量活跃连接。
- 每连接写线程独立于读线程：阻塞写（对端不收数据导致 TCP 缓冲写满）只影响该连接自己，不会拖住转发线程或其他连接——这是"慢客户端隔离"修复（缺陷 4）的核心。

## 3. 在线注册表与并发登录

`OnlineRegistryUtil` 封装一个 `ConcurrentHashMap<String, ClientService>`：

- `register(id, client)` 使用 `putIfAbsent`，**单次原子操作**决定登录成败。两个连接同时登录同一账号时，恰好一个成功；注册成功后才设置 `ClientService` 的 `userId`，失败路径不会污染会话身份。
- `unregister(id, client)` 使用 `remove(id, client)` 双键校验连接所有权。旧连接（超时、断开）清理时只能移除自己注册的记录，无法误删后继新连接——这解决了"旧连接注销覆盖新登录"的问题（缺陷 1 的另一半）。
- `getClient` / `isOnline` / `getOnlineIds` 为只读视图；`getOnlineIds` 返回 key 的快照拷贝。

注意：`ConcurrentHashMap` 的单操作原子性不等于组合操作原子性，本项目的正确性依赖"注册用 putIfAbsent、清理用 remove(id, client)"这一约定，而不是 Map 本身。

## 4. 容量与背压

两级有界队列把"消息过多"的拒绝点放在明确的位置：

### 4.1 共享消息队列（1024）

`MessageSubmissionService.submit(currentUserId, message)` 是唯一入队入口，顺序执行三项检查：

1. **身份校验**：`message.srcId` 必须等于当前连接登录身份，防止伪造发送者；
2. **在线校验**：接收者不在线直接返回 `接收者离线`；
3. **满载拒绝**：`offer()` 返回 false 时返回 `服务器繁忙`，**不阻塞**发送线程。

成功时返回 `消息已加入发送队列`。注意语义边界：这只表示消息进入服务端内部队列，**不等于写入 Socket，更不等于对端收到**。

### 4.2 每连接出站队列（256）

`ClientService.send(text)` 将文本 `offer` 进本连接的出站队列：

- 写线程消费队列并 `println`；`PrintWriter` 会吞掉 IO 异常，因此每次写入后用 `checkError()` 检测写失败，失败则停止写循环并关闭连接。
- 队列满时 `send` 返回 false，两种调用方的处理不同：
  - 本连接自己的响应发送失败 → 读循环直接 break，进入清理（连接本就要关闭）；
  - `SendMsgService` 转发他人消息失败 → 记录 error 日志，**该条消息被丢弃**，转发线程继续服务其他消息。

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

- **读空闲超时**：`Socket.setSoTimeout(60_000)` 只作用于读操作。失联客户端（网络黑洞、进程僵死）在 60 秒内没有任何数据到达即触发清理；活跃连接只要持续有命令就不会超时。注意它**不是**写超时，写阻塞由独立写线程隔离解决。
- **close() 幂等**：`AtomicBoolean.compareAndSet` 保证无论从哪条路径（读循环 finally、写线程错误检测、外部测试）进入，清理只执行一次。
- **写线程回收**：close 时先向出站队列投递哨兵 `"\u0000"`（256 容量已满时 offer 失败，哨兵丢失），等待 300ms；随后关闭 Socket 并中断写线程再等 1s；写线程仍未退出则记录 warn。哨兵 + 中断 + Socket 关闭三重机制保证正常情况下写线程必然退出，已验证断连后 0 残留。
- **登出安全**：只能登出 `userId` 与参数一致的自己；登出成功后 `userId` 置空，登出响应入队后即退出命令循环并关闭连接。若需继续通信，客户端必须重新建立连接并登录。

## 6. 协议解析语义

- `OrderServiceImpl.splitOrder`：`split(",", 2)`，只对命令码 trim，剩余部分整体保留为 content。空串 / null / 空白命令码返回 null；只有命令码时 content 为空串；未知命令码返回 `order=null` 的 Order 对象，由 `ClientService` 转为 `000,FAIL,命令解析错误`。
- `MessageServiceImpl.splitMessage`：`split(",", 3)`，正文中的后续逗号全部保留；不足 3 段返回 null；`Alice,Bob,` 会得到空正文消息（字段是否为空由后续业务判断，解析器不校验）。
- `splitMessage(null)` 会抛 NPE，但现有调用链上 content 来自 `splitOrder`，不为 null，该输入不可达，未锁定为协议契约。

## 7. 数据访问

- MyBatis 3.5.6，POOLED 数据源，连接参数来自 `mysql.properties`（本地文件，已忽略；仓库提供 `.example` 示例）。Java 应用目前不读取环境变量。
- `user` 表：`id VARCHAR(32)` 主键、`password CHAR(32)`（MD5 摘要）。注册与登录均使用 `openSession(true)` 自动提交的短会话。
- 密码仅 MD5 摘要存储是课程项目口径，已在 README 已知限制中声明。

## 8. 关键取舍回顾

| 问题 | 方案 | 取舍 |
|---|---|---|
| 重复登录竞态 | `putIfAbsent` 原子注册 | 不加锁；正确性依赖注册表封装，而非调用方约定 |
| 慢客户端阻塞全局转发 | 每连接写线程 + 256 出站队列 | 多一个线程开销，换取转发线程永不被单个连接卡住 |
| 消息队列无界 | 1024 共享队列 + offer 满载拒绝 | 过载时丢新消息（返回"服务器繁忙"），不做等待或持久化 |
| 发送线程退出 | 停止标志 + Server 中断 | 收到中断即退出，不承诺排空；完整优雅关闭未实现 |
| 失联连接堆积 | 60 秒读空闲超时 | 只覆盖读路径；区分断开、空闲与网络不可达需结合日志 |
| PrintWriter 吞异常 | 每次写后 `checkError()` | 检测有延迟（写失败在下一次 checkError 才被发现），但足够驱动清理 |

## 9. 已验证行为与边界

自动化测试（`mvn test`，23 项，全部不依赖数据库）：

| 测试类 | 数量 | 验证目标 |
|---|---|---|
| `OnlineRegistryUtilTest` | 2 | putIfAbsent 只允许一个并发登录；旧连接清理不影响新连接 |
| `MessageSubmissionServiceTest` | 5 | 满载限时拒绝、身份不匹配拒绝、离线拒绝、成功恰好入队一次、日志上下文回归 |
| `SendMsgServiceTest` | 3 | 慢客户端写阻塞时其他用户正常收发；满队列拒绝；关闭排空与写线程退出 |
| `ClientServiceReadTimeoutTest` | 3 | 读空闲超时清理；EOF 清理；正常活动不超时且保持在线 |
| `OrderServiceImplTest` | 6 | 命令解析的正常、边界与异常输入 |
| `MessageServiceImplTest` | 4 | 消息字段映射、正文逗号保留、空正文、字段不足 |

手工验证（用户执行）：注册 / 登录 / 在线查询 / 含逗号消息转发 / 登出 / 离线列表更新；双实例端口冲突退出；读超时约 60 秒生效；端到端登出响应 3/3 送达、断连后写线程 0 残留。

未验证 / 未覆盖：

- 真实 TCP 缓冲写满（需对端只读不收的大流量场景）与并发规模；
- 压测与容量数据（未执行，不虚构）；
- 服务器完整优雅关闭；
- 全历史凭据审计。
