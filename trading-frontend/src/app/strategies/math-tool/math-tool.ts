import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { TradingService } from '../../services/trading.service';

// NIFTY 50 spot on NSE - same identifiers BreakoutStrategyEngine uses elsewhere.
const NIFTY_SPOT = { exchange: 'NSE', token: '99926000' };

interface OptionLegOhlc {
  symbol?: string;
  open?: number;
  high?: number;
  low?: number;
  close?: number;
}

/**
 * Placeholder shell for the new maths-based strategy - mirrors how 9:25 Breakout
 * started out (UI only, no trading logic yet) while its rules get built out
 * step by step in the backend.
 *
 * Step 1: "Fetch OHLC (Previous Day)" button calls MarketService.getPreviousDayOhlc
 *         and maps the result into the four input fields below (still editable by hand).
 * Step 2: strike selection - previous close rounded DOWN to the nearest 100 picks the
 *         CE/PE strike, e.g. close 23744 -> the 23700-23799 band -> strike 23700.
 * Step 3: "Fetch 5-Min Candle OHLC" button resolves the CE/PE contracts at that strike
 *         (nearest expiry) and pulls each leg's first 5-minute candle (09:15-09:20) -
 *         its Close is the "fixed" reference premium later steps will read from.
 */
@Component({
  selector: 'app-math-tool',
  standalone: true,
  imports: [RouterLink, FormsModule],
  templateUrl: './math-tool.html',
  styleUrl: './math-tool.scss',
})
export class MathToolComponent {
  open: number | null = null;
  high: number | null = null;
  low: number | null = null;
  close: number | null = null;

  loading = signal(false);
  error = signal('');
  fetchedDate = signal('');

  legsLoading = signal(false);
  legsError = signal('');
  ceLeg = signal<OptionLegOhlc | null>(null);
  peLeg = signal<OptionLegOhlc | null>(null);

  constructor(private tradingService: TradingService) {}

  fetchPreviousDayOhlc(): void {
    this.loading.set(true);
    this.error.set('');
    this.tradingService.getPreviousDayOhlc(NIFTY_SPOT.exchange, NIFTY_SPOT.token).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res?.status) {
          this.open = res.open;
          this.high = res.high;
          this.low = res.low;
          this.close = res.close;
          // Broker returns a full ISO timestamp (e.g. "2026-09-05T00:00:00+05:30") -
          // only the date part is meaningful for a daily candle.
          this.fetchedDate.set(String(res.date ?? '').split('T')[0]);
        } else {
          this.error.set(res?.message ?? 'Could not fetch previous-day OHLC.');
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Could not fetch previous-day OHLC.');
      },
    });
  }

  /** Previous close rounded down to the nearest 100 - the shared CE/PE strike. */
  selectedStrike(): number | null {
    if (this.close == null || Number.isNaN(this.close)) return null;
    return Math.floor(this.close / 100) * 100;
  }

  fetchOptionLegCandles(): void {
    const strike = this.selectedStrike();
    if (strike == null) return;

    this.legsLoading.set(true);
    this.legsError.set('');
    this.tradingService.getOptionFirstCandle('NIFTY', strike).subscribe({
      next: (res) => {
        this.legsLoading.set(false);
        if (res?.status) {
          this.ceLeg.set(res.ce ?? null);
          this.peLeg.set(res.pe ?? null);
        } else {
          this.ceLeg.set(null);
          this.peLeg.set(null);
          this.legsError.set(res?.message ?? 'Could not fetch option leg candles.');
        }
      },
      error: (err) => {
        this.legsLoading.set(false);
        this.ceLeg.set(null);
        this.peLeg.set(null);
        this.legsError.set(err?.error?.message ?? 'Could not fetch option leg candles.');
      },
    });
  }
}
