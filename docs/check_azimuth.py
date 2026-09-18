"""Checks one narrow claim about the golden-angle spiral: inside a truncated annulus,
does it leave systematic azimuthal gaps or radial streak artefacts?

The shipped generator emits integer block coordinates, so the primary test runs on the
rounded coordinates of a full index cycle rather than on the float scatter panel. The
area-reverse-transform scheme at the same population is used as the null baseline.

The statistics used by 3.2 of the article are printed by make_figures.report_claims();
this script is the wider diagnostic behind them (thin radial shells, per-sector radial
holes, and the thinning experiment that explains 图 3(c)).

Run:  python check_azimuth.py     (same conda env as make_figures.py)
"""

from __future__ import annotations

import numpy as np

import make_figures as mf


def cv_of_counts(counts: np.ndarray) -> float:
    return float(counts.std() / counts.mean())


def report(name: str, xy: np.ndarray) -> None:
    r = np.hypot(xy[:, 0], xy[:, 1])
    t = np.arctan2(xy[:, 1], xy[:, 0]) % (2.0 * np.pi)
    print(f"\n=== {name}  (N = {r.size}) ===")

    # 1. Azimuthal marginal over the whole annulus.  Under a Poisson process the
    #    coefficient of variation of the counts is 1/sqrt(mean count).
    for n_sector in (60, 360):
        c = mf.azimuthal_counts(t, n_sector)
        print(
            f"  azimuthal marginal, {n_sector:3d} sectors: "
            f"CV = {cv_of_counts(c):.4f} (Poisson {1/np.sqrt(c.mean()):.4f}), "
            f"min/mean = {c.min()/c.mean():.3f}, max/mean = {c.max()/c.mean():.3f}"
        )

    # 2. Thin radial shells.  A radial streak would concentrate a shell's points into
    #    a few azimuths, which shows up as an inflated CV here.
    for r0, width in ((2200.0, 100.0), (3000.0, 100.0), (3900.0, 100.0), (4800.0, 100.0)):
        m = (r >= r0) & (r < r0 + width)
        if m.sum() < 50:
            print(f"  shell r = {r0:.0f}..{r0+width:.0f}: too few points ({m.sum()})")
            continue
        c = mf.azimuthal_counts(t[m], 360)
        print(
            f"  shell r = {r0:.0f}..{r0+width:.0f}: {m.sum():6d} points, "
            f"CV = {cv_of_counts(c):.4f} (Poisson {1/np.sqrt(c.mean()):.4f}), "
            f"empty sectors = {int((c == 0).sum())}/360"
        )

    # 3. Largest radial hole inside each azimuthal sector -- "some azimuths leave a
    #    systematically larger hole in the radial direction".
    n_sector = 72
    span = float(r.max()) - mf.R_MIN
    gaps = mf.max_radial_gap_per_sector(r, t, n_sector)
    print(
        f"  largest radial gap per {n_sector} azimuth sector: mean "
        f"{np.nanmean(gaps):.2f} blocks ({np.nanmean(gaps)/span:.4f} of the radial span), "
        f"max {np.nanmax(gaps):.2f} blocks ({np.nanmax(gaps)/span:.4f})"
    )


def main() -> None:
    spiral_int = np.rint(mf.scheme_spiral(mf.N_CYCLE)[0])   # what the generator emits
    random_xy = mf.scheme_sqrt_radius(mf.N_CYCLE, np.random.default_rng(mf.SEED + 11))

    report("golden-angle spiral, integer coordinates, one full cycle", spiral_int)
    report("area-reverse-transform random, same population", random_xy)

    # 图 3(c) is a 4000-point thinning of the cycle.  Thinning by index is Bernoulli
    # sampling, so its appearance is not the generator's density.
    report("图 3(c) scatter panel (4000-point index thinning, float coords)",
           mf.samples()["c_scatter"])


if __name__ == "__main__":
    main()
