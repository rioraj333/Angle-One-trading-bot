import { Component, OnDestroy, OnInit, PLATFORM_ID, inject, signal, computed } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval, of, Subscription } from 'rxjs';
import { catchError, startWith, switchMap } from 'rxjs/operators';
import { TradingService } from '../../services/trading.service';
import { Breakout925TradeDto, Breakout925TradeService } from '../../services/breakout925-trade.service';
import { Breakout925LegPick, Breakout925Service, Breakout925StartRequest, Breakout925State } from '../../services/breakout925.service';
import { Breakout925Preset, Breakout925PresetService } from '../../services/breakout925-preset.service';

type IndexName = 'NIFTY' | 'SENSEX';

const INDEX_META: Record<IndexName, { exchange: string; symbol: string; token: string }> = {
  NIFTY: { exchange: 'NSE', symbol: 'Nifty 50', token: '99926000' },
  SENSEX: { exchange: 'BSE', symbol: 'SENSEX', token: '99919000' },
};

const DEFAULT_PREMIUM_RANGE: Record<IndexName, { from: number; to: number }> = {
  NIFTY: { from: 100, to: 200 },
  SENSEX: { from: 200, to: 400 },
};

export interface PremiumMatch {
  strike: number;
  premium: number;
  symbol: string;
  token: string;
}

const OPEN_LEG_STATUSES = new Set(['ENTRY_PLACED', 'ENTRY_CONFIRMED', 'BRACKET_PLACED']);
const ORDER_EVENT_TYPES = new Set([
  'ORDER_PLACED', 'ORDER_FAILED', 'BRACKET_PLACED', 'BRACKET_ARMED',
  'ENTRY_CONFIRMED', 'ENTRY_FAILED', 'CANCEL_FAILED', 'MODIFIED',
]);

/**
 * Thin display layer over the server-side Breakout925StrategyEngine - all monitoring
 * (tick polling, breakout/target/SL detection, bracket-order placement/OCO, live order
 * firing) runs in the backend now, so it survives this component being destroyed
 * (navigation away, tab close). This component just: lets you search/select strikes,
 * calls start()/stop()/modify() on the engine, and polls getState() to render it.
 */
@Component({
  selector: 'app-breakout925',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './breakout925.html',
  styleUrl: './breakout925.scss',
})
export class Breakout925Component implements OnInit, OnDestroy {
  quantity = 1;
  mode: 'PAPER' | 'LIVE' = 'PAPER';
  quantityOptions = [1, 2, 3, 5, 10];
  targetPoints = 15;
  newPresetName = '';
  newPresetSelectionMode = signal<'MANUAL' | 'AUTO'>('MANUAL');
  presets = signal<Breakout925Preset[]>([]);

  errorMsg = signal('');

  selectedIndex = signal<IndexName>('NIFTY');
  indexLtp = signal<number | null>(null);

  candleFromTime = '09:20';
  candleToTime = '09:25';

  premiumFrom: number | null = DEFAULT_PREMIUM_RANGE.NIFTY.from;
  premiumTo: number | null = DEFAULT_PREMIUM_RANGE.NIFTY.to;
  premiumSearching = signal(false);
  premiumSearchError = signal('');
  ceMatches = signal<PremiumMatch[]>([]);
  peMatches = signal<PremiumMatch[]>([]);
  resultsExchSeg = signal<string>('NFO');
  searchedFrom = signal<number | null>(null);
  searchedTo = signal<number | null>(null);

  settingsOpen = signal(true);

  selectedCe = signal<PremiumMatch | null>(null);
  selectedPe = signal<PremiumMatch | null>(null);

  deployedFromPresetId: number | null = null;

  runState = signal<Breakout925State | null>(null);
  running = computed(() => this.runState()?.active === true);
  deployedPreset = computed(() => {
    const id = this.runState()?.presetId;
    return id != null ? this.presets().find((p) => p.id === id) ?? null : null;
  });
  ceLeg = computed(() => this.runState()?.ce ?? null);
  peLeg = computed(() => this.runState()?.pe ?? null);
  cePositionOpen = computed(() => OPEN_LEG_STATUSES.has(this.ceLeg()?.legStatus ?? ''));
  pePositionOpen = computed(() => OPEN_LEG_STATUSES.has(this.peLeg()?.legStatus ?? ''));
  exitInfo = computed(() => {
    const s = this.runState();
    if (!s?.lastExitSide) return null;
    return { side: s.lastExitSide, reason: s.lastExitReason ?? '', price: s.lastExitPrice ?? 0 };
  });
  latestOrderMsg = computed(() => {
    const events = this.runState()?.events ?? [];
    const relevant = events.filter((e) => ORDER_EVENT_TYPES.has(e.type));
    return relevant.length ? relevant[relevant.length - 1].message : '';
  });

