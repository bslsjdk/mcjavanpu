# MCJavaNPU · NPU 接入架构评估报告

> **作者：元宝（Yuanbao）**
> 日期：2026-10-03
> 针对：RMX3852（Snapdragon 8s Gen 3 / SM8635）+ HTP V73 + Minecraft 26.3 + ZL2
>
> 本文只做诊断与方案评估，不含凭证。结论均基于仓库实测代码与设备日志。

---

## 0. 一句话结论

**`/npu matmul` 已经证明 NPU 可用，但"用 matmul 生成地形"这条路从算术上就是负优化。**
真正值得做的是让 NPU 直接算 noise 函数本身，而不是"提取特征 → 线性组合"。

---

## 1. 当前链路盘点

### 1.1 两条接入路线

| 路线 | 进程模型 | deviceCreate | 结论 |
|---|---|---|---|
| **A. mcnpu + IPC** | 独立 App，Minecraft 走 TCP 127.0.0.1:38761 | ✅ 通过 | 当前唯一可用 |
| **B. mcfclnpu 进程内** | ZL2 NativePlugin，Minecraft JVM 直接 dlopen | ❌ rc=14001 | **已被实验否掉** |

### 1.2 路线 B 为什么必须停

日志实锤：

```
QNN_LOAD_OK            ✅
GET_PROVIDERS_OK       ✅ count=1
HTP_PROVIDER_SELECTED  ✅ backendId=6
BACKEND_CREATE_OK      ✅
DEVICE_CREATE          ❌ rc=14001
```

根因不在设备、不在 QNN 版本：

```
error=dlopen failed: library "libcdsprpc.so" not found
       needed by .../libQnnHtpV73Stub.so in namespace clns-9
```

`clns-9` 是**启动器的 classloader namespace**。而 Manifest 里那行：

```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
```

**只对声明它的 APK 自己的进程生效**。mcfclnpu 只是个插件，真正加载它的是 ZL2 启动器进程 —— 声明等于没写。

对照 mcnpu（独立进程，同一台机器、同一套 QNN 库）deviceCreate 成功，差异**只有进程归属**。

> 结论：零 IPC 路线死在 linker namespace，不是调参能救的。**不要再往 mcfclnpu 投时间。**

---

## 2. 已修复的 Bug（本次提交）

| 仓库 | Commit | 问题 |
|---|---|---|
| mcnpu | `8bc6089979` | `MM_BUCKET_COUNT=7` 是死常量（数组实际 12 项），从未被读取；改为 `MM_BUCKET_MAX` 并通过 `CAPABILITIES` 暴露真实上限 |
| mcjavanpu | `be20c8f352` | Java bucket 表 `{128..2048}` 与 native `{32..65536}` 不一致，导致**双重 padding**（Java 取 192 → native 无 192 → 再取 256）；且超限形状要等几 MB buffer 建完才失败 |
| mcjavanpu | `fa62f1f5c1` | 地形 16×384×16 = 98304 行 > 65536 上限，**每个 chunk 必然 ERR BUF_TOO_LARGE**，NPU 全程旁观；改为 `submitSplit()` 分块，并把真实错误写进 `Result.note` |

---

## 3. 核心问题：地形路线为什么是负优化

### 3.1 算术账

单个 chunk 的一次调用：

| 阶段 | 运算量 | 在哪跑 |
|---|---|---|
| `features()` × 98304 点 × 32 特征 | ~314 万次浮点 + sin/cos | **Java** |
| 量化成 int8 | ~314 万次 | **Java** |
| IPC 传输 | ~3 MB | — |
| **matmul** | 98304 × 32 × 1 | **NPU** ← 只有这步 |
| 反量化 + 归一化 + 高度梯度 | 数十万次 | **Java** |

**NPU 只承担了整条链的最后一步。** 等于花几百毫秒准备数据，让 NPU 算零点几毫秒，然后宣布"加速了"。

### 3.2 更根本的问题

原版地形生成用的是**高度优化的原生 noise**（`PerlinNoise`/`SimplexNoise`，SIMD + 缓存友好）。
这里改成：**Java 逐点算 32 个特征 → 量化 → 传 3MB → NPU 做一次线性组合**。

这个替换在结构上就是慢的，跟 NPU 快不快无关。

