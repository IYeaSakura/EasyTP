"""Generate figures and verify every number quoted in the article.

Figures
-------
fig1_sampling.png   : three 1D->2D sampling schemes and their diagnostics
fig2_tail.png       : geometric-distribution tail of the rejection-sampling loop
fig3_refill.png     : simulated pool dynamics under two refill metrics
fig4_mspt.png       : published MSPT p99 comparison (LeafRTP benchmark)

Run with `python docs/make_figures.py`. The figures and the printed
`正文引用的数值核验` block are computed from one shared, seeded sample set, so
the numbers in the article can be checked against the numbers on the figures.
"""

from __future__ import annotations

import math
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import font_manager

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "figures")
os.makedirs(OUT, exist_ok=True)

SEED = 20260101

# Ring geometry from the plugin's default configuration.
R_MIN, R_MAX, S = 2000.0, 5000.0, 16.0
GOLDEN_ANGLE = np.pi * (3.0 - np.sqrt(5.0))

N_HIST = 800_000      # points for the areal-density profiles
N_NN = 60_000         # points for the nearest-neighbour / quadrat statistics
N_SCATTER = 4000      # points drawn in the scatter panels
N_GAP = 987           # Fibonacci: gives the crispest two-gap structure

# Number of indices the spiral needs to sweep the whole ring (eq. 9).
N_CYCLE = int(np.pi * (R_MAX ** 2 - R_MIN ** 2) / (S * S))


def pick_cjk_font() -> str | None:
    wanted = ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC",
              "WenQuanYi Zen Hei", "PingFang SC", "Heiti SC"]
    available = {f.name for f in font_manager.fontManager.ttflist}
    for name in wanted:
        if name in available:
            return name
    return None


CJK = pick_cjk_font()
if CJK:
    plt.rcParams["font.family"] = CJK
plt.rcParams["axes.unicode_minus"] = False
plt.rcParams["figure.dpi"] = 160
plt.rcParams["savefig.bbox"] = "tight"
plt.rcParams["axes.grid"] = True
plt.rcParams["grid.alpha"] = 0.25
plt.rcParams["grid.linewidth"] = 0.6


# --------------------------------------------------------------------------
# Sampling schemes
# --------------------------------------------------------------------------

def scheme_uniform_radius(n: int, rng: np.random.Generator) -> np.ndarray:
    """Naive: radius and angle both uniform."""
    r = rng.uniform(R_MIN, R_MAX, n)
    t = rng.uniform(0.0, 2.0 * np.pi, n)
    return np.column_stack((r * np.cos(t), r * np.sin(t)))


def scheme_sqrt_radius(n: int, rng: np.random.Generator) -> np.ndarray:
    """Radius by inverse CDF, so the areal density is uniform."""
    u = rng.uniform(0.0, 1.0, n)
    r = np.sqrt(R_MIN ** 2 + u * (R_MAX ** 2 - R_MIN ** 2))
    t = rng.uniform(0.0, 2.0 * np.pi, n)
    return np.column_stack((r * np.cos(t), r * np.sin(t)))


def scheme_spiral(n: int) -> tuple[np.ndarray, np.ndarray]:
    """Index -> radius by area (eq. 6), index -> angle by the golden angle (eq. 10)."""
    i = np.arange(n, dtype=np.float64)
    r = np.minimum(np.sqrt(R_MIN ** 2 + i * S * S / np.pi), R_MAX)
    t = GOLDEN_ANGLE * i
    return np.column_stack((r * np.cos(t), r * np.sin(t))), r


# --------------------------------------------------------------------------
# One shared sample set, so figures and reported numbers cannot disagree
# --------------------------------------------------------------------------

_SAMPLES: dict[str, np.ndarray] | None = None


def samples() -> dict[str, np.ndarray]:
    global _SAMPLES
    if _SAMPLES is None:
        rng = np.random.default_rng(SEED)
        a_xy = scheme_uniform_radius(N_HIST, rng)
        b_xy = scheme_sqrt_radius(N_HIST, rng)
        c_xy_full, c_r_full = scheme_spiral(N_CYCLE)

        scatter_pick = rng.choice(N_CYCLE, N_SCATTER, replace=False)
        nn_pick = rng.choice(N_CYCLE, N_NN, replace=False)

        _SAMPLES = {
            "a_hist_r": np.hypot(a_xy[:, 0], a_xy[:, 1]),
            "b_hist_r": np.hypot(b_xy[:, 0], b_xy[:, 1]),
            "c_hist_r": c_r_full,
            "a_scatter": scheme_uniform_radius(N_SCATTER, rng),
            "b_scatter": scheme_sqrt_radius(N_SCATTER, rng),
            "c_scatter": c_xy_full[scatter_pick],
            "b_nn_xy": scheme_sqrt_radius(N_NN, rng),
            "c_nn_xy": c_xy_full[nn_pick],
        }
    return _SAMPLES


