"""
Approximate backtest of the SENSEX opening-move strategy on 1-minute SENSEX candles.

Why approximate: Angel One history only goes down to 1-minute candles, and expired
option contracts can't be downloaded. So this measures SENSEX index points and converts
them to option premium with a fixed delta (ATM ~0.5): 15 premium pts ~= 30 SENSEX pts.

Two variants are tested:

  GAP rule (exact signal):  9:15 open vs yesterday's close -> CE if up, PE if down.
                            Enter at the 9:15 open, exit at +target or 9:16 (end of first candle).
                            With no stop-loss, "did the target get touched inside the minute"
                            is fully answered by the candle high/low.

  MOMENTUM (1 minute later): the 10-second move can't be seen in 1-minute data, so this
                            uses the same idea one minute later: direction of the 9:15 candle
                            decides the side, enter at 9:16 open, exit at +target or 9:17.

Run (uses the same config.json as gap_open.py):
  python backtest.py                 # last 12 months
  python backtest.py --months 24 --delta 0.5 --target 15 --slippage 1 --charges 60
"""

import argparse
import csv
import json
import os
import statistics
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).parent
SENSEX_TOKEN = "99919000"
LOT = 20


# ─── data ─────────────────────────────────────────────────────────────────────

def download(months):
    """Fetches SENSEX 1-minute candles from Angel One, 30 days per request (API limit)."""
    import pyotp
    from SmartApi import SmartConnect

    # config.json on your PC, or environment variables (e.g. a cloud session's settings)
    if (HERE / "config.json").exists():
        cfg = json.loads((HERE / "config.json").read_text())
    else:
        env = {k: os.environ.get(f"ANGEL_{k.upper()}") for k in ("api_key", "client_id", "mpin", "totp_secret")}
        if not all(env.values()):
            sys.exit("No config.json and ANGEL_API_KEY / ANGEL_CLIENT_ID / ANGEL_MPIN / ANGEL_TOTP_SECRET not set.")
        cfg = env
    api = SmartConnect(api_key=cfg["api_key"])
    resp = api.generateSession(cfg["client_id"], cfg["mpin"], pyotp.TOTP(cfg["totp_secret"]).now())
    if not resp or not resp.get("status"):
        sys.exit(f"Login failed: {resp}")

    end = datetime.now().replace(hour=15, minute=30, second=0, microsecond=0)
    start = end - timedelta(days=months * 30)
    rows = []
    chunk_start = start
    while chunk_start < end:
        chunk_end = min(chunk_start + timedelta(days=29), end)
        params = {"exchange": "BSE", "symboltoken": SENSEX_TOKEN, "interval": "ONE_MINUTE",
                  "fromdate": chunk_start.strftime("%Y-%m-%d 09:15"), "todate": chunk_end.strftime("%Y-%m-%d 15:30")}
        for attempt in range(3):
            try:
                data = (api.getCandleData(params) or {}).get("data") or []
                break
            except Exception as e:
                print(f"  retry {chunk_start:%Y-%m-%d}: {e}")
                time.sleep(2)
        else:
            data = []
        print(f"  {chunk_start:%Y-%m-%d} -> {chunk_end:%Y-%m-%d}: {len(data)} candles")
        for ts, o, h, l, c, *_ in data:
            t = ts[11:16]
            # keep only what the backtest needs: the opening minutes and the day's last candle
            if t <= "09:20" or t >= "15:25":
                rows.append({"date": ts[:10], "time": t, "open": o, "high": h, "low": l, "close": c})
        chunk_start = chunk_end + timedelta(days=1)
        time.sleep(0.5)   # stay under the historical API rate limit

    path = HERE / f"sensex_1min_{start:%Y%m%d}_{end:%Y%m%d}.csv"
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["date", "time", "open", "high", "low", "close"])
        w.writeheader()
        w.writerows(rows)
    print(f"Saved {len(rows)} rows to {path.name}")
    return path


def load(path):
    days = defaultdict(dict)
    with open(path) as f:
        for r in csv.DictReader(f):
            days[r["date"]][r["time"]] = {k: float(r[k]) for k in ("open", "high", "low", "close")}
    return dict(sorted(days.items()))


# ─── backtest ─────────────────────────────────────────────────────────────────

def trade(side, candle, entry, target_idx):
    """Index-point result of a long CE (side=+1) / long PE (side=-1) entered at `entry`
    and exited at +target_idx or the candle close. Returns (points, hit)."""
    favourable = (candle["high"] - entry) if side > 0 else (entry - candle["low"])
    if target_idx and favourable >= target_idx:
        return target_idx, True
    return side * (candle["close"] - entry), False