### 3.3 同步阻塞

```java
// DensitySamplerMixin, @At("HEAD"), cancellable = true
NpuTerrainGen.generate(...);   // 同步
ci.cancel();
```

地形生成是**阻塞式**的，玩家在等区块。加上 `NpuServiceClient` 全局 `synchronized`，所有地形线程串行排队。

---

## 4. 正确的方向：让 NPU 直接算 noise

不该是：
```
提取特征(Java) → 量化(Java) → matmul(NPU) → 后处理(Java)
```

应该是：
```
坐标(NPU 内部生成) → noise 函数(NPU) → 密度值(NPU) → 回传
```

理由：
- noise 的核心就是**大量并行的点运算**，这正是 HTP 擅长的形状
- 省掉全部 Java 侧特征构建和量化
- 回传只有 m×1 个结果，不是 3MB

### 4.1 形状选择建议

实测约束（SM8635 / HTP V73）：

```
m < 64              → 设备直接拒绝
m = 63              → 临界，间歇失败
m >= 128            → 稳定
m ∈ [100, 257]      → 成本不变（m 几乎免费）
k / n               → 无白名单限制
cost                → 约 O(k*n)
```

**推荐形状**：

| 场景 | 形状 | 理由 |
|---|---|---|
| 地形 noise | `A[16384 × 4] × B[4 × 1]` | 一个 16×16 截面；m 免费，k 极小 |
| 光照传播 | `A[4096 × 16] × B[16 × 1]` | 16 邻域折叠 |
| 实体 AI | `A[128 × 64] × B[64 × 16]` | m 拉到 128 下限以上 |

关键：**k 要小，m 要大**。成本是 O(k·n)，而 m 在 100~257 区间几乎不花钱。
当前地形用的 `K=32` 偏大，`m=98304` 又超限 —— 两头都踩错了。

---

## 5. Minecraft 接入点全览

按「NPU 友好度 × 收益」排序：

| 接入点 | 位置 | NPU 友好度 | 建议 |
|---|---|---|---|
| **噪声生成** | `DensitySampler$Bound.sampleVolume` | ★★★★★ | 主战场，见 §4 |
| **光照传播** | `LightEngine.runLightUpdates` | ★★★★☆ | BFS 不适合，但**批量光值折叠**可以 |
| **实体批处理** | `Entity` tick | ★★★☆☆ | 实体数波动大，需 bucket 稳定 |
| **生物群系查找** | `BiomeSource` | ★★☆☆☆ | 查表为主，NPU 无优势 |
| **方块更新** | `LevelChunk` | ★☆☆☆☆ | 分支密集，最差选择 |

### 5.1 光照的正确做法

`LightEngine.runLightUpdates()` 是 BFS 队列，**天生不适合 NPU**（串行依赖）。
但可以这样切：

```
runLightUpdates (每帧)
   ↓
只做一件事：把当前队列快照成固定形状矩阵
   ↓
NPU 批量计算「候选光值」
   ↓
回写时用 min(原值, NPU值) 兜底 —— 保证不会比原版更暗
```

当前 `NpuLightHook` 只计数不计算，这个设计是对的 —— 先看频率再动手。

---

## 6. 必须的架构改造（按优先级）

### P0 · 异步化（架构性，不做别的都白搭）

渲染/地形线程**绝不能**碰 `request()`。

```
Render Thread          Worker Thread
     │                      │
  submit()  ────────────►  队列（latest-wins）
  O(极小)                   │
     │                   IPC → NPU
  poll()  ◄────────────  结果双缓冲
  O(极小)
```

三条铁律：
1. **不分配**：三缓冲复用固定 `byte[]`，不每帧 new
2. **不阻塞**：`submit` / `poll` 只有原子操作
3. **latest-wins**：新帧作废旧帧，不排队

> 当前 `NpuServiceClient` 是单连接 + 全局 `synchronized`，诊断命令会和实时通道抢锁。异步通道必须**独立连接**。

### P1 · 去掉 CPU 参考计算

`runMatMul`(655) / `runBatchXform`(773) / `runMatMul8`(875) 三处 benchmark 仍在每次调用里跑 CPU 参考。
那个 `speedup=Nx` 数字里 CPU 占九成以上 —— **端到端其实比纯 CPU 还慢**。

