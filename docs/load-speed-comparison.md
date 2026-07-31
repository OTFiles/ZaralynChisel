# 区块加载性能对比 (v0.4.0-alpha 优化前后)

对比脚本: `tools/compare_load_speed.py`

```bash
python3 tools/compare_load_speed.py cache/l.log cache/ul.log
```

- `cache/l.log`  — 优化前 (v0.3.0-alpha, 串行加载)
- `cache/ul.log` — 优化后 (v0.4.0-alpha, 并行解码 + 动态批次)

## 结果 (2026-07-31 实测)

| 指标 | 优化前 | 优化后 | 加速 |
|------|--------|--------|------|
| 前 50 区块耗时 | 2.85 s | 1.08 s | **2.6×** |
| 前 100 区块耗时 | 5.65 s | 1.83 s | **3.1×** |
| 前 200 区块耗时 | 11.15 s | 4.20 s | **2.7×** |
| 前 500 区块耗时 | 33.39 s | 28.14 s | 1.2× |
| 单区块间隔中位 | 50.0 ms | 1.0 ms | **50×** |
| 并行簇 (间隔<5ms 占比) | 0% | **77%** | 证明并行解码生效 |
| 批次间隔中位 | 1740 ms | 882 ms | 2.0× |

## 分析

- **初始视野加载 (前 200 区块) 提速约 3×** — 这是用户感知最明显的部分。
- 并行簇 77% 说明大部分区块由多个 IO 线程同时解码完成（优化前为 0%，完全串行）。
- 前 500 区块的加速只有 1.2×，因为此时视野扩大到 zoom=1.0，每批要覆盖更大的范围，且批次间隔中出现 6-7s 的 region 切换停顿。

## 剩余瓶颈

日志中 `SAF openInputStream OK` + `Opened region: region_XXX.mca` 出现频繁：
每次切换 region 文件时，`AnvilReader.fromStream` 会把整个 .mca（最大 ~8MB）
复制到临时文件，这是当前最大的单点瓶颈（约 6-7s/次，出现在 16:27:22.591 →
16:27:28.728 的间隔中）。

下一步优化方向:
1. **Region 文件缓存**: 复用已复制到临时文件的 AnvilReader，而不是每次切换
   region 都重新复制 (已部分实现: `cachedReader` 只缓存一个 region，多 region
   切换时会不断重建)。
2. **多 region 并行**: 每个 region 一个 reader，并行打开。
3. **避免全文复制**: 直接 seek SAF 的 ParcelFileDescriptor，跳过 temp 文件。
