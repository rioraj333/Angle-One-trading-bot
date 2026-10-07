"""
SENSEX Gap Open strategy - one quick trade right after market open.

  1. At ENTRY_TIME (09:15:10) get SENSEX live price and yesterday's close.
  2. Gap up   (price > yesterday close) -> BUY ATM CE, nearest expiry.
     Gap down (price < yesterday close) -> BUY ATM PE, nearest expiry.
  3. Exit when the option premium reaches entry + TARGET_POINTS,
     or at EXIT_TIME (09:16:00) - whichever comes first.

Run:  python gap_open.py        (start it any time before 09:15)
Settings + credentials live in config.json (copy config.example.json).

Testing (always PAPER, no real orders):
  python gap_open.py --simulate up     offline, fake prices: gap up -> CE -> target hit
  python gap_open.py --simulate down   offline, fake prices: gap down -> PE -> time exit
  python gap_open.py --now             live prices, any time in market hours: enter in 10s, exit 50s later
  python gap_open.py --now --side PE   same, but force PE (or CE) whatever the gap is
"""

import argparse
import csv
import json
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pyotp
import requests
from SmartApi import SmartConnect

IST = ZoneInfo("Asia/Kolkata")
HERE = Path(__file__).parent
SCRIP_MASTER_URL = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
SENSEX_TOKEN = "99919000"   # SENSEX index on BSE
SENSEX_LOT_SIZE = 20
ENTRY_RETRY_SECONDS = 20    # keep retrying the entry this long if the broker API hiccups
POLL_SECONDS = 0.5          # how often the option price is checked while in the trade


# ─── helpers ──────────────────────────────────────────────────────────────────

def now():
    return datetime.now(IST)


def log(msg):
    print(f"[{now():%H:%M:%S}] {msg}", flush=True)


def at_today(hhmmss):
    t = datetime.strptime(hhmmss, "%H:%M:%S").time()
    return datetime.combine(now().date(), t, tzinfo=IST)


def sleep_until(when):
    while True:
        left = (when - now()).total_seconds()
        if left <= 0:
            return
        time.sleep(min(left, 30))


def load_config():
    path = HERE / "config.json"
    if not path.exists():
        sys.exit("config.json not found - copy config.example.json to config.json and fill it in.")
    cfg = json.loads(path.read_text())
    cfg["mode"] = cfg.get("mode", "PAPER").upper()
    if cfg["mode"] not in ("PAPER", "LIVE"):
        sys.exit('mode must be "PAPER" or "LIVE"')
    return cfg


# ─── broker ───────────────────────────────────────────────────────────────────

def login(cfg):
    api = SmartConnect(api_key=cfg["api_key"])
    totp = pyotp.TOTP(cfg["totp_secret"]).now()
    resp = api.generateSession(cfg["client_id"], cfg["mpin"], totp)
    if not resp or not resp.get("status"):
        sys.exit(f"Login failed: {resp}")
    log(f"Logged in as {cfg['client_id']}")
    return api


def quote(api, mode, exchange, token):
    """Returns the first 'fetched' row of a market-data quote, or None."""
    try:
        resp = api.getMarketData(mode, {exchange: [token]})
        rows = (resp or {}).get("data", {}).get("fetched") or []
        return rows[0] if rows else None
    except Exception as e:
        log(f"Quote error: {e}")
        return None


def option_ltp(api, opt):
    row = quote(api, "LTP", opt["exch_seg"], opt["token"])
    try:
        ltp = float(row["ltp"]) if row else 0
    except (KeyError, ValueError, TypeError):
        return None
    return ltp if ltp > 0 else None


def load_sensex_options():
    log("Downloading option list from Angel One (~30MB, takes a few seconds)...")
    rows = requests.get(SCRIP_MASTER_URL, timeout=120).json()
    today = now().date()
    options = []
    for r in rows:
        if r.get("name") != "SENSEX" or r.get("instrumenttype") != "OPTIDX":
            continue
        sym = r["symbol"]
        if not (sym.endswith("CE") or sym.endswith("PE")):
            continue
        try:
            expiry = datetime.strptime(r["expiry"].title(), "%d%b%Y").date()
            strike = round(float(r["strike"]) / 100)   # strike is in paise
        except (ValueError, KeyError):
            continue
        if expiry < today:
            continue
        options.append({"symbol": sym, "token": str(r["token"]), "strike": strike,
                        "side": sym[-2:], "expiry": expiry, "exch_seg": r["exch_seg"]})
    if not options:
        sys.exit("No SENSEX options found in the scrip master.")
    nearest = min(o["expiry"] for o in options)
    log(f"Loaded SENSEX options, nearest expiry {nearest}")
    return options


def pick_atm(options, spot, side):
    nearest = min(o["expiry"] for o in options)
    chain = [o for o in options if o["expiry"] == nearest and o["side"] == side]
    return min(chain, key=lambda o: abs(o["strike"] - spot))