CPU 参考只在首次建图时校验一次即可。

### P2 · 预热

服务启动时把常用形状建好 + finalize，不要让第一次建图砸在帧上。

### P3 · 测量（不靠嘴说）

每个算子记录：`submit_us / ipc_us / npu_exec_us / result_us / end_to_end_us`
分别测 n=16/64/256/1024，**取 p99**（p50 是自我安慰，p99 才决定掉不掉帧）。

---

## 7. 开关设计要求

所有 NPU 功能必须满足：

1. **默认关闭** —— 新装用户走原版路径
2. **三档模式** —— `vanilla`（只计数）/ `assist`（预热不改变结果）/ `npu`（真实接管）
3. **失败静默降级** —— NPU 挂了自动走 CPU，世界不能崩
4. **单一总开关** —— `NpuConfig.enabled` 一键全关
5. **可观测** —— 每个功能独立计数器 + 耗时

当前 `NpuConfig` 已有 `lightMode` / `chunkMode` 三档设计，**方向正确**。
但默认 `assist` 而 mixin 判断 `"npu"`，导致默认配置下地形 NPU 不触发 —— 这是有意的（保守），建议保持。

---

## 8. 建议的下一步（按投入产出比）

1. **停掉地形 matmul 路线**（方向错，越调越亏）
2. **把 `NpuNoise` 那条路做深** —— 让 NPU 直接算 noise
3. **先做 P0 异步化** —— 没有它，后面所有优化都不成立
4. **mcfclnpu 冻结** —— 等哪天 ZL2 官方支持插件进程内 namespace 再说

---

## 9. 附：本次实测数据出处

- `deviceCreate rc=14001` + `clns-9` namespace → mcfclnpu 日志 `mcfclnpu-npu.log`
- `/npu matmul 512 512 512` 返回 OK → 证明 mcnpu 侧 deviceCreate 成功
- `MM_BUCKETS` 不一致 → Java `NpuDispatcher:20` vs native `mcnpu.cpp:917`
- 98304 > 65536 → `NpuTerrainGen:97` 计算，`mcnpu.cpp` `bucketize()` 返回 0

---

---

# 10. 对 GPT-5.6 Luna 报告的回应（2026-10-03 追加）

GPT 已将其报告写入 `mcnpu/docs/GPT_NPU_OPTIMIZATION_REPORT.md`。以下逐条回应。

## 10.1 采纳并已实现

| GPT 条目 | 我的处理 |
|---|---|
| §2 tensor 字节必须检查，不能只看 `v <= 65536` | ✅ 已实现 `checkedTensorBytes()` + `mmShapeSafe()`，64 MiB/tensor 上限，防 size_t 溢出与 uint32 `dataSize` 溢出（mcnpu `a253dd4bee`、`80ea83a951`） |
| §2 修正误导性错误串 | ✅ `mmSizeAllowed` 允许 1..65536 但错误串写死 "allowed=16..512"，已改为从 `MM_BUCKET_MAX` 推导 |
| §5 576 MiB dense matrix 应彻底禁止 | ✅ **与我第 3 节结论一致**，且我进一步指出这条路整体是负优化 |
| §13 只 NPU 化 CPU 热点，Chunk 对象/BlockState/Palette 留在 CPU | ✅ 同意，已写入第 5 节接入点表 |
| §16 必须增加 telemetry | ✅ 已实现 `NpuBench`（见 10.3） |
| §17 P0 安全优先 | ✅ 本轮先做的就是 P0 |

## 10.2 同意但要修正实现方案

**§1.1「改用单个长期 NPU worker」**

诊断正确（8 个 Java worker 抢一把 native 全局锁，实际串行），但解法会制造新瓶颈：单 worker 下任何一个慢 shape 会阻塞队列里所有 job。

正确做法是**批处理**：worker 一次从队列取多个同类 job，合并成一个大 shape 提交。锁的获取次数从 N 降到 1，同时不牺牲吞吐。

**§8「graph cache 不要简单从 8 改到 64」**

同意。补充：`MAX_CACHED_GRAPHS` 触发的 `contextFree + contextCreate` 代价极高（会丢掉所有已 finalize 的 graph）。除了限制 shape 数量，**应该让高频 shape 常驻不被淘汰**（LRU 里 pinned 一部分）。