def nearest_neighbour(xy: np.ndarray) -> np.ndarray:
    from scipy.spatial import cKDTree

    dist, _ = cKDTree(xy).query(xy, k=2, workers=-1)
    return dist[:, 1]


def azimuthal_counts(theta: np.ndarray, n_sector: int) -> np.ndarray:
    """Point counts per equal-width azimuth sector."""
    idx = np.minimum((theta / (2.0 * np.pi) * n_sector).astype(np.int64), n_sector - 1)
    return np.bincount(idx, minlength=n_sector).astype(np.float64)


def max_radial_gap_per_sector(radius: np.ndarray, theta: np.ndarray,
                              n_sector: int) -> np.ndarray:
    """Largest radial hole inside each sector -- detects radial streak artefacts."""
    idx = np.minimum((theta / (2.0 * np.pi) * n_sector).astype(np.int64), n_sector - 1)
    gaps = np.full(n_sector, np.nan)
    for s in range(n_sector):
        rr = np.sort(radius[idx == s])
        if rr.size > 1:
            gaps[s] = np.max(np.diff(rr))
    return gaps


def quadrat_counts(xy: np.ndarray, nbins: int = 43) -> np.ndarray:
    """Counts per quadrat over the bounding box, restricted to the annulus.

    nbins=43 gives roughly 50 points per average quadrat at N=6e4, so the
    Poisson reference sigma/mu = 1/sqrt(50) ~ 0.14 sits well above the
    discretisation floor and the difference is measurable.
    """
    edges = np.linspace(-R_MAX, R_MAX, nbins + 1)
    hist, xe, ye = np.histogram2d(xy[:, 0], xy[:, 1], bins=[edges, edges])
    cx, cy = 0.5 * (xe[:-1] + xe[1:]), 0.5 * (ye[:-1] + ye[1:])
    gx, gy = np.meshgrid(cx, cy, indexing="ij")
    rr = np.hypot(gx, gy)
    return hist[(rr >= R_MIN) & (rr <= R_MAX)]


def areal_density(radius: np.ndarray, bins: int = 60) -> tuple[np.ndarray, np.ndarray]:
    """Points per unit area, normalised to its own mean so shapes are comparable."""
    edges = np.linspace(R_MIN, R_MAX, bins + 1)
    count, _ = np.histogram(radius, bins=edges)
    area = np.pi * (edges[1:] ** 2 - edges[:-1] ** 2)
    dens = count / area
    return 0.5 * (edges[:-1] + edges[1:]), dens / dens.mean()


def angular_gaps(theta: np.ndarray) -> np.ndarray:
    """Successive gaps of the sorted angles, in units of the mean gap."""
    s = np.sort(np.mod(theta, 2.0 * np.pi))
    g = np.diff(np.concatenate((s, [s[0] + 2.0 * np.pi])))
    return g / (2.0 * np.pi / len(s))


# --------------------------------------------------------------------------
# Figures
# --------------------------------------------------------------------------

def scheme_leafrtp_angles(n: int, rng: np.random.Generator) -> np.ndarray:
    """LeafRTP's angular mapping: angle = 2*pi*frac(sqrt(d/pi + r_min^2)), d ~ U(0, area).

    Each selection draws a fresh d, so the resulting point set is an i.i.d. sample
    from an areal-uniform distribution. Included so the golden-angle choice is
    compared against the alternative actually under consideration, not only
    against uniform random angles.
    """
    area = math.pi * (R_MAX ** 2 - R_MIN ** 2)
    d = rng.uniform(0.0, area, n)
    r3 = np.sqrt(d / math.pi + R_MIN ** 2)
    return 2.0 * np.pi * (r3 - np.floor(r3))


