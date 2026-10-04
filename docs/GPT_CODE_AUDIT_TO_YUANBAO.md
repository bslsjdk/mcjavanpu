# GPT 已发现问题 → 元宝逐项查证

这不是一份抽象的“审计规范”，而是 GPT 根据当前仓库源码实际看到的**可疑 Bug / 性能问题 / 正确性问题**。

注意：下面每一项都是“GPT 怀疑”，不是先验结论。元宝的任务是回到当前 main 源码，逐项证明或推翻。**不要因为 GPT 写了 FAIL 就直接改代码，也不要因为旧报告说“已修复”就跳过。**

目标：

`GPT 发现可疑点 → 元宝查当前源码 → 证明/推翻 → 给出证据 → 再决定是否修复`

---

## P0：可能直接影响正确性 / 稳定性

### GPT-01：Persistent IPC 单 Socket 并发读写可能串响应

**GPT 为什么怀疑：**

`NpuServiceClient` 使用 persistent Socket/InputStream/OutputStream。

当前 MatMul 数据路径使用自己的 `SUBMIT_LOCK`，而普通 `request("PING"/"STATUS")` 路径有另一套同步机制。

如果两条路径能够同时操作同一个 persistent stream，就可能出现：

- A 写 SUBMITBIN，等待 MatMul response
- B 写 STATUS
- B 读取到 A 的 response
- A 再读取到 B 的 response
- 或两个 request 的 write 在协议边界上交错

这不是单纯性能问题，而是 IPC correctness 问题。

**元宝必须查：**

1. MatMul 与 PING/STATUS 是否共享同一 socket/stream。
2. 两条路径是否可能由不同线程同时进入。
3. `SUBMIT_LOCK` 与普通 request 的锁是否实际上等价覆盖整个 request/response。
4. 是否存在唯一的 request/response serialization。
5. 给出真实调用链证明安全，或者证明存在竞态。

**不要只写“理论上可能”。**

---

### GPT-02：native graphCount 可能重复计数

**GPT 为什么怀疑：**

当前 `runMatMulInt8Buf()` 一带的 graph 生命周期代码里，GPT 看到 graphCount 有可能在：

1. graph 创建阶段
2. graphFinalize 成功阶段

分别递增。

如果同一个 graph 被计数两次，而 `MAX_CACHED_GRAPHS=8`，可能导致缓存上限提前触发。

可能结果：

`实际 graph 数 < MAX_CACHED_GRAPHS`  
但  
`graphCount >= MAX_CACHED_GRAPHS`

然后提前 reset/context churn，造成 prepare/cache miss/延迟尖峰。

**元宝必须查：**

- 当前 main 的所有 `graphCount++`。
- 每个位置的控制流。
- 一个 graph 是否真的可能走两个 increment。
- reset 条件。
- 给出实际 graph 数与 graphCount 的关系。

---

### GPT-03：QNN graph 创建/Finalize/prepare 失败后可能存在 dirty context

**GPT 为什么怀疑：**

当前失败路径会从 Java/native cache map 删除 graph，但“从 map 删除”不等于 QNN context 内的 graph/resource 已经释放。

GPT 没有看到足够明确的：

`failure → graphFree → contextDirty → contextFree → contextCreate → cache reset`

完整闭环证据。

如果失败 graph 继续留在 QNN context，长期运行可能：

- context 资源累积
- 后续 graph create/finalize 异常
- cache 命中率下降
- latency spike
- 最终 HTP backend 不稳定

**元宝必须查：**

- graphCreate failure
- graphFinalize failure
- prepare failure
- graphFree
- contextFree
- contextCreate
- cache clear/rebuild

必须按真实控制流判断，不能凭 API 名称猜。

---

### GPT-04：普通 JNI MatMul 与 Binary MatMul 的 Shape Planner 可能不一致

**GPT 为什么怀疑：**

实测 HTP 不是“1..65536 任意尺寸都正常”，而是 m/k/n 三个维度分别落入允许 bucket。

Binary 路径已经有 bucketize/padding 思路，但普通 JNI MatMul 路径的 `mmSizeAllowed(v)` 看起来更像：

`1 <= v <= 65536`

这意味着 33、65、129 等尺寸可能被普通路径接受，却没有落到真实 HTP bucket。

**元宝必须查：**

