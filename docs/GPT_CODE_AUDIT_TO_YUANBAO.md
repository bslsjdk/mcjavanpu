# GPT Code Audit -> Yuanbao Follow-up

这份文档不是改代码任务，而是让元宝继续对当前仓库做一次“代码事实审计”。不要先相信已有报告里的“已修复”，必须以 main 当前源码为准逐项复核。

## 当前审计结论

### P0：先查清

1. **Persistent IPC 单 Socket 并发读写**
   - `NpuServiceClient` 的 MatMul 数据路径和 PING/STATUS 路径是否使用同一把锁。
   - 如果共享同一个 Socket/InputStream/OutputStream，但不同路径可并发读写，必须确认是否存在响应串线、读走别人的响应、写入交错。
   - 要给出实际调用路径和线程场景，不要只说“理论上可能”。

2. **QNN graphCount 是否重复计数**
   - 当前 native MatMul graph 创建/Finalize/cache 路径中，检查 graphCount 是否在创建前和成功 finalize 后都增加。
   - 如果存在，确认对 MAX_CACHED_GRAPHS 的实际影响。
   - 不要引用旧报告中的“已修复”，以当前 main 为准。

3. **失败 Graph 的 Context Recovery**
   - graphCreate / graphFinalize / prepare 失败后，QNN context 中的失败 graph 是否真的可回收。
   - 如果没有 graphFree，确认当前实现是否存在 dirty context 风险。
   - 检查是否有 contextDirty -> stop -> contextFree -> contextCreate -> cache reset 的完整闭环。

4. **所有 MatMul JNI 入口的 Shape Validation**
   - 普通 MATMUL、INT8 MATMUL、Buffer/Binary MATMUL 是否共用同一个 shape planner。
   - m/k/n 是否全部按照真实 HTP bucket whitelist 处理，而不是仅检查 1..65536。
   - 特别检查 33、65、129 等非 bucket 尺寸。
   - 检查 padding 后的 shape 是否真正用于 graph key/cache key。

5. **Allocation 前置预算**
   - 所有 JNI/native MatMul 入口在 vector/byte[]/float[] 分配之前是否已经完成维度、元素数量、总字节数检查。
   - 禁止“先按照攻击者/调用者给出的巨大尺寸分配，再在后面拒绝”。
   - 给出最大安全 allocation 上限和实际代码位置。

6. **Terrain takeover correctness**
   - `chunkMode=npu` 是否能够绕过 `takeoverAllowed=false`、parity gate 或其他安全条件。
   - 实验性 `NpuTerrainLattice` 是否仍可能通过 npu mode 进入正式 DensitySampler takeover。
   - 必须明确区分“实验 benchmark candidate”和“真实 vanilla-parity takeover”。

7. **NPU_ONLY 语义**
   - 检查 `BLOCKS.enabled`、gate、parity、service unavailable 等条件是否可能让 npu mode 静默回退 vanilla。
   - 如果叫 NPU_ONLY，就必须做到失败可见，而不是静默 vanilla。

### P1：重点性能

8. **Java lock + native runtime mutex 双重串行**
   - 画出真实调用链。
   - 判断多个 producer 是否最终全部串到一个 Java synchronized + 一个 native mutex。
   - 评估这是否让“8 workers”实际上变成单通道。

9. **Native temporary allocation**
   - 检查每次 INT8 submit 是否重新创建 A/B/padded/C vector。
   - 统计一次典型 terrain batch 会产生多少次 allocation/copy。
   - 判断 buffer pool / reusable arena 是否值得做。

10. **Calibration 是否污染热路径**
    - 新 bucket 首次出现时是否仍执行 CPU calibration。
    - 检查 calibration 是否可能发生在玩家移动期间。
    - 给出启动预热所有 bucket、运行时只读 scale 的可行方案。

11. **Terrain logical batch vs actual NPU batch**
    - `generateMulti()` 是否真的一次 QNN submit 处理多个 chunk，还是只是多个 row-block submit。
    - 分清 logical batch、IPC request、QNN graphExecute 次数。

12. **Terrain cache eviction**
    - `TAKEOVER_CACHE` 当前达到容量后是否直接 clear 全部。
    - 判断这会不会造成明显 cache miss storm。
    - 与普通 CACHE 的 eviction 机制一起审查。

13. **8 秒 takeover wait**
    - `DensitySamplerMixin` 的 TAKEOVER_WAIT_NS 是否可能在正常游戏路径阻塞主/worldgen thread。
    - 必须区分 benchmark/NPU_ONLY 与正常 assist/play 模式。

14. **Vanilla density reference 的成本**
    - `NpuTerrainVanilla.fill()` 的 allocation、program eval、lattice interpolation 是否可能成为 CPU 侧瓶颈。
    - 判断它应该只存在于 benchmark/reference，还是会进入正式运行热路径。

## 需要元宝最终输出

不要修改代码。

请生成一份新的审计报告，至少包含：

- 每项：PASS / FAIL / PARTIAL / UNKNOWN
- 精确文件路径
- 精确函数名
- 尽可能给出行号
- 证据代码片段
- 严重等级 P0/P1/P2
- 性能影响
- correctness 影响
- 是否已经被已有报告误判为“已修复”
- 最终修复优先级

最后给出一张总表：

| ID | 问题 | 状态 | 严重度 | 文件 | 函数 | 性能影响 | 正确性影响 | 是否已修复 |
|---|---|---|---|---|---|---|---|---|

**特别要求：**
这次不要凭文档推断。必须以当前 main 分支源码为事实来源。能直接证明的才写 FAIL；无法证明就写 UNKNOWN，并说明还缺什么证据。

这是代码审计，不是改码任务。