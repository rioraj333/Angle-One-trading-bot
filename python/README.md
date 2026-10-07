# SENSEX opening-move strategy (simple Python script)

Default rule (`entry_rule: "premium"`):

1. **09:15:10** - note the premiums of the ATM **CE** and ATM **PE** (nearest expiry).
2. **09:15:10 to 09:15:20** - watch both premiums every second.
3. **09:15:20** - only the CE rose -> BUY that **CE**; only the PE rose -> BUY that **PE**.
   Both rose, both fell or no change -> **no trade today**.
4. **09:16:00** - exit (no target, `target_points: null`).

Every second is printed and saved to `ticks_<date>.csv`; each trade goes to `trades.csv`.
The older `momentum` and `gap` rules (side from the SENSEX move) are still available, see Settings.

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

It logs in, downloads the option list, watches 09:15:10-09:15:20, trades (or skips), and adds a row to `trades.csv`.

## Testing (always PAPER, never places real orders)

**1. Offline simulation - works any time, even at night / weekends, no login needed**

```bash
python gap_open.py --simulate up     # SENSEX rising for 10s  -> CE premium rises -> buys CE
python gap_open.py --simulate down   # SENSEX falling for 10s -> PE premium rises -> buys PE
```

Uses fake prices - checks the logic only (which side, target, trades.csv). Writes `ticks_sim_<date>.csv`.

**2. Live paper test - during market hours (09:15-15:30), real prices from Angel One**

```bash
python gap_open.py --now             # side from the real gap right now
python gap_open.py --now --side CE   # force CE
python gap_open.py --now --side PE   # force PE
```

Logs in, watches for 10 seconds, buys, and exits 40 seconds later (50 for the momentum / gap rules), or at the target if one is set.
`--side` lets you test both CE and PE on the same day.

## Settings (`config.json`)

| key | default | meaning |
|---|---|---|
| `mode` | `PAPER` | `PAPER` = no real orders, uses live prices. `LIVE` = real MARKET orders |
| `lots` | `1` | 1 lot = 20 qty |
| `entry_rule` | `premium` | `premium` = buy whichever of ATM CE / PE alone rose from `watch_start` to `entry_time`, skip if both or neither rose. `momentum` = side from the SENSEX move. `gap` = side from SENSEX vs yesterday's close |
| `target_points` | `null` | exit when premium is entry + this. `null` = no target, ride the move until `exit_time` |
| `stop_loss_points` | `null` | optional price stop, e.g. `10`. `null` = time exit only |
| `min_move_points` | `0` | skip the day if the 10-second move (or gap) is smaller than this. For `premium` it is in premium points (the rising option's change) |
| `watch_start` / `entry_time` / `exit_time` | `09:15:10` / `09:15:20` / `09:16:00` | IST. If not set: `premium` uses 09:15:10 / 09:15:20, `momentum` / `gap` use 09:15:01 / 09:15:10 |

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

Neither the `premium` rule nor the real 10-second momentum rule can be backtested; they can only be measured from the `ticks_*.csv` files `gap_open.py` records each day.