- 普通 MATMUL
- INT8 MATMUL
- Buffer MATMUL
- Binary MATMUL

是否共用同一个 ShapePlanner。

尤其测试代码逻辑：

`requested m/k/n → bucketize → padding → graph key`

是否统一。

**重点：** m/k/n 是三个独立维度，不能只对一个维度 bucketize。

---

### GPT-05：JNI/native allocation 可能发生在完整预算检查之前

**GPT 为什么怀疑：**

某些 MatMul 入口可能先按照调用者提供的 m/k/n 创建：

- A vector
- B vector
- C vector
- float/int8 buffer

之后才做完整 dimension / element / byte limit 检查。

这会产生两个问题：

1. 极端尺寸导致不必要的大内存分配。
2. OOM 发生在 validation 之前，程序直接崩，而不是正常 reject。

**元宝必须查：**

真实顺序必须是：

`validate dimensions → overflow check → element count → total bytes → max job bytes → allocation`

而不是：

`allocation → validation`

把所有 JNI/native MatMul 入口都查一遍。

---

### GPT-06：Terrain takeover 的 gate 可能允许实验性 lattice 进入正式地形

**GPT 为什么怀疑：**

当前：

`NpuTerrainGate.isTakeoverAllowed()`

存在 `chunkMode == "npu"` 的特殊路径。

同时：

`NpuTerrainLattice`

明确还是 experimental HTP lattice：

- sin/cos
- 手写 feature
- random weights
- CELL_XZ=8
- CELL_Y=16

它不是 Minecraft vanilla DensitySampler。

因此如果 `chunkMode=npu` 可以绕过 `takeoverAllowed=false`、parity prerequisite 或其他 gate，就可能发生：

`实验算法 → 正式 chunk terrain`

这属于 correctness P0。

**元宝必须证明：**

- npu mode 到底能绕过什么。
- `takeoverAllowed` 是否仍然有效。
- `NpuTerrainVanilla.ready()` / unsupported 检查是否被绕过。
- `allowWrite()` 最终是否允许 lattice result 写入正式 chunk。
- 哪个 feature flag 控制真正 takeover。

必须明确区分：

**benchmark candidate ≠ vanilla-parity takeover**

---

### GPT-07：NPU_ONLY 可能仍然静默 fallback Vanilla

**GPT 为什么怀疑：**

`DensitySamplerMixin` 存在：

`if (!NpuStats.BLOCKS.enabled) return;`

而 gate / service unavailable / no result 等路径可能让原版继续执行。

如果未来把模式定义为真正的 NPU_ONLY，那么：

- NPU unavailable
- NPU timeout
- NPU result missing
- parity failure
- feature disabled

都不应该偷偷变成 Vanilla。

**元宝必须追完整控制流：**

`mode → gate → request → wait/take → result → write/fallback`

明确指出哪里 fallback。

---

## P1：明显值得查的性能问题

### GPT-08：Java lock + native global mutex 可能形成双重串行

**GPT 为什么怀疑：**

Java side 已经存在 synchronized/lock。

native side 又存在：

`gRuntimeMutex`

如果多个 producer：

`producer 1`
`producer 2`
`producer 3`

最终全部：

`Java lock → JNI → gRuntimeMutex → QNN`

那么表面上的多个 worker 不代表实际 NPU 并行。

**元宝必须画真实调用链：**

`producer → Java lock → JNI → native mutex → QNN`

然后判断到底有多少地方被串行化。

---

### GPT-09：每次 INT8 submit 可能产生大量 temporary allocation/copy

**GPT 为什么怀疑：**

`runMatMulInt8Buf()` 路径可能每次创建：

- A padded vector
- B padded vector
- C vector
- quant/dequant temporary buffer

Java side 也可能创建 byte[]。

Terrain 高频调用时，这会产生：

- allocator 压力
- GC/native heap pressure
- memcpy
- cache pollution

**元宝必须查：**

一次典型 terrain request 到底发生多少次：

`allocation + memcpy`

并区分：

- Java heap
- native heap
- QNN-owned buffer

---

### GPT-10：Calibration 可能污染运行时热路径

**GPT 为什么怀疑：**

新 bucket 第一次出现时可能执行 CPU calibration，然后保存 scale。

如果玩家移动时首次遇到新 shape：

`NPU submit → CPU calibration → scale`

会产生明显 latency spike。

**元宝必须查：**