  modifyOpen = signal<'CE' | 'PE' | null>(null);
  modifyTargetInput: number | null = null;
  modifySlInput: number | null = null;
  modifyError = signal('');

  tradeHistory = signal<Breakout925TradeDto[]>([]);
  historyOpen = signal(false);
  historyLoading = signal(false);
  historyMode = signal<'PAPER' | 'LIVE'>('PAPER');

  viewingTradeId = signal<number | null>(null);
  viewingEvents = signal<{ time: string; type: string; message: string }[]>([]);
  viewingEventsLoading = signal(false);
  viewingEventsError = signal('');

  private indexPollSub?: Subscription;
  private stateSub?: Subscription;
  private platformId = inject(PLATFORM_ID);

  constructor(
    private tradingService: TradingService,
    private tradeHistoryService: Breakout925TradeService,
    private breakout925Service: Breakout925Service,
    private breakout925PresetService: Breakout925PresetService,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.breakout925PresetService.list().subscribe({
      next: (list) => {
        this.presets.set(list);
        const presetId = this.route.snapshot.queryParamMap.get('presetId');
        if (presetId) this.applyPresetById(Number(presetId), true);
      },
      error: () => {},
    });

    this.indexPollSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => {
          const meta = INDEX_META[this.selectedIndex()];
          return this.tradingService.getLTP(meta.exchange, meta.symbol, meta.token);
        })
      )
      .subscribe({
        next: (r) => {
          if (r?.status && r.data?.ltp != null) this.indexLtp.set(Number(r.data.ltp));
        },
        error: () => {},
      });

    // Hydrates immediately on mount (covers navigating to the dashboard and back mid-run)
    // rather than waiting for the first 1s poll tick, then keeps polling for live updates.
    this.stateSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => this.breakout925Service.getState().pipe(catchError(() => of(null)))),
      )
      .subscribe((state) => {
        if (!state) return;
        this.runState.set(state);
        // Landing on this page (e.g. via the Dashboard's "View" button) starts with
        // whatever local defaults happen to be sitting in these fields - sync them from
        // the server's real running config so Settings always reflects the actual run,
        // not stale/default values unrelated to it.
        if (state.active) {
          if (state.indexName) this.selectedIndex.set(state.indexName as IndexName);
          if (state.candleFromTime) this.candleFromTime = state.candleFromTime;
          if (state.candleToTime) this.candleToTime = state.candleToTime;
          if (state.quantity != null) this.quantity = state.quantity;
          if (state.targetPoints != null) this.targetPoints = state.targetPoints;
          if (state.mode) this.mode = state.mode;
        }
      });
  }

  ngOnDestroy(): void {
    this.indexPollSub?.unsubscribe();
    this.stateSub?.unsubscribe();
  }

  toggleSettings(): void {
    this.settingsOpen.set(!this.settingsOpen());
  }

  onIndexChange(value: string): void {
    this.indexLtp.set(null);
    const index = value as IndexName;
    this.selectedIndex.set(index);
    const range = DEFAULT_PREMIUM_RANGE[index];
    this.premiumFrom = range.from;
    this.premiumTo = range.to;
  }

  searchPremium(): void {
    this.premiumSearchError.set('');
    if (this.premiumFrom == null || this.premiumTo == null) {
      this.premiumSearchError.set('Enter both a from and to premium value.');
      return;
    }
    this.premiumSearching.set(true);
    this.selectedCe.set(null);
    this.selectedPe.set(null);
    this.tradingService.searchPremium(this.selectedIndex(), this.premiumFrom, this.premiumTo).subscribe({
      next: (r) => {
        this.premiumSearching.set(false);
        if (!r?.status) {
          this.premiumSearchError.set(r?.message || 'Search failed.');
          this.ceMatches.set([]);
          this.peMatches.set([]);
          return;
        }
        this.ceMatches.set((r.ce || []).slice().sort((a: PremiumMatch, b: PremiumMatch) => b.premium - a.premium));
        this.peMatches.set((r.pe || []).slice().sort((a: PremiumMatch, b: PremiumMatch) => b.premium - a.premium));
        this.resultsExchSeg.set(r.exchSeg || 'NFO');
        this.searchedFrom.set(this.premiumFrom);
        this.searchedTo.set(this.premiumTo);
      },
      error: (err) => {
        this.premiumSearching.set(false);
        this.premiumSearchError.set(err.error?.message || 'Search failed.');
      },
    });
  }

  selectCe(m: PremiumMatch): void {
    const isDeselect = this.selectedCe()?.strike === m.strike;
    this.selectedCe.set(isDeselect ? null : m);
  }

  selectPe(m: PremiumMatch): void {
    const isDeselect = this.selectedPe()?.strike === m.strike;
    this.selectedPe.set(isDeselect ? null : m);
  }

  startStrategy(): void {
    this.errorMsg.set('');
    const ce = this.selectedCe();
    const pe = this.selectedPe();
    if (!ce && !pe) {
      this.errorMsg.set('Select at least one of CE or PE.');
      return;
    }
    if (!this.targetPoints || this.targetPoints <= 0) {
      this.errorMsg.set('Target (points) must be greater than 0.');
      return;
    }

    const toPick = (m: PremiumMatch | null): Breakout925LegPick | null =>
      m ? { strike: m.strike, symbol: m.symbol, token: m.token } : null;

    const request: Breakout925StartRequest = {
      indexName: this.selectedIndex(),
      exchSeg: this.resultsExchSeg(),
      candleFromTime: this.candleFromTime,
      candleToTime: this.candleToTime,
      quantity: this.quantity,
      targetPoints: this.targetPoints,
      mode: this.mode,
      ce: toPick(ce),
      pe: toPick(pe),
      presetId: this.deployedFromPresetId,
    };

    this.breakout925Service.start(request).subscribe({
      next: (state) => {
        if (state.error) {
          this.errorMsg.set(state.error);
        } else {
          this.runState.set(state);
        }
      },
      error: (err) => this.errorMsg.set(err.error?.message || 'Failed to start strategy.'),
    });
  }

  stopStrategy(): void {
    this.breakout925Service.stop(false).subscribe({
      next: (state) => {
        if (state.error) {
          if (confirm('A position is open. Square it off and stop?')) {
            this.breakout925Service.stop(true).subscribe({
              next: (s2) => this.runState.set(s2),
              error: (err) => this.errorMsg.set(err.error?.message || 'Failed to stop strategy.'),
            });
          }
        } else {
          this.runState.set(state);
        }
      },
      error: (err) => this.errorMsg.set(err.error?.message || 'Failed to stop strategy.'),
    });
  }

  openModify(side: 'CE' | 'PE'): void {
    const leg = side === 'CE' ? this.ceLeg() : this.peLeg();
    this.modifyTargetInput = leg?.target ?? null;
    this.modifySlInput = leg?.stopLoss ?? null;
    this.modifyError.set('');
    this.modifyOpen.set(side);
  }

  cancelModify(): void {
    this.modifyOpen.set(null);
  }

  saveModify(): void {
    const side = this.modifyOpen();
    if (!side) return;
    this.modifyError.set('');
    this.breakout925Service.modify(side, this.modifyTargetInput ?? undefined, this.modifySlInput ?? undefined).subscribe({
      next: (state) => {
        if (state.error) {
          this.modifyError.set(state.error);
        } else {
          this.runState.set(state);
          this.modifyOpen.set(null);
        }
      },
      error: (err) => this.modifyError.set(err.error?.message || 'Modify failed.'),
    });
  }

  toggleHistory(): void {
    const next = !this.historyOpen();
    this.historyOpen.set(next);
    if (next) this.loadHistory();
  }

  loadHistory(): void {
    this.historyLoading.set(true);
    this.tradeHistoryService.list(this.historyMode()).subscribe({
      next: (trades) => {
        this.historyLoading.set(false);
        this.tradeHistory.set(trades);
      },
      error: () => {
        this.historyLoading.set(false);
      },
    });
  }

  setHistoryMode(mode: 'PAPER' | 'LIVE'): void {
    if (this.historyMode() === mode) return;
    this.historyMode.set(mode);
    this.loadHistory();
  }

  deleteTrade(id: number): void {
    if (!confirm('Delete this trade record? This cannot be undone.')) return;
    this.tradeHistoryService.delete(id).subscribe({
      next: () => this.tradeHistory.set(this.tradeHistory().filter((t) => t.id !== id)),
      error: () => {},
    });
  }

  viewTradeLog(trade: Breakout925TradeDto): void {
    this.viewingTradeId.set(trade.id);
    this.viewingEvents.set([]);
    this.viewingEventsError.set('');

    if (!trade.runId) {
      this.viewingEventsError.set('No log available for this trade (recorded before logging was added).');
      return;
    }

    this.viewingEventsLoading.set(true);
    this.breakout925Service.getRunEvents(trade.runId).subscribe({
      next: (events) => {
        this.viewingEventsLoading.set(false);
        this.viewingEvents.set(events);
      },
      error: () => {
        this.viewingEventsLoading.set(false);
        this.viewingEventsError.set('Failed to load log.');
      },
    });
  }

  closeTradeLog(): void {
    this.viewingTradeId.set(null);
  }

  applyPreset(idStr: string): void {
    if (!idStr) return;
    this.applyPresetById(Number(idStr), false);
  }

  private applyPresetById(id: number, autoSearch: boolean): void {
    const preset = this.presets().find((p) => p.id === id);
    if (!preset) return;
    this.deployedFromPresetId = id;
    this.selectedIndex.set(preset.indexName as IndexName);
    this.premiumFrom = preset.premiumFrom;
    this.premiumTo = preset.premiumTo;
    this.candleFromTime = preset.candleFromTime;
    this.candleToTime = preset.candleToTime;
    this.quantity = preset.quantity;
    this.targetPoints = preset.targetPoints;
    this.mode = preset.mode;
    if (autoSearch) this.searchPremium();
  }

  saveCurrentAsPreset(): void {
    this.errorMsg.set('');
    if (!this.newPresetName.trim()) return;
    if (this.premiumFrom == null || this.premiumTo == null) {
      this.errorMsg.set('Search a premium range before saving a preset.');
      return;
    }
    this.breakout925PresetService
      .save({
        name: this.newPresetName.trim(),
        selectionMode: this.newPresetSelectionMode(),
        indexName: this.selectedIndex(),
        premiumFrom: this.premiumFrom,
        premiumTo: this.premiumTo,
        candleFromTime: this.candleFromTime,
        candleToTime: this.candleToTime,
        quantity: this.quantity,
        targetPoints: this.targetPoints,
        mode: this.mode,
      })
      .subscribe({
        next: (preset) => {
          this.presets.set([preset, ...this.presets()]);
          this.newPresetName = '';
        },
        error: (err) => this.errorMsg.set(err.error?.message || 'Failed to save preset.'),
      });
  }

  fmt(n: number | undefined | null): string {
    return n === undefined || n === null ? '—' : n.toFixed(2);
  }

  /** Java's LocalDateTime.toString() can carry up to 9 fractional digits; JS Date only reliably parses 3. */
  fmtEventTime(iso: string): string {
    const trimmed = iso.replace(/(\.\d{3})\d*$/, '$1');
    const d = new Date(trimmed);
    return isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-IN', { hour12: false });
  }

  fmtPnl(n: number | undefined | null): string {
    if (n === undefined || n === null) return '—';
    return (n >= 0 ? '+₹' : '-₹') + Math.abs(n).toFixed(2);
  }

  legStatusLabel(status: string | undefined): string {
    switch (status) {
      case 'NONE': return 'Not used';
      case 'WATCHING': return 'Watching for breakout';
      case 'ENTRY_PLACED': return 'Entry order placed…';
      case 'ENTRY_CONFIRMED': return 'Entered — arming bracket in 60s';
      case 'BRACKET_PLACED': return 'Target/SL live';
      case 'CLOSED': return 'Closed';
      case 'ENTRY_FAILED': return 'Entry failed — check broker';
      case 'SKIPPED': return 'Skipped — other leg already hit target';
      default: return status || '—';
    }
  }
}
