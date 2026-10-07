# SENSEX Gap Open (simple Python script)

At **09:15:10**, compare SENSEX with yesterday's close:

- **Gap up** → BUY ATM **CE** (nearest expiry)
- **Gap down** → BUY ATM **PE** (nearest expiry)

Exit when the option premium is **+15 points** (target), or at **09:16:00**, whichever comes first.

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

## Settings (`config.json`)

| key | default | meaning |
|---|---|---|
| `mode` | `PAPER` | `PAPER` = no real orders, uses live prices. `LIVE` = real MARKET orders |
| `lots` | `1` | 1 lot = 20 qty |
| `target_points` | `15` | exit when premium is entry + this |
| `stop_loss_points` | `null` | optional price stop, e.g. `10`. `null` = time exit only |
| `min_gap_points` | `0` | skip the day if the gap is smaller than this |
| `entry_time` / `exit_time` | `09:15:10` / `09:16:00` | IST |

**Ctrl+C** while in a trade squares it off. If a LIVE sell fails 5 times the script prints a
warning: square off manually in the Angel One app.

Run it in **PAPER** for a few days and compare the log with the Angel One app before switching to `LIVE`.