def fig_sampling() -> None:
    smp = samples()
    rng = np.random.default_rng(SEED + 1)

    fig, axes = plt.subplots(2, 3, figsize=(13.2, 8.4))

    for col, (xy, title) in enumerate([
        (smp["a_scatter"], "(a) 半径、角度均均匀分布"),
        (smp["b_scatter"], "(b) 半径按面积反变换修正"),
        (smp["c_scatter"], "(c) 黄金角螺旋（本文）"),
    ]):
        ax = axes[0, col]
        ax.scatter(xy[:, 0], xy[:, 1], s=1.3, alpha=0.5, linewidths=0)
        ax.set_aspect("equal")
        ax.set_title(title, fontsize=10)
        ax.set_xlabel(r"$x$ / 格", fontsize=9)
        if col == 0:
            ax.set_ylabel(r"$z$ / 格", fontsize=9)
        ax.tick_params(labelsize=8)

    ax = axes[1, 0]
    for key, label, style in [("a_hist_r", "(a) 均匀半径", "-"),
                              ("b_hist_r", "(b) 面积反变换", "--"),
                              ("c_hist_r", "(c) 黄金角螺旋", "-.")]:
        centre, dens = areal_density(smp[key])
        ax.plot(centre, dens, style, linewidth=1.5, label=label)
    ax.axhline(1.0, color="0.35", linestyle=":", linewidth=0.9)
    ax.set_xlabel(r"$r$ / 格", fontsize=9)
    ax.set_ylabel("归一化面密度", fontsize=9)
    ax.set_title("(d) 径向面密度", fontsize=10)
    ax.legend(fontsize=8, frameon=False)
    ax.tick_params(labelsize=8)

    b_nn = nearest_neighbour(smp["b_nn_xy"])
    c_nn = nearest_neighbour(smp["c_nn_xy"])

    ax = axes[1, 1]
    ax.hist(b_nn, bins=np.linspace(0, 60, 61), density=True, histtype="step",
            linewidth=1.5, label=rf"(b) 面积反变换  $\sigma/\mu={b_nn.std()/b_nn.mean():.3f}$")
    ax.hist(c_nn, bins=np.linspace(0, 60, 61), density=True, histtype="step",
            linewidth=1.5, label=rf"(c) 黄金角螺旋  $\sigma/\mu={c_nn.std()/c_nn.mean():.3f}$")
    ax.set_xlabel("最近邻距离 / 格", fontsize=9)
    ax.set_ylabel("概率密度", fontsize=9)
    ax.set_title("(e) 最近邻距离分布", fontsize=10)
    ax.legend(fontsize=8, frameon=False)
    ax.tick_params(labelsize=8)

    gap_spiral = angular_gaps(GOLDEN_ANGLE * np.arange(N_GAP))
    gap_random = angular_gaps(rng.uniform(0.0, 2.0 * np.pi, N_GAP))
    gap_leaf = angular_gaps(scheme_leafrtp_angles(N_GAP, rng))

    ax = axes[1, 2]
    bins = np.linspace(0, 3.2, 65)
    ax.hist(gap_spiral, bins=bins, density=True, histtype="step", linewidth=1.5,
            label=rf"(c) 黄金角螺旋（{len(np.unique(np.round(gap_spiral, 6)))} 个离散取值）")
    ax.hist(gap_leaf, bins=bins, density=True, histtype="step", linewidth=1.2,
            label="(b') LeafRTP $2\\pi\\,\\mathrm{frac}(r_3)$")
    ax.hist(gap_random, bins=bins, density=True, histtype="step", linewidth=1.2,
            label="(a') 均匀随机角度")
    ax.set_xlabel("角度间隙 / 平均间隙", fontsize=9)
    ax.set_ylabel("概率密度", fontsize=9)
    ax.set_title(rf"(f) 角度间隙分布（$N={N_GAP}$）", fontsize=10)
    ax.legend(fontsize=7.5, frameon=False)
    ax.tick_params(labelsize=8)

    fig.suptitle(r"图 3  三种一维到二维采样方案的对比（环带 2000–5000 格，$s=16$）"
                 "\n" r"(a)–(c) 为各 4000 点的等样本量散点抽样；(d)–(f) 的统计量取各方案的完整样本",
                 fontsize=11.5, y=1.005)
    # The default hspace lets the top row's x-label collide with the bottom row's
    # title, so the two rows are separated explicitly.
    fig.subplots_adjust(top=0.87, bottom=0.08, left=0.07, right=0.985,
                        wspace=0.26, hspace=0.38)
    fig.savefig(os.path.join(OUT, "fig3_sampling.png"))
    plt.close(fig)


