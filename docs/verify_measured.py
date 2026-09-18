"""Three-way check: analytic prediction vs numerical model vs measured output.

The measured side comes from docs/bench/, produced by the production classes
running on a real Paper 26.2 server (see benchmarks/). The model side is the
same construction as docs/make_figures.py.

Usage: python docs/verify_measured.py
"""

from __future__ import annotations

import math
import os
import sys

import numpy as np
from scipy.spatial import cKDTree

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "bench")

R_MIN, R_MAX, S = 2000.0, 5000.0, 16.0
GOLDEN_ANGLE = math.pi * (3.0 - math.sqrt(5.0))
CAPACITY_EXACT = int(math.pi * (R_MAX ** 2 - R_MIN ** 2) / S ** 2)
# What the capacity would be if the rounding happened before the multiplication,
# i.e. `(long) Math.PI * (...)`. Kept as the contrast case for the source check.
CAPACITY_TRUNC = 3 * (int(R_MAX) ** 2 - int(R_MIN) ** 2) // (int(S) ** 2)

RING_ZONE = os.path.join(os.path.dirname(HERE),
                         "src", "main", "java", "net", "sakurain", "mc", "easytp",
                         "rtp", "spiral", "RingZone.java")


def ring_zone_capacity() -> str:
    """Reads RingZone.area() and checks the order of the multiplication and the cast.

    `(long) Math.PI * x` truncates pi to 3 before multiplying, which makes the
    capacity estimate 4.5% low and wraps the spiral before it reaches the outer
    edge of the ring. This is checked against the source rather than restated,
    because the article quotes the analytic value and the two must agree.
    """
    try:
        with open(RING_ZONE, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        return f"无法读取 RingZone.java: {exc}"
    if "(long) (Math.PI *" in text:
        return f"{CAPACITY_EXACT}  (先乘后取整，与式 9 一致)"
    if "(long) Math.PI *" in text:
        return f"{CAPACITY_TRUNC}  <- 写成 (long) Math.PI * ...，pi 被截断为 3"
    return "无法识别 RingZone.area() 的写法，请人工核对"


def model_spiral(n: int) -> tuple[np.ndarray, np.ndarray]:
    i = np.arange(n, dtype=np.float64)
    r = np.minimum(np.sqrt(R_MIN ** 2 + i * S * S / math.pi), R_MAX)
    t = GOLDEN_ANGLE * i
    return np.column_stack((r * np.cos(t), r * np.sin(t))), r


def areal_density(radius: np.ndarray, bins: int = 60,
                  r_lo: float = R_MIN, r_hi: float = R_MAX) -> np.ndarray:
    edges = np.linspace(r_lo, r_hi, bins + 1)
    cnt, _ = np.histogram(radius, bins=edges)
    area = math.pi * (edges[1:] ** 2 - edges[:-1] ** 2)
    d = cnt / area
    return d / d.mean()


def angular_gaps_from_xy(xy: np.ndarray, first_n: int | None = None) -> np.ndarray:
    pts = xy if first_n is None else xy[:first_n]
    th = np.mod(np.arctan2(pts[:, 1], pts[:, 0]), 2.0 * math.pi)
    s = np.sort(th)
    g = np.diff(np.concatenate((s, [s[0] + 2.0 * math.pi])))
    return g / (2.0 * math.pi / len(s))


def nnd(xy: np.ndarray, n: int = 60_000, seed: int = 1) -> np.ndarray:
    idx = np.random.default_rng(seed).choice(len(xy), min(n, len(xy)), replace=False)
    p = xy[idx]
    return cKDTree(p).query(p, k=2, workers=-1)[0][:, 1]


def quadrat(xy: np.ndarray, n: int = 60_000, nbins: int = 43, seed: int = 1) -> np.ndarray:
    idx = np.random.default_rng(seed).choice(len(xy), min(n, len(xy)), replace=False)
    p = xy[idx]
    ed = np.linspace(-R_MAX, R_MAX, nbins + 1)
    hist, xe, ye = np.histogram2d(p[:, 0], p[:, 1], bins=[ed, ed])
    c = 0.5 * (xe[:-1] + xe[1:])
    gx, gy = np.meshgrid(c, c, indexing="ij")
    rr = np.hypot(gx, gy)
    return hist[(rr >= R_MIN) & (rr <= R_MAX)]


def main() -> int:
    csv = os.path.join(BENCH, "cold", "spiral_single.csv")
    if not os.path.exists(csv):
        print("missing", csv)
        return 1

    d = np.loadtxt(csv, delimiter=",", skiprows=1)
    mx, mz = d[:, 0], d[:, 1]
    measured = np.column_stack((mx, mz))
    mr = np.hypot(mx, mz)
    n = len(d)

    # The harness burns WARMUP indices before recording, so the recorded points
    # cover a sub-annulus rather than the whole ring. Recover the index interval
    # from the radii and build the model over exactly the same interval, so the
    # two sets are comparable; binning over the full ring would compare the
    # measurement against a region it never sampled.
    warmup = 20_000
    i_lo = int(round((mr.min() ** 2 - R_MIN ** 2) * math.pi / S ** 2))
    i_hi = int(round((mr.max() ** 2 - R_MIN ** 2) * math.pi / S ** 2))
    r_lo, r_hi = R_MIN if i_lo == 0 else mr.min(), mr.max()

    print("=" * 74)
    print("实测螺旋（生产类 SpiralCoordinateGenerator，Paper 26.2）")
    print(f"  点数          : {n}")
    print(f"  半径范围      : {mr.min():.1f} .. {mr.max():.1f}  (暖机 {warmup} 点后开始记录)")
    print(f"  索引区间      : {i_lo} .. {i_hi}")
    print(f"  解析容量      : 精确 {CAPACITY_EXACT}")
    print(f"  代码内容量    : {ring_zone_capacity()}")
    uniq = len(np.unique(mx * 100000 + mz))
    print(f"  去重点数      : {uniq}  (重复 {n - uniq})")

    model_xy, model_r = model_spiral(i_hi + 1)
    model_xy = model_xy[i_lo:i_hi + 1]
    model_r = model_r[i_lo:i_hi + 1]

    dens_m = areal_density(mr, r_lo=r_lo, r_hi=r_hi)
    dens_mo = areal_density(model_r, r_lo=r_lo, r_hi=r_hi)
    holes = int(np.sum(dens_m == 0.0))
    print(f"  面密度空洞    : {holes} 个（对照同索引区间后应为 0）")

    print()
    print("-" * 74)
    print(f"{'指标':<32}{'解析':>13}{'模型':>13}{'实测':>13}")
    print("-" * 74)

    print(f"{'面密度 极差 (式8 预测 0)':<32}{0.0:>13.4f}"
          f"{dens_mo.max() - dens_mo.min():>13.4f}{dens_m.max() - dens_m.min():>13.4f}")

    nnd_mo, nnd_m = nnd(model_xy), nnd(measured)
    print(f"{'最近邻 sigma/mu':<32}{'0.5227':>13}"
          f"{nnd_mo.std() / nnd_mo.mean():>13.4f}{nnd_m.std() / nnd_m.mean():>13.4f}")

    q_mo, q_m = quadrat(model_xy), quadrat(measured)
    print(f"{'面元计数 sigma/mu':<32}{'—':>13}"
          f"{q_mo.std() / q_mo.mean():>13.4f}{q_m.std() / q_m.mean():>13.4f}")

    # The design-relevant property is the minimum separation in 2D, not in angle.
    sub = measured[:20_000]
    mo_sub = model_xy[:20_000]
    min_m = cKDTree(sub).query(sub, k=2, workers=-1)[0][:, 1].min()
    min_mo = cKDTree(mo_sub).query(mo_sub, k=2, workers=-1)[0][:, 1].min()
    rng = np.random.default_rng(11)
    rand_xy = np.column_stack((
        np.sqrt(R_MIN ** 2 + rng.uniform(0, 1, 20_000) * (r_hi ** 2 - R_MIN ** 2))
        * np.cos(rng.uniform(0, 2 * math.pi, 20_000)),
        np.sqrt(R_MIN ** 2 + rng.uniform(0, 1, 20_000) * (r_hi ** 2 - R_MIN ** 2))
        * np.sin(rng.uniform(0, 2 * math.pi, 20_000))))
    min_rand = cKDTree(rand_xy).query(rand_xy, k=2, workers=-1)[0][:, 1].min()
    print(f"{'最小点间距 (20000 点, 格)':<32}{'—':>13}"
          f"{min_mo:>13.4f}{min_m:>13.4f}")
    print(f"  └ 同规模面积反变换随机采样: {min_rand:.4f} 格")

    print()
    print("-" * 74)
    print("角度间隙：三间隙定理在坐标层面何时失效")
    print(f"  取整使每点角度偏移约 0.5/r；r={R_MIN:.0f} 时 {0.5 / R_MIN:.2e} rad")
    print(f"{'N':>9}{'平均间隙(rad)':>16}{'扰动/间隙':>12}{'间隙0.05分辨率取值数':>22}{'最小间隙/均值':>16}")
    for n_gap in (987, 5_000, 20_000, 200_000):
        if n_gap > n:
            continue
        gm = angular_gaps_from_xy(measured, n_gap)
        mean_gap = 2.0 * math.pi / n_gap
        ratio = (0.5 / (R_MIN + 500)) / mean_gap
        buckets = len(np.unique(np.round(gm / 0.05)))
        print(f"{n_gap:>9}{mean_gap:>16.2e}{ratio:>12.2f}{buckets:>22}{gm.min():>16.4f}")

    ideal = np.mod(GOLDEN_ANGLE * np.arange(987), 2.0 * math.pi)
    s = np.sort(ideal)
    gi = np.diff(np.concatenate((s, [s[0] + 2.0 * math.pi])))
    gi = gi / (2.0 * math.pi / len(s))
    print(f"  理想角度 N=987: 取值={np.unique(np.round(gi, 6))} 最小间隙/均值={gi.min():.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