def place_market(api, cfg, opt, side):
    """side = BUY / SELL. Returns order id or None."""
    params = {
        "variety": "NORMAL",
        "tradingsymbol": opt["symbol"],
        "symboltoken": opt["token"],
        "transactiontype": side,
        "exchange": opt["exch_seg"],
        "ordertype": "MARKET",
        "producttype": "INTRADAY",
        "duration": "DAY",
        "price": "0",
        "quantity": str(cfg["lots"] * SENSEX_LOT_SIZE),
    }
    try:
        order_id = api.placeOrder(params)
    except Exception as e:
        log(f"{side} order error: {e}")
        return None
    if order_id:
        log(f"{side} {opt['symbol']} x{params['quantity']} MARKET placed - order {order_id}")
    else:
        log(f"{side} {opt['symbol']} order FAILED")
    return order_id


def wait_fill(api, order_id, timeout=15):
    """Polls the order book until the order completes. Returns avg price, or None."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            for o in (api.orderBook() or {}).get("data") or []:
                if str(o.get("orderid")) != str(order_id):
                    continue
                status = str(o.get("status", "")).lower()
                if "complete" in status:
                    return float(o.get("averageprice") or 0) or None
                if "rejected" in status or "cancelled" in status:
                    log(f"Order {order_id} {status}: {o.get('text')}")
                    return None
        except Exception as e:
            log(f"Order book error: {e}")
        time.sleep(1)
    log(f"Order {order_id} not confirmed within {timeout}s - CHECK THE BROKER APP")
    return None


# ─── trade ────────────────────────────────────────────────────────────────────

def buy(api, cfg, opt):
    if cfg["mode"] == "PAPER":
        return option_ltp(api, opt)
    order_id = place_market(api, cfg, opt, "BUY")
    return wait_fill(api, order_id) if order_id else None


def sell(api, cfg, opt, last_ltp):
    if cfg["mode"] == "PAPER":
        return option_ltp(api, opt) or last_ltp
    for attempt in range(5):
        order_id = place_market(api, cfg, opt, "SELL")
        if order_id:
            return wait_fill(api, order_id) or last_ltp
        time.sleep(1)
    log("!!! SELL FAILED 5 TIMES - SQUARE OFF MANUALLY IN THE ANGEL ONE APP !!!")
    return last_ltp


def save_trade(row):
    path = HERE / "trades.csv"
    new = not path.exists()
    with path.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(row.keys()))
        if new:
            w.writeheader()
        w.writerow(row)


# ─── offline simulator (--simulate) ───────────────────────────────────────────

class FakeApi:
    """Stands in for SmartConnect: fixed SENSEX gap, scripted option premium."""

    def __init__(self, scenario):
        self.spot = 80150.0 if scenario == "up" else 79850.0
        # up: premium climbs 2 pts/s -> target hit. down: premium drifts around entry -> time exit.
        self.prices = ([200 + 2 * i for i in range(100)] if scenario == "up"
                       else [200 + (i % 7) - 3 for i in range(1000)])
        self.calls = 0

    def getMarketData(self, mode, exchange_tokens):
        if "BSE" in exchange_tokens:
            return {"status": True, "data": {"fetched": [{
                "ltp": self.spot, "close": 80000.0, "exchFeedTime": now().strftime("%d-%b-%Y %H:%M:%S")}]}}
        price = self.prices[min(self.calls // 2, len(self.prices) - 1)]   # 2 polls per second
        self.calls += 1
        return {"status": True, "data": {"fetched": [{"ltp": price}]}}


def fake_options():
    expiry = now().date()
    return [{"symbol": f"SENSEX{k}{side}", "token": f"{k}{side}", "strike": k, "side": side,
             "expiry": expiry, "exch_seg": "BFO"}
            for k in range(79500, 80600, 100) for side in ("CE", "PE")]


def parse_args():
    p = argparse.ArgumentParser(description="SENSEX Gap Open strategy")
    p.add_argument("--simulate", choices=["up", "down"], help="offline test with fake prices (no login)")
    p.add_argument("--now", action="store_true", help="paper test now: enter in 10s, exit 50s later")
    p.add_argument("--side", choices=["CE", "PE"], help="force CE or PE (test only)")
    return p.parse_args()


def main():
    args = parse_args()
    testing = bool(args.simulate or args.now or args.side)
    if args.simulate:
        cfg = {"mode": "PAPER", "lots": 1, "target_points": 15}
        try:
            cfg.update({k: v for k, v in load_config().items() if k in ("lots", "target_points", "stop_loss_points")})
        except SystemExit:
            pass   # no config.json needed for the offline simulation
    else:
        cfg = load_config()
    if testing:
        cfg["mode"] = "PAPER"   # test runs never place real orders
    target_pts = float(cfg.get("target_points", 15))
    sl_pts = cfg.get("stop_loss_points")          # null = no price stop, time exit only
    min_gap = float(cfg.get("min_gap_points", 0))
    qty = cfg["lots"] * SENSEX_LOT_SIZE
    if args.simulate or args.now:
        entry_at = now().replace(microsecond=0) + timedelta(seconds=10)
        exit_at = entry_at + timedelta(seconds=50)
    else:
        entry_at = at_today(cfg.get("entry_time", "09:15:10"))
        exit_at = at_today(cfg.get("exit_time", "09:16:00"))

    if not args.simulate and now().weekday() >= 5:
        sys.exit("Weekend - market closed.")
    if now() >= exit_at:
        sys.exit(f"Already past {exit_at:%H:%M:%S} - run it before market open tomorrow.")
    if testing:
        log("*** TEST RUN - PAPER only" + (f", simulated gap {args.simulate}" if args.simulate else ", live prices")
            + (f", forced {args.side}" if args.side else "") + " ***")

    log(f"Mode {cfg['mode']} | {cfg['lots']} lot(s) = {qty} qty | target +{target_pts} | "
        f"SL {('-' + str(sl_pts)) if sl_pts else 'none'} | entry {entry_at:%H:%M:%S} | exit {exit_at:%H:%M:%S}")

    if args.simulate:
        api, options = FakeApi(args.simulate), fake_options()
    else:
        api = login(cfg)
        options = load_sensex_options()

    log(f"Waiting for {entry_at:%H:%M:%S}...")
    sleep_until(entry_at)

    # 1. Gap
    row = None
    while now() < min(entry_at + timedelta(seconds=ENTRY_RETRY_SECONDS), exit_at):
        row = quote(api, "FULL", "BSE", SENSEX_TOKEN)
        if row and float(row.get("ltp") or 0) > 0 and float(row.get("close") or 0) > 0:
            break
        log("Could not read SENSEX price - retrying")
        time.sleep(1)
    else:
        sys.exit("No SENSEX price - no trade today.")

    feed_time = str(row.get("exchFeedTime", ""))
    try:
        feed_date = datetime.strptime(feed_time, "%d-%b-%Y %H:%M:%S").date()
    except ValueError:
        feed_date = None   # unknown format - don't block the trade on it
    if feed_date and feed_date != now().date():
        sys.exit(f"SENSEX last update was {feed_time} - market not open today (holiday?). No trade.")

    spot, prev_close = float(row["ltp"]), float(row["close"])
    gap = spot - prev_close
    log(f"SENSEX {spot:.2f} vs yesterday close {prev_close:.2f} -> gap {gap:+.2f} pts")
    if not args.side and (gap == 0 or abs(gap) < min_gap):
        sys.exit(f"Gap smaller than minimum {min_gap} - no trade today.")

    # 2. Entry
    side = args.side or ("CE" if gap > 0 else "PE")
    opt = pick_atm(options, spot, side)
    log(f"{'Gap UP' if gap > 0 else 'Gap DOWN'}{' (side forced)' if args.side else ''} -> BUY {side} {opt['strike']} ({opt['symbol']}, expiry {opt['expiry']})")
    entry_price = buy(api, cfg, opt)
    if not entry_price:
        sys.exit("Entry failed - no trade today. Check the broker app for any open position.")
    entry_time = now()
    target = entry_price + target_pts
    stop = entry_price - float(sl_pts) if sl_pts else None
    log(f"BOUGHT at {entry_price:.2f} | target {target:.2f}" + (f" | SL {stop:.2f}" if stop else "")
        + f" | time exit {exit_at:%H:%M:%S}")

    # 3. Watch until target / SL / exit time
    reason, last = "Time exit", entry_price
    try:
        while now() < exit_at:
            ltp = option_ltp(api, opt)
            if ltp:
                last = ltp
                if ltp >= target:
                    reason = "Target hit"
                    break
                if stop is not None and ltp <= stop:
                    reason = "Stop loss hit"
                    break
            time.sleep(POLL_SECONDS)
    except KeyboardInterrupt:
        reason = "Manual stop (Ctrl+C)"

    log(f"{reason} - LTP {last:.2f}, selling")
    exit_price = sell(api, cfg, opt, last)
    pnl = (exit_price - entry_price) * qty
    log(f"SOLD at {exit_price:.2f} | P&L {pnl:+.2f} ({exit_price - entry_price:+.2f} pts x {qty})")

    save_trade({
        "date": now().date().isoformat(), "mode": "SIMULATED" if args.simulate else cfg["mode"], "prev_close": prev_close, "spot": spot,
        "gap": round(gap, 2), "side": side, "strike": opt["strike"], "symbol": opt["symbol"],
        "qty": qty, "entry_time": f"{entry_time:%H:%M:%S}", "entry": entry_price,
        "exit_time": f"{now():%H:%M:%S}", "exit": exit_price, "reason": reason, "pnl": round(pnl, 2),
    })
    log("Saved to trades.csv. Done for today.")


if __name__ == "__main__":
    main()