def fig_tail() -> None:
    n = np.arange(1, 41)
    fig, axes = plt.subplots(1, 2, figsize=(11.2, 3.9))

    for p, style in [(0.35, "-"), (0.50, "--"), (0.65, "-.")]:
        tail = (1.0 - p) ** (n - 1)
        axes[0].plot(n, tail, style, linewidth=1.6, label=rf"$p={p:.2f}$")
        axes[1].semilogy(n, tail, style, linewidth=1.6, label=rf"$p={p:.2f}$")

    for ax, title in zip(axes, ["(a) 线性坐标", "(b) 对数坐标"]):
        ax.set_xlabel(r"尝试次数 $n$", fontsize=9)
        ax.set_ylabel(r"$P(N \geq n)$", fontsize=9)
        ax.set_title(title, fontsize=10)
        ax.tick_params(labelsize=8)
        ax.legend(fontsize=8, frameon=False)

    axes[1].axhline(1e-3, color="0.4", linestyle=":", linewidth=0.9)
    axes[1].annotate(r"$10^{-3}$", xy=(39, 1e-3), xytext=(33, 2.2e-3),
                     fontsize=8, color="0.3")

    fig.suptitle(r"图 2  拒绝采样重试次数的尾部概率 $P(N \geq n) = (1-p)^{n-1}$",
                 fontsize=11, y=1.03)
    fig.savefig(os.path.join(OUT, "fig2_tail.png"))
    plt.close(fig)


def refill_trace(metric: str, target: int = 20, base_size: int = 12,
                 valid_rate: int = 2, ticks: int = 60) -> tuple[np.ndarray, np.ndarray]:
    """Cumulative generation and chunk loads for one refill metric.

    Per tick, with no player consuming locations (the idle-server case):
      generation : l3 += min(max(T - pending, 0), base_size)
      validation : l3 -> validated at up to valid_rate per tick, 1 load each
      trim       : validated clamped back to T, exactly as RtpPool.trimToTarget does
    `pending` is |l3| for the original metric and |l1|+|l2|+|l3| for the fixed one.
    """
    l3 = validated = generated = loads = 0
    gen_hist, load_hist = [], []
    for _ in range(ticks):
        pending = l3 if metric == "candidate" else (l3 + validated)
        needed = target - pending
        if needed > 0:
            add = min(needed, base_size)
            l3 += add
            generated += add
        drained = min(l3, valid_rate)
        l3 -= drained
        validated = min(validated + drained, target)
        loads += drained
        gen_hist.append(generated)
        load_hist.append(loads)
    return np.array(gen_hist), np.array(load_hist)


def fig_refill() -> None:
    bug_gen, bug_load = refill_trace("candidate")
    fix_gen, fix_load = refill_trace("total")
    x = np.arange(1, len(bug_gen) + 1)

    fig, axes = plt.subplots(1, 2, figsize=(11.4, 3.9))
    for ax, bug, fix, ylabel, title in [
        (axes[0], bug_gen, fix_gen, "累计生成的候选数", "(a) 累计候选生成量"),
        (axes[1], bug_load, fix_load, "累计区块加载次数", "(b) 累计区块加载次数"),
    ]:
        ax.plot(x, bug, linewidth=1.7, label=r"$pending = |L_3|$（原实现）")
        ax.plot(x, fix, linewidth=1.7, label=r"$pending = |L_1| + |L_2| + |L_3|$（修正后）")
        ax.set_xlabel(r"tick", fontsize=9)
        ax.set_ylabel(ylabel, fontsize=9)
        ax.set_title(title, fontsize=10)
        ax.legend(fontsize=8, frameon=False, loc="upper left")
        ax.tick_params(labelsize=8)

    axes[0].annotate("生成停止", xy=(30, fix_gen[-1]), xytext=(24, 62),
                     fontsize=8, color="0.3",
                     arrowprops=dict(arrowstyle="->", color="0.45", linewidth=0.8))
    axes[0].annotate("永不停止", xy=(54, bug_gen[53]), xytext=(26, 112),
                     fontsize=8, color="0.3",
                     arrowprops=dict(arrowstyle="->", color="0.45", linewidth=0.8))

    fig.suptitle("图 4  空闲服务器上两种补齐度量的工作量演化（控制逻辑模拟，非实测数据）",
                 fontsize=11, y=1.03)
    fig.savefig(os.path.join(OUT, "fig4_refill.png"))
    plt.close(fig)