- calibration 触发条件
- 是否在启动阶段完成
- 是否所有合法 bucket 都能预热
- runtime 是否仍可能第一次 calibration

目标方向：

`startup prewarm → runtime read-only scale`

---

### GPT-11：Terrain logical batch 可能不等于真实 NPU batch

**GPT 为什么怀疑：**

`NpuTerrainLattice.generateMulti()` 当前会拆成 row blocks。

例如：

`count=4`

不代表：

`1 QNN graphExecute`

可能实际是：

`4 chunks → 多个 row block → 多次 NpuDispatcher.submit()`

因此“batch=4”这个概念可能只是 scheduler/logical batch。

**元宝必须分别统计：**

- logical chunks
- IPC requests
- QNN graphExecute calls
- 实际 NPU batch dimension

不要把 generateMulti() 名字当成真实 batch。

---

### GPT-12：TAKEOVER_CACHE 达上限后可能整表 clear

**GPT 为什么怀疑：**

当前 `TAKEOVER_CACHE` 容量达到上限后似乎是：

`cache.clear()`

而不是 oldest/LRU eviction。

玩家连续移动时可能形成：

`8 个缓存 → clear → 全部 miss → 再填 8 个 → clear`

造成 cache miss storm。

**元宝必须查：**

- 当前实际 eviction 代码
- clear 触发条件
- 普通 CACHE 是否也是同样问题
- 最合理的 oldest/LRU/proximity eviction

---

### GPT-13：8 秒 takeover wait 可能阻塞正常 worldgen 路径

**GPT 为什么怀疑：**

`DensitySamplerMixin` 中存在：

`TAKEOVER_WAIT_NS = 8_000_000_000L`

并循环：

`peek → sleep(200us) → retry`

如果这是正常 chunk generation/worldgen thread 的路径，那么一次 NPU miss 最坏可以让线程等待约 8 秒。

这对正常游戏不可接受。

**元宝必须区分：**

- benchmark
- NPU_ONLY
- normal assist/play

正常模式应该尽量：

`miss → fallback/skip`

而不是：

`miss → block 8 seconds`

---

### GPT-14：Vanilla density reference 可能误入正式热路径

**GPT 为什么怀疑：**

`NpuTerrainVanilla.fill()` / `NpuDfProgram` 涉及：

- float[] allocation
- density program evaluation
- lattice/interpolation

如果正式 NPU terrain 路径每次都先算一遍 Vanilla reference，再算 NPU，NPU 加速很可能被 CPU reference 成本吃掉。

**元宝必须查：**

`NpuTerrainVanilla.fill()`

到底只用于：

- benchmark/oracle

还是也用于：

- runtime takeover
- parity validation
- normal chunk generation

如果正式运行每 chunk 都算 reference，这就是严重性能问题。

---

# 元宝最终需要交付的不是“泛泛而谈”

对每一个 GPT-XX，输出：

| 字段 | 内容 |
|---|---|
| 状态 | VERIFIED BUG / FALSE POSITIVE / PARTIAL / UNKNOWN |
| 文件 | 精确路径 |
| 函数 | 精确函数名 |
| 行号 | 尽可能准确 |
| 证据 | 当前 main 源码片段 |
| 性能影响 | 具体说明 |
| 正确性影响 | 具体说明 |
| GPT 判断 | 为什么当初怀疑 |
| 元宝结论 | 为什么证实/推翻 |
| 修复优先级 | P0/P1/P2 |

最后汇总：

| ID | 问题 | 元宝结论 | 严重度 | 文件 | 函数 | 性能影响 | 正确性影响 | 是否真的需要修 |
|---|---|---|---|---|---|---|---|---|

## 特别要求

1. **以当前 main 源码为唯一事实来源。**
2. 不能因为这份文档说“可能有 Bug”就直接认定。
3. 也不能因为以前有人说“已修复”就直接跳过。
4. 能从源码证明的，才标 VERIFIED BUG。
5. 不能证明的，标 UNKNOWN/PARTIAL，并说明缺什么证据。
6. 如果 GPT 判断错误，明确写 FALSE POSITIVE。
7. 这一步只查证，**不要擅自修改代码**。

这份文件的目的就是把 GPT 已经看到的“可疑代码点”原封不动交给元宝，让它当第二个代码审查员，而不是让它重新发明一套审计方法。
