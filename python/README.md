# SENSEX opening-move strategy (simple Python script)

From **09:15:01** the script reads SENSEX every second (plus the ATM CE and PE premiums).
At **09:15:10** it decides the side:

- SENSEX **rising** over those 10 seconds → BUY ATM **CE** (nearest expiry)
- SENSEX **falling** → BUY ATM **PE** (nearest expiry)

Exit when the option premium is **+15 points** (target), or at **09:16:00**, whichever comes first.
Every second is printed and saved to `ticks_<date>.csv`; each trade goes to `trades.csv`.

## One-time setup

```bash
cd python
pip install -r requirements.txt
copy config.example.json config.json      # Windows  (Mac/Linux: cp)
```

Edit `config.json` with your Angel One SmartAPI key, client ID, MPIN and TOTP secret
(the same values as the backend's `application-local.properties`). `config.json` is gitignored.

## Every trading day

Start it any time before 09:15, and leave the window open until it says "Done for today":

```bash
python gap_open.py
```

It logs in, downloads the option list, waits until 09:15:10, trades, and adds a row to `trades.csv`.

## Testing (always PAPER, never places real orders)

**1. Offline simulation - works any time, even at night / weekends, no login needed**

```bash
python gap_open.py --simulate up     # SENSEX rising for 10s  -> buys CE -> Target hit
python gap_open.py --simulate down   # SENSEX falling for 10s -> buys PE -> Target hit
```

Uses fake prices - checks the logic only (which side, target, trades.csv). Writes `ticks_sim_<date>.csv`.

**2. Live paper test - during market hours (09:15-15:30), real prices from Angel One**

```bash
python gap_open.py --now             # side from the real gap right now
python gap_open.py --now --side CE   # force CE
python gap_open.py --now --side PE   # force PE
```

Logs in, watches SENSEX for 10 seconds, buys, and exits 50 seconds later (or at +15).
`--side` lets you test both CE and PE on the same day.

## Settings (`config.json`)

| key | default | meaning |
|---|---|---|
| `mode` | `PAPER` | `PAPER` = no real orders, uses live prices. `LIVE` = real MARKET orders |
| `lots` | `1` | 1 lot = 20 qty |
| `entry_rule` | `momentum` | `momentum` = side from the SENSEX move 09:15:01 to 09:15:10. `gap` = side from SENSEX vs yesterday's close |
| `target_points` | `15` | exit when premium is entry + this. `null` = no target, ride the move until `exit_time` |
| `stop_loss_points` | `null` | optional price stop, e.g. `10`. `null` = time exit only |
| `min_move_points` | `0` | skip the day if the 10-second move (or gap) is smaller than this |
| `watch_start` / `entry_time` / `exit_time` | `09:15:01` / `09:15:10` / `09:16:00` | IST |

**Ctrl+C** while in a trade squares it off. If a LIVE sell fails 5 times the script prints a
warning: square off manually in the Angel One app.

Run it in **PAPER** for a few days and compare the log with the Angel One app before switching to `LIVE`.

## Backtest (approximate)

```bash
python backtest.py                    # downloads last 12 months of SENSEX 1-minute candles, prints a report
python backtest.py --months 24 --target 20 --min-move 30
python backtest.py --csv sensex_1min_XXXX.csv --target 0     # re-run on saved data, no target
```

Uses your `config.json` login. Saves `sensex_1min_*.csv` (data), `backtest_report_*.txt` and `backtest_trades_*.csv`.

It is an **approximation**: Angel One history is 1-minute candles only and expired options can't be
downloaded, so it uses SENSEX points x delta (default 0.5) as the option premium, plus slippage and charges.

- **GAP rule**: exact signal (9:15 open vs yesterday's close), first minute as the trade.
- **MOMENTUM (1 min later)**: the 10-second move can't be seen in 1-minute data, so this tests the same
  idea one minute later (9:15 candle direction -> trade the 9:16 minute).

The real 10-second momentum rule can only be measured from the `ticks_*.csv` files `gap_open.py` records each day.