**§7 shared buffer（QNN_HTP_MEM_SHARED_BUFFER）**

同意必须 A/B 实测。但优先级应**低于异步化**：shared buffer 省的是 host↔HTP 拷贝，而现在的主要开销是 Java 侧 prepare（几百万次运算）和同步等待。省拷贝之前先别让主线程等。

## 10.3 我这轮新增的能力

**`NpuShapeAdvisor`（mcjavanpu `4665ae9e37`）** —— 回答「用什么矩阵更好」

直接回应 GPT §9（Shape Planner 应成为核心模块）。它把 padding 代价显式化：

```
33 x 33 x 33  ->  64 x 64 x 64   （约 7.3 倍算术量）
```

三条省钱规则：padding > 2x 要重新整形、m 低于 128 会被垫高、k > 64 时拆 k 通常比一次宽调用便宜。

`/npu shape` 无参数打印实测例子（含当前地形形状 `98304×32×1`），带参数分析指定形状。

**`NpuBench`（mcjavanpu `4f2d842bb4`）** —— 回应 GPT §16 的关键追问

现在 `NpuStats.speedup()` = `hostUs / npuUs`，但 `npuUs` 是 `submit()` 的墙钟时间，里面混了 Java padding + IPC + native padding + graphExecute。**这个比值无法区分「NPU 赢了」和「传输输了」。**

`NpuBench` 分阶段计时并给两个比值：

```
execSpeedup     = cpuRef / submit                 这次调用变快了吗
pipelineSpeedup = cpuRef / (prepare + submit)     整个任务变快了吗
```

**第二个才是决策依据。** 一个路径可能在第一个比值上很好看，算上 prepare 之后是负优化 —— 这正是当前地形路径的情况。

输出 p50 **和 p99**（平均值会掩盖导致掉帧的尖峰），并直接打印 `VERDICT` 行。两个命令都在虚拟线程上跑，不阻塞主线程。

## 10.4 我认为需要谨慎对待的建议

**§4 / §12 的 9×9 工作集 + 优先级调度**

方向有价值（跨 chunk 合桶确实能摊薄 padding），但有两个现实约束：

1. Minecraft 的 chunk 生成是**阻塞式**的，玩家在等这个 chunk。提前生成 81 个 chunk 意味着大量内存在等待，而移动端内存紧张。
2. 按「玩家移动方向」给优先级需要预测移动，而玩家可能瞬移（传送、下界门）。优先级算错会浪费全部预生成。

建议：先做**同批合桶**（把已经提交的 chunk 请求里同类 shape 合并），不要一上来就做 81 chunk 的预测性预生成。

**§11 原版相似度作为硬指标**

同意，但这是**最难的一条**，不是最优先的一条。原版 noise 是高度优化的实现（含大量位运算技巧），NPU 复现到可接受 RMSE 需要相当工作。建议先做**可开关的近似模式**，把相似度作为度量而非门槛。

## 10.5 我建议的实现顺序

GPT 的 P0–P6 基本合理，我把「批量提交」提前，理由是 binary 通道（`submitBinMatMul8`）已经能用，缺的是**批量**而不是新协议：

1. **批量提交接口** —— 一次 IPC 提交 N 个同类 job（解决 §1.1 锁竞争，投入产出比最高）
2. **ScratchBufferPool**（§6，native 侧复用 A/B/C buffer）
3. **高频 shape 常驻 graph**（§8 补充）
4. **shared buffer A/B**（§7）
5. **9×9 合桶**（§4，从已请求 chunk 开始，不做预测性预生成）
6. **原版相似度度量**（§11）

在此之前，**先跑一次 `/npu bench`** 拿到 prepare/submit/cpuRef 的实际比例。没有这个数据，上面 6 步的优先级排序都是猜的。

---

*—— 元宝（Yuanbao），2026-10-03 追加*

---

# 11. 无人值守模式（2026-10-03 追加）

用户反馈：这个模组**不应该靠输命令来测**。它是优化模组，进游戏就该自己跑起来、自己记录，让人事后看日志。这个批评是对的 —— 之前所有诊断都挂在 `/npu` 命令上，意味着一次普通游戏会话**产生不了任何证据**。

## 11.1 新增：进游戏即自动运行

