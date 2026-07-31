#!/usr/bin/env python3
"""
对比区块加载性能: 优化前 (cache/l.log) vs 优化后 (cache/ul.log)

指标:
  1. 总加载区块数 (Loaded chunk 行数)
  2. 加载前 N 个区块耗时 (第一个 Loaded 到第 N 个 Loaded)
  3. 平均吞吐 (chunks/sec, 按首末时间)
  4. 批次耗时 (Surface batch 相邻行的时间差)
  5. 单区块中位/平均耗时 (相邻 Loaded chunk 行时间差)
  6. 并行度指标: 同一毫秒内完成的区块数 (新日志应明显高于旧日志)

用法: python3 tools/compare_load_speed.py [old_log] [new_log]
"""
import re
import sys
from collections import Counter
from datetime import datetime

TIME_RE = re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\]")
CHUNK_RE = re.compile(r"Loaded chunk \((-?\d+),(-?\d+)\) nonZero=(\d+)/256")
BATCH_RE = re.compile(r"Surface batch: loaded=(\d+) failed=(\d+) totalVisible=(\d+) totalWithSurface=(\d+)")

def parse(path):
    """返回 (chunk_times_ms, batch_times_ms) — 每个列表是 (t_ms, count) 元组"""
    chunk_times = []   # (t_ms)
    batch_times = []   # (t_ms, loaded)
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = TIME_RE.search(line)
            if not m:
                continue
            ts = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f")
            t = ts.timestamp() * 1000.0
            if "Loaded chunk" in line:
                chunk_times.append(t)
            elif "Surface batch" in line:
                bm = BATCH_RE.search(line)
                loaded = int(bm.group(1)) if bm else 0
                batch_times.append((t, loaded))
    return chunk_times, batch_times

def deltas(ts):
    return [b - a for a, b in zip(ts, ts[1:])]

def stats(name, chunk_times, batch_times):
    print(f"\n{'='*60}\n{name}\n{'='*60}")
    n = len(chunk_times)
    print(f"总加载区块数: {n}")
    if n == 0:
        return

    # 前 N 个区块耗时
    for target in (50, 100, 200, 500):
        if n > target:
            dt = chunk_times[target] - chunk_times[0]
            print(f"  加载前 {target:>4} 个区块耗时: {dt/1000:7.2f} s  ({target/dt*1000:6.1f} chunks/s)")

    # 整体吞吐 (首末时间, 忽略首尾离群)
    span = chunk_times[-1] - chunk_times[0]
    if span > 0:
        print(f"  总耗时: {span/1000:.2f} s → 平均吞吐 {n/span*1000:.1f} chunks/s")

    # 批次耗时
    if batch_times:
        bd = deltas([t for t, _ in batch_times])
        bd = [d for d in bd if 0 < d < 60000]  # 去掉异常值
        if bd:
            bd.sort()
            med = bd[len(bd)//2]
            print(f"  批次间隔 (n={len(bd)}): 中位 {med:.0f} ms, p90 {bd[int(len(bd)*0.9)]:.0f} ms, p99 {bd[min(len(bd)-1,int(len(bd)*0.99))]:.0f} ms")

    # 单区块间隔 (相邻 Loaded chunk 行)
    cd = [d for d in deltas(chunk_times) if 0 < d < 5000]
    if cd:
        cd.sort()
        med = cd[len(cd)//2]
        mean = sum(cd)/len(cd)
        print(f"  单区块间隔 (n={len(cd)}): 中位 {med:.1f} ms, 平均 {mean:.1f} ms")
        # 并行度: 间隔 < 5ms 的比例 (并行解码时多个区块几乎同时完成)
        burst = sum(1 for d in cd if d < 5)
        print(f"  并行簇 (间隔<5ms 占比): {burst/len(cd)*100:.0f}%")

def main():
    old_path = sys.argv[1] if len(sys.argv) > 1 else "cache/l.log"
    new_path = sys.argv[2] if len(sys.argv) > 2 else "cache/ul.log"
    old_c, old_b = parse(old_path)
    new_c, new_b = parse(new_path)
    stats("优化前 (cache/l.log)", old_c, old_b)
    stats("优化后 (cache/ul.log)", new_c, new_b)

    print(f"\n{'='*60}\n对比\n{'='*60}")
    def first_n(ts, n):
        if len(ts) > n:
            return ts[n] - ts[0]
        return None
    for target in (50, 100, 200, 500):
        a = first_n(old_c, target); b = first_n(new_c, target)
        if a and b:
            print(f"  前 {target:>4} 个区块: 旧 {a/1000:6.2f}s vs 新 {b/1000:6.2f}s → {a/b:5.1f}×")
    if len(old_c) > 1 and len(new_c) > 1:
        old_cps = (len(old_c)-1) / ((old_c[-1]-old_c[0])/1000)
        new_cps = (len(new_c)-1) / ((new_c[-1]-new_c[0])/1000)
        print(f"  平均吞吐: 旧 {old_cps:.1f} chunks/s vs 新 {new_cps:.1f} chunks/s → {new_cps/old_cps:.1f}×")

if __name__ == "__main__":
    main()
