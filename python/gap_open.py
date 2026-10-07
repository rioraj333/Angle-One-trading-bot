"""
SENSEX opening-move strategy - one quick trade right after market open.

  1. From WATCH_START (09:15:01) read SENSEX every second (+ ATM CE / PE premiums).
  2. At ENTRY_TIME (09:15:10) decide the side:
       entry_rule "momentum" (default): SENSEX rising over those 10 seconds -> BUY ATM CE,
                                        falling -> BUY ATM PE.
       entry_rule "gap":                SENSEX above yesterday's close -> CE, below -> PE.
  3. Exit when the premium reaches entry + TARGET_POINTS, or at EXIT_TIME (09:16:00).
     target_points null = no target, ride the move until 09:16:00.

Every second is printed and saved to ticks_<date>.csv; each trade goes to trades.csv.

Run:  python gap_open.py        (start it any time before 09:15)
Settings + credentials live in config.json (copy config.example.json).

Testing (always PAPER, no real orders):
  python gap_open.py --simulate up     offline, fake prices: SENSEX rising   -> CE
  python gap_open.py --simulate down   offline, fake prices: SENSEX falling  -> PE
  python gap_open.py --now             live prices, any time in market hours: watch 10s, trade, exit 50s later
  python gap_open.py --now --side PE   same, but force PE (or CE) whatever the move is
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
    fmt = "%H:%M:%S" if hhmmss.count(":") == 2 else "%H:%M"
    t = datetime.strptime(hhmmss, fmt).time()
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


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return 0.0


class TickLog:
    """Appends one row per price read to ticks_<date>.csv so the first minute can be studied later."""

    FIELDS = ["time", "phase", "sensex", "move", "gap", "ce_strike", "ce_ltp", "pe_strike", "pe_ltp", "position_ltp"]

    def __init__(self, prefix):
        self.path = HERE / f"{prefix}_{now():%Y-%m-%d}.csv"
        self.new = not self.path.exists()

    def write(self, **row):
        with self.path.open("a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=self.FIELDS)
            if self.new:
                w.writeheader()
                self.new = False
            w.writerow({"time": f"{now():%H:%M:%S.%f}"[:12], **row})


# ─── broker ───────────────────────────────────────────────────────────────────

def login(cfg):
    api = SmartConnect(api_key=cfg["api_key"])
    totp = pyotp.TOTP(cfg["totp_secret"]).now()
    resp = api.generateSession(cfg["client_id"], cfg["mpin"], totp)
    if not resp or not resp.get("status"):
        sys.exit(f"Login failed: {resp}")
    log(f"Logged in as {cfg['client_id']}")
    return api


def quote_many(api, mode, exchange, tokens):
    """Returns {token: quote row} for one market-data call."""
    try:
        resp = api.getMarketData(mode, {exchange: list(tokens)})
        rows = (resp or {}).get("data", {}).get("fetched") or []
        return {str(r.get("symbolToken") or r.get("symboltoken")): r for r in rows}
    except Exception as e:
        log(f"Quote error: {e}")
        return {}


def sensex_quote(api):
    """Returns (ltp, prev_close, exchFeedTime) or None."""
    row = quote_many(api, "FULL", "BSE", [SENSEX_TOKEN]).get(SENSEX_TOKEN)
    if not row or num(row.get("ltp")) <= 0 or num(row.get("close")) <= 0:
        return None
    return num(row["ltp"]), num(row["close"]), str(row.get("exchFeedTime", ""))


def option_ltps(api, opts):
    """Returns {token: ltp} for options on the same exchange segment."""
    opts = [o for o in opts if o]
    if not opts:
        return {}
    rows = quote_many(api, "LTP", opts[0]["exch_seg"], [o["token"] for o in opts])
    return {t: num(r.get("ltp")) for t, r in rows.items() if num(r.get("ltp")) > 0}


def option_ltp(api, opt):
    return option_ltps(api, [opt]).get(opt["token"])


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
                    return num(o.get("averageprice")) or None
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
    for _ in range(5):
        order_id = place_market(api, cfg, opt, "SELL")
        if order_id:
            return wait_fill(api, order_id) or last_ltp
        time.sleep(1)
    log("!!! SELL FAILED 5 TIMES - SQUARE OFF MANUALLY IN THE ANGEL ONE APP !!!")
    return last_ltp


def save_trade(row):
    path = HERE / "trades.csv"
    if path.exists():
        with path.open() as f:
            header = f.readline().strip().split(",")
        if header != list(row.keys()):   # columns changed in a newer version - keep the old file aside
            path.rename(HERE / f"trades_old_{now():%Y%m%d_%H%M%S}.csv")
    new = not path.exists()
    with path.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(row.keys()))
        if new:
            w.writeheader()
        w.writerow(row)


# ─── offline simulator (--simulate) ───────────────────────────────────────────

class FakeApi:
    """Stands in for SmartConnect. SENSEX opens 150 pts above yesterday's close, then
    trends up ('up') or down ('down') 3 pts/s; ATM premiums move ~0.5 x SENSEX."""

    def __init__(self, scenario, start):
        self.dir = 1 if scenario == "up" else -1
        self.start = start

    def spot(self):
        t = (now() - self.start).total_seconds()
        return round(80150 + self.dir * 3 * t, 2)

    def getMarketData(self, mode, exchange_tokens):
        exch, tokens = next(iter(exchange_tokens.items()))
        if exch == "BSE":
            return {"status": True, "data": {"fetched": [{
                "symbolToken": SENSEX_TOKEN, "ltp": self.spot(), "close": 80000.0,
                "exchFeedTime": now().strftime("%d-%b-%Y %H:%M:%S")}]}}
        move = self.spot() - 80150
        rows = []
        for tok in tokens:
            premium = 200 + 0.5 * move if tok.endswith("CE") else 200 - 0.5 * move
            rows.append({"symbolToken": tok, "ltp": round(max(premium, 0.05), 2)})
        return {"status": True, "data": {"fetched": rows}}


def fake_options():
    expiry = now().date()
    return [{"symbol": f"SENSEX{k}{side}", "token": f"{k}{side}", "strike": k, "side": side,
             "expiry": expiry, "exch_seg": "BFO"}
            for k in range(79500, 80800, 100) for side in ("CE", "PE")]


def parse_args():
    p = argparse.ArgumentParser(description="SENSEX opening-move strategy")
    p.add_argument("--simulate", choices=["up", "down"], help="offline test with fake prices (no login)")
    p.add_argument("--now", action="store_true", help="paper test now: watch 10s, trade, exit 50s later")
    p.add_argument("--side", choices=["CE", "PE"], help="force CE or PE (test only)")
    return p.parse_args()


# ─── main ─────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    testing = bool(args.simulate or args.now or args.side)
    if args.simulate:
        cfg = {"mode": "PAPER", "lots": 1, "target_points": 15}
        try:
            cfg.update({k: v for k, v in load_config().items()
                        if k in ("lots", "target_points", "stop_loss_points", "entry_rule", "min_move_points")})
        except SystemExit:
            pass   # no config.json needed for the offline simulation
    else:
        cfg = load_config()
    if testing:
        cfg["mode"] = "PAPER"   # test runs never place real orders

    rule = cfg.get("entry_rule", "momentum").lower()
    if rule not in ("momentum", "gap"):
        sys.exit('entry_rule must be "momentum" or "gap"')
    target_pts = cfg.get("target_points", 15)       # null = no target, exit at exit_time
    sl_pts = cfg.get("stop_loss_points")            # null = no price stop
    min_move = num(cfg.get("min_move_points", cfg.get("min_gap_points", 0)))
    qty = cfg["lots"] * SENSEX_LOT_SIZE

    if args.simulate or args.now:
        watch_at = now().replace(microsecond=0) + timedelta(seconds=2)
        entry_at = watch_at + timedelta(seconds=10)
        exit_at = entry_at + timedelta(seconds=50)
    else:
        watch_at = at_today(cfg.get("watch_start", "09:15:01"))
        entry_at = at_today(cfg.get("entry_time", "09:15:10"))
        exit_at = at_today(cfg.get("exit_time", "09:16:00"))

    if not args.simulate and now().weekday() >= 5:
        sys.exit("Weekend - market closed.")
    if now() >= exit_at:
        sys.exit(f"Already past {exit_at:%H:%M:%S} - run it before market open tomorrow.")
    if testing:
        log("*** TEST RUN - PAPER only" + (f", simulated SENSEX {args.simulate}" if args.simulate else ", live prices")
            + (f", forced {args.side}" if args.side else "") + " ***")
    log(f"Mode {cfg['mode']} | rule {rule} | {cfg['lots']} lot(s) = {qty} qty | "
        f"target {('+' + str(target_pts)) if target_pts else 'none (ride to exit)'} | "
        f"SL {('-' + str(sl_pts)) if sl_pts else 'none'} | watch {watch_at:%H:%M:%S} | "
        f"entry {entry_at:%H:%M:%S} | exit {exit_at:%H:%M:%S}")

    if args.simulate:
        api, options = FakeApi(args.simulate, watch_at), fake_options()
        ticks = TickLog("ticks_sim")
    else:
        api = login(cfg)
        options = load_sensex_options()
        ticks = TickLog("ticks")

    log(f"Waiting for {watch_at:%H:%M:%S}...")
    sleep_until(watch_at)

    # 1. Watch SENSEX (+ ATM CE/PE) every second until entry time
    first = last = prev_close = None
    feed_time = ""
    watch_ce = watch_pe = None
    next_read = watch_at
    while now() < min(entry_at + timedelta(seconds=ENTRY_RETRY_SECONDS), exit_at):
        if now() >= entry_at and last is not None:
            break
        q = sensex_quote(api)
        if q:
            last, prev_close, feed_time = q
            if first is None:
                first = last
                watch_ce, watch_pe = pick_atm(options, first, "CE"), pick_atm(options, first, "PE")
            prem = option_ltps(api, [watch_ce, watch_pe])
            ce, pe = prem.get(watch_ce["token"]), prem.get(watch_pe["token"])
            log(f"SENSEX {last:.2f} | move {last - first:+.2f} | gap {last - prev_close:+.2f} | "
                f"CE {watch_ce['strike']} {ce or '-'} | PE {watch_pe['strike']} {pe or '-'}")
            ticks.write(phase="watch", sensex=last, move=round(last - first, 2), gap=round(last - prev_close, 2),
                        ce_strike=watch_ce["strike"], ce_ltp=ce, pe_strike=watch_pe["strike"], pe_ltp=pe)
        else:
            log("Could not read SENSEX price - retrying")
        next_read += timedelta(seconds=1)
        sleep_until(next_read)
    if last is None:
        sys.exit("No SENSEX price - no trade today.")

    try:
        feed_date = datetime.strptime(feed_time, "%d-%b-%Y %H:%M:%S").date()
    except ValueError:
        feed_date = None   # unknown format - don't block the trade on it
    if feed_date and feed_date != now().date():
        sys.exit(f"SENSEX last update was {feed_time} - market not open today (holiday?). No trade.")

    # 2. Decide side
    move, gap = last - first, last - prev_close
    signal = move if rule == "momentum" else gap
    log(f"Decision ({rule}): SENSEX {first:.2f} -> {last:.2f}, move {move:+.2f} | "
        f"yesterday close {prev_close:.2f}, gap {gap:+.2f}")
    if not args.side and (signal == 0 or abs(signal) < min_move):
        sys.exit(f"{rule} {signal:+.2f} smaller than minimum {min_move} - no trade today.")
    side = args.side or ("CE" if signal > 0 else "PE")

    # 3. Entry
    opt = pick_atm(options, last, side)
    log(f"{'UP' if signal > 0 else 'DOWN'}{' (side forced)' if args.side else ''} -> BUY {side} "
        f"{opt['strike']} ({opt['symbol']}, expiry {opt['expiry']})")
    entry_price = buy(api, cfg, opt)
    if not entry_price:
        sys.exit("Entry failed - no trade today. Check the broker app for any open position.")
    entry_time = now()
    target = entry_price + num(target_pts) if target_pts else None
    stop = entry_price - num(sl_pts) if sl_pts else None
    log(f"BOUGHT at {entry_price:.2f} | target {f'{target:.2f}' if target else 'none'}"
        + (f" | SL {stop:.2f}" if stop else "") + f" | time exit {exit_at:%H:%M:%S}")

    # 4. Watch until target / SL / exit time
    reason, last_ltp, last_print = f"Time exit {exit_at:%H:%M:%S}", entry_price, 0.0
    try:
        while now() < exit_at:
            ltp = option_ltp(api, opt)
            if ltp:
                last_ltp = ltp
                if time.time() - last_print >= 1:
                    last_print = time.time()
                    log(f"{side} {opt['strike']} {ltp:.2f} | {ltp - entry_price:+.2f} pts | "
                        f"P&L {(ltp - entry_price) * qty:+.2f}")
                    ticks.write(phase="trade", position_ltp=ltp)
                if target and ltp >= target:
                    reason = "Target hit"
                    break
                if stop is not None and ltp <= stop:
                    reason = "Stop loss hit"
                    break
            time.sleep(POLL_SECONDS)
    except KeyboardInterrupt:
        reason = "Manual stop (Ctrl+C)"

    log(f"{reason} - LTP {last_ltp:.2f}, selling")
    exit_price = sell(api, cfg, opt, last_ltp)
    pnl = (exit_price - entry_price) * qty
    log(f"SOLD at {exit_price:.2f} | P&L {pnl:+.2f} ({exit_price - entry_price:+.2f} pts x {qty})")

    save_trade({
        "date": now().date().isoformat(), "mode": "SIMULATED" if args.simulate else cfg["mode"],
        "rule": rule, "prev_close": prev_close, "sensex_first": first, "sensex_entry": last,
        "move": round(move, 2), "gap": round(gap, 2), "side": side, "strike": opt["strike"],
        "symbol": opt["symbol"], "qty": qty, "entry_time": f"{entry_time:%H:%M:%S}", "entry": entry_price,
        "exit_time": f"{now():%H:%M:%S}", "exit": exit_price, "reason": reason, "pnl": round(pnl, 2),
    })
    log(f"Saved to trades.csv (every second in {ticks.path.name}). Done for today.")


if __name__ == "__main__":
    main()