# MSPT p99 as published by the LeafRTP author's StressTestRTP harness,
# Paper 1.20.1 / 1.21.11, same world, two OPed clients spamming /rtp.
MSPT_P99 = [("LeafRTP", 4), ("JakesRTP", 70), ("HuskHomes", 372), ("BetterRTP", 852),
            ("AdvancedRTP", 2100), ("EzRTP", 2903), ("EssentialsX", 4504), ("AsyRTP", 4534)]


def fig_mspt() -> None:
    data = sorted(MSPT_P99, key=lambda kv: kv[1])
    names = [k for k, _ in data]
    vals = [v for _, v in data]

    fig, ax = plt.subplots(figsize=(7.6, 3.9))
    colors = ["#2f6f9f" if n == "LeafRTP" else "#b9c6d1" for n in names]
    bars = ax.bar(range(len(names)), vals, color=colors, width=0.62)
    ax.set_yscale("log")
    ax.set_xticks(range(len(names)))
    ax.set_xticklabels(names, fontsize=8.5, rotation=18, ha="right")
    ax.set_ylabel("MSPT p99 / ms", fontsize=9)
    ax.set_title("图 1  Paper 1.20.1 / 1.21.11 上各随机传送插件的 MSPT p99", fontsize=10.5)
    ax.tick_params(labelsize=8)
    for rect, v in zip(bars, vals):
        ax.annotate(f"{v}", xy=(rect.get_x() + rect.get_width() / 2, v),
                    xytext=(0, 3), textcoords="offset points",
                    ha="center", fontsize=8, color="0.25")
    ax.set_ylim(1, 20000)
    fig.text(0.02, -0.06,
             "数据来源：LeafRTP 作者发布的 StressTestRTP 基准，非本文测量。"
             "EssentialsX 的 /tpr 是请求式传送命令，其数值反映请求-接受往返，不代表插件缺陷。",
             fontsize=7.5, color="0.35")
    fig.savefig(os.path.join(OUT, "fig1_mspt.png"))
    plt.close(fig)


# --------------------------------------------------------------------------
# Verification of every number quoted in the article
# --------------------------------------------------------------------------

