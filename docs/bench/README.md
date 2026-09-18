# 实测数据

这是 `docs/easytp-rtp-algorithm.md`（第 10 节）用到的原始输出，由 `benchmarks/` 下的基准插件在 Paper 26.2-124 上采集。两次运行的差别只是世界状态，插件构建与采样坐标完全相同。

| 目录 | 世界状态 | 说明 |
|---|---|---|
| `cold/` | 全新世界 | 每个采样区块都需要现场生成地形 |
| `warm/` | 已预生成世界 | 同一批区块已在磁盘上，只需读入 |

每个目录下的文件：

| 文件 | 内容 |
|---|---|
| `chunk_summary.txt` | 区块获取与判定的分位耗时（本文 10.3 节的表） |
| `chunk_cost.csv` | 同上，逐样本明细 |
| `pool_conservation.csv` | 两种补齐度量下逐 tick 的队列长度与累计量（6.3 节、图 5(a)） |
| `spatial_memory.csv` | 逐 5000 次写入时的缓存大小 |
| `spatial_memory_probe.txt` | LRU 边界、命中率、以及"未落盘缓冲"探针结果（10.2 节） |
| `spiral_single.csv` | 20 万个实测坐标（单环 2000–5000，`s=16`），供分布分析 |

`spiral_*.csv` 每个约 2.3 MB 且可由基准插件重新生成，**不在版本控制内**。需要时跑一次
`benchmarks/build_bench.ps1` 即可产出；`docs/verify_measured.py` 依赖 `cold/spiral_single.csv`，
缺该文件时无法运行。

## 怎么读这些数

- `chunk_summary.txt` 里的 `burst_*` 是并发无上限（64 个请求同时提交）的结果，`capped_*` 是
  并发上限 8 的结果。文章引用的是 p50。
- `acquire_*` 是主线程上的同步 `world.getChunkAt`，此时区块已经在磁盘上，因此这一项在两个
  世界状态下几乎相同（13.9 ms 对 14.1 ms）——它本身就是"排除了地形生成之后的开销"这一对照组。
- `safe=` 是 64 列中通过露天安全判定的列数，六轮合计 43/384。单轮样本小，不要用单轮数字。

## 复现时的三个坑

见 `benchmarks/README.md`。最要紧的一条：残留的螺旋索引会让采样从半途开始并在容量处回绕，
在径向覆盖上撕开空洞，而这一步**不报错**。`build_bench.ps1` 每轮都会删掉 `data.db`。