def run(days, a):
    target_idx = a.target / a.delta if a.target else None
    cost_pts = 2 * a.slippage                       # premium points lost per round trip
    results = {"GAP rule": [], "MOMENTUM (1 min later)": []}
    prev_close = None
    for date, mins in days.items():
        c915, c916 = mins.get("09:15"), mins.get("09:16")
        last = mins.get("15:29") or mins.get("15:28") or (mins[max(mins)] if mins else None)
        if c915 and prev_close:
            gap = c915["open"] - prev_close
            if gap != 0 and abs(gap) >= a.min_move:
                side = 1 if gap > 0 else -1
                pts, hit = trade(side, c915, c915["open"], target_idx)
                results["GAP rule"].append(row(date, side, gap, pts, hit, a, cost_pts))
            if c916:
                move = c915["close"] - c915["open"]
                if move != 0 and abs(move) >= a.min_move:
                    side = 1 if move > 0 else -1
                    pts, hit = trade(side, c916, c916["open"], target_idx)
                    results["MOMENTUM (1 min later)"].append(row(date, side, move, pts, hit, a, cost_pts))
        if last:
            prev_close = last["close"]
    return results


def row(date, side, signal, idx_pts, hit, a, cost_pts):
    prem = idx_pts * a.delta - cost_pts
    rupees = prem * a.lots * LOT - a.charges
    return {"date": date, "side": "CE" if side > 0 else "PE", "signal": round(signal, 2),
            "sensex_pts": round(idx_pts, 2), "premium_pts": round(prem, 2), "hit": hit, "pnl": round(rupees, 2)}


# ─── report ───────────────────────────────────────────────────────────────────

def summarise(name, trades):
    if not trades:
        return f"\n{name}: no trades\n", {}
    pnl = [t["pnl"] for t in trades]
    wins = [p for p in pnl if p > 0]
    losses = [p for p in pnl if p <= 0]
    equity, peak, max_dd = 0, 0, 0
    for p in pnl:
        equity += p
        peak = max(peak, equity)
        max_dd = min(max_dd, equity - peak)
    by_month = defaultdict(float)
    for t in trades:
        by_month[t["date"][:7]] += t["pnl"]
    s = {
        "trades": len(trades), "target_hits": sum(t["hit"] for t in trades),
        "win_rate": 100 * len(wins) / len(trades), "total": sum(pnl), "avg": statistics.mean(pnl),
        "avg_win": statistics.mean(wins) if wins else 0, "avg_loss": statistics.mean(losses) if losses else 0,
        "best": max(pnl), "worst": min(pnl), "max_dd": max_dd,
        "ce": sum(t["side"] == "CE" for t in trades), "pe": sum(t["side"] == "PE" for t in trades),
    }
    out = [f"\n=== {name} ===",
           f"Trades {s['trades']}  (CE {s['ce']} / PE {s['pe']})  |  target hit {s['target_hits']} "
           f"({100 * s['target_hits'] / s['trades']:.0f}%)  |  win rate {s['win_rate']:.0f}%",
           f"Total P&L Rs {s['total']:,.0f}  |  avg/trade Rs {s['avg']:,.0f}  |  "
           f"avg win Rs {s['avg_win']:,.0f}  avg loss Rs {s['avg_loss']:,.0f}",
           f"Best Rs {s['best']:,.0f}  worst Rs {s['worst']:,.0f}  |  max drawdown Rs {s['max_dd']:,.0f}",
           "Month      P&L"]
    out += [f"{m}  {v:>10,.0f}" for m, v in sorted(by_month.items())]
    return "\n".join(out) + "\n", s


def main():
    p = argparse.ArgumentParser(description="Approximate backtest on SENSEX 1-minute candles")
    p.add_argument("--csv", help="use an already-downloaded sensex_1min_*.csv instead of downloading")
    p.add_argument("--months", type=int, default=12)
    p.add_argument("--delta", type=float, default=0.5, help="ATM option delta (premium pts per SENSEX pt)")
    p.add_argument("--target", type=float, default=15, help="premium points, 0 = no target (exit at minute end)")
    p.add_argument("--min-move", type=float, default=0, help="skip days with a smaller gap / first-minute move (SENSEX pts)")
    p.add_argument("--slippage", type=float, default=1.0, help="premium points lost per order (spread + market order)")
    p.add_argument("--charges", type=float, default=60, help="Rs per round trip (brokerage + taxes)")
    p.add_argument("--lots", type=int, default=1)
    a = p.parse_args()

    path = Path(a.csv) if a.csv else download(a.months)
    days = load(path)
    results = run(days, a)

    header = (f"SENSEX opening-move backtest  |  {min(days)} to {max(days)}  ({len(days)} days)\n"
              f"delta {a.delta}  target {a.target or 'none'} premium pts (= {a.target / a.delta if a.target else '-'} SENSEX pts)  "
              f"slippage {a.slippage}/order  charges Rs {a.charges}/trade  {a.lots} lot(s) x {LOT}\n"
              f"APPROXIMATE: index candles x delta, not real option prices. Entry at the candle open, not 10s later.")
    report = [header]
    for name, trades in results.items():
        report.append(summarise(name, trades)[0])
    text = "\n".join(report)
    print(text)

    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    (HERE / f"backtest_report_{stamp}.txt").write_text(text)
    with (HERE / f"backtest_trades_{stamp}.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["strategy", "date", "side", "signal", "sensex_pts", "premium_pts", "hit", "pnl"])
        w.writeheader()
        for name, trades in results.items():
            for t in trades:
                w.writerow({"strategy": name, **t})
    print(f"Saved backtest_report_{stamp}.txt and backtest_trades_{stamp}.csv")


if __name__ == "__main__":
    main()