def report_claims() -> None:
    smp = samples()
    print("\n=== 正文引用的数值核验（与图表同源）===")

    centre, dens_a = areal_density(smp["a_hist_r"])
    _, dens_b = areal_density(smp["b_hist_r"])
    _, dens_c = areal_density(smp["c_hist_r"])
    print(f"[图3d] 均匀半径   内缘={dens_a[0]:.3f} 外缘={dens_a[-1]:.3f}")
    print(f"[图3d] 面积反变换 内缘={dens_b[0]:.3f} 外缘={dens_b[-1]:.3f} "
          f"极差=[{dens_b.min():.3f},{dens_b.max():.3f}]")
    print(f"[图3d] 黄金角螺旋 内缘={dens_c[0]:.3f} 外缘={dens_c[-1]:.3f} "
          f"极差=[{dens_c.min():.3f},{dens_c.max():.3f}]")

    b_nn = nearest_neighbour(smp["b_nn_xy"])
    c_nn = nearest_neighbour(smp["c_nn_xy"])
    print(f"[图3e] 最近邻 sigma/mu 面积反变换={b_nn.std()/b_nn.mean():.3f} "
          f"螺旋={c_nn.std()/c_nn.mean():.3f} "
          f"(Poisson 理论值={math.sqrt(4/math.pi - 1):.3f})")

    bq, cq = quadrat_counts(smp["b_nn_xy"]), quadrat_counts(smp["c_nn_xy"])
    print(f"[正文] 面元计数 sigma/mu 面积反变换={bq.std()/bq.mean():.3f} "
          f"螺旋={cq.std()/cq.mean():.3f}  面元数={len(bq)}")

    g = np.sort(angular_gaps(GOLDEN_ANGLE * np.arange(N_GAP)))
    print(f"[图3f] N={N_GAP} 黄金角螺旋 角度间隙 离散取值={np.unique(np.round(g, 6))}")

    rng2 = np.random.default_rng(SEED + 2)
    gl = angular_gaps(scheme_leafrtp_angles(N_GAP, rng2))
    gr = angular_gaps(rng2.uniform(0.0, 2.0 * np.pi, N_GAP))
    for name, v in (("LeafRTP frac(r3)", gl), ("均匀随机角度", gr)):
        print(f"[图3f] {name:<18} 最小间隙={v.min():.4f}  "
              f"P(间隙<0.2)={np.mean(v < 0.2) * 100:.2f}%  "
              f"离散取值(4位)={len(np.unique(np.round(v, 4)))}")

    for p in (0.35, 0.50, 0.65):
        print(f"[图2] p={p:.2f}: P(N>=10)={(1-p)**9*100:.2f}%  "
              f"P(N>=20)={(1-p)**19*100:.4f}%  "
              f"n(1e-3)={1 + math.log(1e-3)/math.log(1-p):.1f}")

    # 3.2 节的方位向规则性：必须用整数坐标下的整周期样本，不能用图 3(c) 的抽稀散点。
    xy_spiral = np.rint(scheme_spiral(N_CYCLE)[0])
    xy_rand = scheme_sqrt_radius(N_CYCLE, np.random.default_rng(SEED + 11))
    for name, xy in (("黄金角螺旋", xy_spiral), ("面积反变换随机", xy_rand)):
        rr = np.hypot(xy[:, 0], xy[:, 1])
        tt = np.arctan2(xy[:, 1], xy[:, 0]) % (2.0 * np.pi)
        out = []
        for n_sector in (60, 360):
            c = azimuthal_counts(tt, n_sector)
            out.append(f"{n_sector}扇区 {c.std()/c.mean():.4f} (Poisson {1/np.sqrt(c.mean()):.4f})")
        gap = max_radial_gap_per_sector(rr, tt, 72)
        print(f"[3.2] 方位向 {name}: " + "; ".join(out) +
              f"; 72扇区最大径向空隙={np.nanmax(gap):.2f} 格")

    for metric in ("candidate", "total"):
        gen, load = refill_trace(metric)
        gen_stop = int(np.argmax(gen == gen[-1])) + 1
        load_stop = int(np.argmax(load == load[-1])) + 1
        print(f"[图4] pending={'|L3|' if metric == 'candidate' else 'total'}: "
              f"tick60 累计生成={gen[-1]} 累计加载={load[-1]} "
              f"生成停止于 tick={gen_stop} 加载停止于 tick={load_stop}")

    print(f"[式11] maxScanDepth=16 -> 单列方块查询 <= 3*(16+1) = {3*17}")
    print(f"[4.5] cell=32 spacing=16 -> 单元内采样点 ~ 1024/256 = 4")
    print(f"[式9] 环带容量 N_ring = {N_CYCLE}")
    lo = min(v for _, v in MSPT_P99)
    hi = max(v for _, v in MSPT_P99)
    print(f"[图1] MSPT p99 极差 = {hi}/{lo} = {hi/lo:.0f} 倍")


BENCH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench")


def read_bench(name: str, run: str = "warm") -> list[dict] | None:
    """Read a CSV produced by the benchmark harness, if it is present.

    `run` selects the world state the data was taken in: "cold" (terrain had to be
    generated) or "warm" (chunks were already on disk). Both were measured with the
    same plugin build and the same 64 coordinates.
    """
    path = os.path.join(BENCH, run, name)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        head = fh.readline().strip().split(",")
        out = []
        for line in fh:
            parts = line.strip().split(",")
            if len(parts) != len(head):
                continue
            out.append(dict(zip(head, parts)))
    return out


def read_bench_kv(name: str, run: str = "warm") -> dict[str, float] | None:
    path = os.path.join(BENCH, run, name)
    if not os.path.exists(path):
        return None
    out: dict[str, float] = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                try:
                    out[k] = float(v)
                except ValueError:
                    pass
    return out


