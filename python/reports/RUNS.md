# Backtest runs (2026-10-07)

Data: `sensex_1min_20241017_20261007.csv`, downloaded by run 1 (SENSEX 1-minute candles, 2024-10-17 to 2026-10-06, 486 trading days).
All commands were run from `python/`. Runs 2-7 read the CSV from `reports/`, where it was moved after run 1.

| # | Command | Report | Trades |
|---|---------|--------|--------|
| 1 | `python backtest.py --months 24` (default `--target 15`) | `backtest_report_20261007_1209.txt` | `backtest_trades_20261007_1209.csv` |
| 2 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 20` | `backtest_report_20261007_1211.txt` | `backtest_trades_20261007_1211.csv` |
| 3 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 10` | `backtest_report_20261007_1212.txt` | `backtest_trades_20261007_1212.csv` |
| 4 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 0` | `backtest_report_20261007_1213.txt` | `backtest_trades_20261007_1213.csv` |
| 5 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 15 --min-move 30` | `backtest_report_20261007_1214.txt` | `backtest_trades_20261007_1214.csv` |
| 6 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 15 --min-move 60` | `backtest_report_20261007_1215.txt` | `backtest_trades_20261007_1215.csv` |
| 7 | `python backtest.py --csv reports/sensex_1min_20241017_20261007.csv --target 15 --delta 0.4` | `backtest_report_20261007_1216.txt` | `backtest_trades_20261007_1216.csv` |

Output filenames are stamped to the minute, so each run started in a new minute to avoid overwriting the previous run's files.
