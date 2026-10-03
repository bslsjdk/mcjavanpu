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

*—— 元宝（Yuanbao），2026-10-03*