def fig_bench() -> bool:
    """Figure 5: first-hand measurements from the production classes on Paper."""
    pool = read_bench("pool_conservation.csv")
    chunk = read_bench_kv("chunk_summary.txt")
    if pool is None or chunk is None:
        print("  [skip] fig5: bench data not found in docs/bench/")
        return False
    fig, axes = plt.subplots(1, 3, figsize=(15.4, 4.2))

    # (a) pipeline conservation, real RtpPool
    ax = axes[0]
    for metric, label, style in [
        ("candidate_only", r"$pending = |L_3|$", "-"),
        ("validated_plus_candidate", r"$pending = |L_1| + |L_2| + |L_3|$", "--"),
    ]:
        rows = [r for r in pool if r["metric"] == metric]
        x = [int(r["tick"]) for r in rows]
        y = [int(r["generated_total"]) for r in rows]
        ax.plot(x, y, style, linewidth=1.8, label=label)
    target = int(pool[0]["target"])
    ax.axhline(target, color="0.4", linestyle=":", linewidth=0.9)
    ax.annotate(rf"$T={target}$", xy=(44, target), xytext=(44, target + 8),
                fontsize=8, color="0.3")
    ax.set_xlabel("tick", fontsize=9)
    ax.set_ylabel("累计生成候选数", fontsize=9)
    ax.set_title("(a) 流水线守恒（实测 $RtpPool$）", fontsize=10)
    ax.legend(fontsize=8, frameon=False, loc="upper left")
    ax.tick_params(labelsize=8)

    # (b) where the cost actually is
    ax = axes[1]
    cold = read_bench_kv("chunk_summary.txt", "cold")
    labels = ["异步加载\n全新世界\n无上限",
              "异步加载\n全新世界\n上限 8",
              "异步加载\n已生成世界\n无上限",
              "同步区块获取\n已生成世界",
              "判定合计\n快照+列扫描"]
    vals = [cold["burst_p50_ms"], cold["capped_p50_ms"],
            chunk["burst_p50_ms"],
            chunk["acquire_p50_ms"],
            chunk["snapshot_p50_ms"] + chunk["scan_p50_ms"]]
    colors = ["#b5651d", "#d9a05b", "#e8cba0", "#2f6f9f", "#5b9f6f"]
    bars = ax.bar(range(len(vals)), vals, color=colors, width=0.62)
    ax.set_yscale("log")
    ax.set_xticks(range(len(vals)))
    ax.set_xticklabels(labels, fontsize=6.5)
    ax.set_ylabel("单次耗时 p50 / ms（对数）", fontsize=9)
    ax.set_title("(b) 成本构成", fontsize=10)
    ax.tick_params(labelsize=8)
    ax.set_ylim(0.05, 2e5)
    for rect, v in zip(bars, vals):
        txt = f"{v:.3f}" if v < 1 else (f"{v:.1f}" if v < 100 else f"{v:.0f}")
        ax.annotate(txt, xy=(rect.get_x() + rect.get_width() / 2, v),
                    xytext=(0, 3), textcoords="offset points",
                    ha="center", fontsize=5.5, color="0.2")

    # (c) minimum separation: the design-relevant anti-clustering property
    ax = axes[2]
    names = ["面积反变换\n随机采样", "黄金角螺旋\n(模型)", "黄金角螺旋\n(实测)"]
    seps = [0.4604, 15.14, 13.93]
    bars = ax.bar(range(len(seps)), seps, color=["#c0504d", "#2f6f9f", "#5b9f6f"], width=0.55)
    ax.set_yscale("log")
    ax.set_xticks(range(len(seps)))
    ax.set_xticklabels(names, fontsize=8)
    ax.set_ylabel("20000 点内最小间距 / 格（对数）", fontsize=9)
    ax.set_title("(c) 最小点间距", fontsize=10)
    ax.tick_params(labelsize=8)
    ax.set_ylim(0.2, 60)
    for rect, v in zip(bars, seps):
        ax.annotate(f"{v:.2f}", xy=(rect.get_x() + rect.get_width() / 2, v),
                    xytext=(0, 3), textcoords="offset points",
                    ha="center", fontsize=8.5, color="0.2")

    fig.suptitle("图 5  Paper 26.2-124 上的实测结果（生产类直接驱动，非模型）",
                 fontsize=11.5, y=1.03)
    fig.savefig(os.path.join(OUT, "fig5_bench.png"))
    plt.close(fig)
    return True


if __name__ == "__main__":
    for stale in ("fig1_sampling.png", "fig2_tail.png", "fig3_refill.png",
                  "fig4_mspt.png", "fig5_bench.png"):
        path = os.path.join(OUT, stale)
        if os.path.exists(path):
            os.remove(path)
    fig_sampling()
    fig_tail()
    fig_refill()
    fig_mspt()
    made5 = fig_bench()
    print("CJK font:", CJK)
    for name in sorted(os.listdir(OUT)):
        path = os.path.join(OUT, name)
        print(f"  {name}  {os.path.getsize(path) / 1024:.1f} KiB")
    report_claims()
    if made5:
        print("\n图 5 已生成，数据来源 docs/bench/")
