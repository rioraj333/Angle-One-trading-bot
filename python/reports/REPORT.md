# SENSEX opening-move strategy – backtest report

**Data:** SENSEX 1-minute candles from Angel One, 17 Oct 2024 to 6 Oct 2026, 479 tradable days.
**Assumptions:** 1 lot (20 qty). Option premium is taken as SENSEX points × delta 0.5, so 15 premium points ≈ 30 SENSEX points. Costs are 1 premium point of slippage per order plus ₹60 charges per trade, which comes to about **₹100 per trade**.

> This is an approximation. Angel One has no second-level history and no prices for expired options, so the test uses SENSEX index candles, not real option prices. Entry is at the candle open, not 10 seconds after it.

## Headline: every version lost money

| Version | Trades | Win rate | Total P&L | Avg / trade |
|---|---|---|---|---|
| **Gap rule**, target 15 (the original idea) | 479 | 69% | **−₹2,10,937** | −₹440 |
| **Momentum** (1 min later), target 15 | 479 | 59% | **−₹63,628** | −₹133 |
| Momentum, target 10 | 479 | 68% | −₹65,993 | −₹138 |
| Momentum, target 20 | 479 | 53% | −₹57,395 | −₹120 |
| Momentum, no target (hold the minute) | 479 | 44% | −₹46,927 | −₹98 |
| Momentum, target 15, skip moves < 30 pts | 395 | 59% | −₹48,570 | −₹123 |
| Momentum, target 15, skip moves < 60 pts | 323 | 58% | −₹45,349 | −₹140 |
| Gap rule, target 10 | 479 | 74% | −₹1,99,923 | −₹417 |
| Gap rule, target 20 | 479 | 66% | −₹2,02,631 | −₹423 |
| Gap rule, no target | 479 | 42% | −₹1,54,504 | −₹323 |
| Gap rule, delta 0.4 | 479 | 67% | −₹1,72,502 | −₹360 |

None of the 25 months was clearly profitable for either rule. The best months were roughly break-even.

## Why

1. **The gap rule wins often but loses big.** It hits +15 on 69% of days, but the average loss (₹1,886) is about 9× the average win (₹200). After a gap, the first minute moves *against* the gap on 55% of days, because part of the gap gets filled. On big-gap days that reversal is hundreds of points. Worst day: 2 March 2026, gap −2,773, first minute +1,650.
2. **Momentum has almost no edge, and costs eat it.** Holding the minute with no target loses ₹46,927 over 479 trades. Costs alone are about ₹100 × 479 = **₹47,900**. Before costs the direction call was right about as often as a coin flip, so the result is roughly zero.
3. **The first minute is very noisy.** The median 9:15 candle range is 200 SENSEX points; the 9:16 candle's is 75. A 15-point premium target (30 index points) gets touched on most days in both directions, so hitting the target says little about whether the direction was right.

## Extra checks

| Check | Total P&L |
|---|---|
| Gap rule with loss capped at 150 premium pts (real options can't fall below 0) | −₹1,72,174 |
| **Fade** the gap (buy PE on a gap up) | −₹99,269 (78% win rate) |
| Fade the gap, only gaps > 200 pts | −₹56,979 |
| Fade momentum | −₹66,030 |

Removing the few extreme days or capping losses doesn't turn the gap rule profitable. Fading also loses. There's no simple directional edge in the first minute that survives about ₹100 of costs per trade.

## What this means

- **Don't trade the gap rule live.** It's the clearest loser.
- **The 10-second momentum rule itself is untested.** 1-minute data can't show it, and the closest proxy (one minute later) is roughly break-even before costs and negative after them. Only the `ticks_*.csv` files `gap_open.py` records live can answer it.
- **Costs matter.** At about 15 premium points per winning trade, ₹100 per trade is about a third of each win. Any version that might work needs a larger average move per trade, or far fewer trades.

## Suggested next steps

1. Keep running `gap_open.py` in **PAPER** mode for 3–4 weeks to collect real 10-second tick data. It costs nothing and is the only way to test the actual rule.
2. Then re-test on that data. Things worth checking: whether the 10-second move predicts the next 50 seconds, and whether only very strong moves (e.g. > 40 pts in 10 s) are worth trading.
3. Ideas worth backtesting on 1-minute data, since they trade later when the market is calmer and moves are cleaner: an opening-range breakout after 9:20, or a trend after the first 5 minutes.

## Files

- `RUNS.md` – which command produced which report
- `backtest_report_*.txt` – full reports incl. month-by-month P&L
- `backtest_trades_*.csv` – every simulated trade (open in Excel)
- `sensex_1min_20241017_20261007.csv` – the downloaded data