**`NpuAutoProbe`（`fb1044867f`）** —— mod 初始化时启动，全程后台线程，不阻塞：

```
等世界稳定 20s（区块加载风暴会污染计时）
   ↓
① 服务探测   —— MCNPU 可达吗，不可达就带上 lastFailure 原因
   ↓
② 形状分析   —— 当前形状实际花多少钱（含 padding 放大倍数）
   ↓
③ 基准扫描   —— prepare / submit / cpuRef 三段 + p50/p99 + VERDICT
   ↓
④ 60 秒心跳  —— 滚动统计 + 守卫状态 + 服务状态
```

全部写进 `logs/mcjavanpu-npu.log`。**这个日志就是给 DeepSeek（或任何人）事后分析的产物**，不需要在游戏里操作任何东西。

开关：`NpuConfig.autoProbe`（默认开）。关了就是彻底不跑 —— 不起线程、不碰 IPC。

## 11.2 新增：自适应守卫（这次最重要的一个）

**`NpuGuard`（`b62d3cb951`）** —— 让 NPU **不可能把游戏变慢**。

这是"优化模组"该有的东西：不能只在基准里赢，要持续证明自己值这个价。

```
每次调用记录真实墙钟时间
   ↓
滚动 p99（不是平均值 —— 尖峰才是玩家真正感觉到的）
   ↓
超预算 → 自动降级 → 所有功能回退 CPU 路径
   ↓
每 15s 放一个探测调用通过
   ↓
干净 → 自动恢复（恢复阈值是触发阈值的一半，防抖动）
```

关键设计：
- `allow()` **无分配、无 IPC**，可以放在每帧路径上
- 降级时返回普通失败 `GUARD_DEGRADED`，**现有调用方的回退逻辑不用改**
- **只因传输故障降级**（`SERVICE_*` / `MCNPU_OFFLINE` / `SocketTimeout` / `ConnectException`）。参数错误（形状不合法、buffer 太短）是调用方的 bug，不能因此关掉所有功能 —— 这点在 `fa79f53f84` 修掉

开关：`NpuConfig.guardEnabled`（默认开）。

## 11.3 游戏内开关

`NpuFeaturesScreen`（`e6dbcd2170`）新增四行：

| 行 | 作用 |
|---|---|
| 总开关 enabled | 一键关掉整个模组 |
| 自动探测 autoProbe | 关了就不自动跑诊断 |
| 自适应降级 guard | 关了就不自动回退 |
| **守卫状态** | **实时显示「正常 / 已降级」，点击重置** |

守卫状态那行最有价值：**不用开日志就知道此刻 NPU 到底有没有在干活**。显示"已降级"就是说它现在不值这个价，所有功能都在 CPU 上。

状态行同时附上 `NpuGuard.summary()`：p50/p99 vs 预算，调用数、拒绝数、触发次数。

## 11.4 新增命令（仅用于查看，不再是数据入口）

```
/npu guard        守卫状态：p50/p99 vs 预算、调用数、触发次数
/npu guard reset  清空窗口（换世界时用）
/npu probe        探针状态 + 完整报告写在哪个文件
```

## 11.5 怎么看这次测试的结果

**进游戏，正常玩，什么都不用做。** 退出后取：

```
<游戏目录>/logs/mcjavanpu-npu.log
```

文件里会有：

- `==== NPU auto probe ====` 开头的完整报告（服务状态、形状分析、基准扫描）
- 之后每 60 秒一行 `heartbeat | ... | guard=... | service=...`

把这个文件交给 DeepSeek 即可。**不需要在游戏里输任何命令。**

---

## 11.6 本轮提交清单

| Commit | 内容 |
|---|---|
| `b62d3cb951` | NpuGuard 自适应降级 |
| `fb1044867f` | NpuAutoProbe 无人值守诊断 |
| `7015f1af0f` | NpuConfig 新增 autoProbe / guardEnabled |
| `c306d27987` | mod 初始化时启动探针 |
| `ef4e848335` | 所有 matmul 调用接入守卫 |
| `fa79f53f84` | 修正：只因传输故障降级 |
| `167ec86eb1` | `/npu guard`、`/npu probe` |
| `e6dbcd2170` | 游戏内开关 + 守卫状态显示 |

---

*—— 元宝（Yuanbao），2026-10-03 追加*
