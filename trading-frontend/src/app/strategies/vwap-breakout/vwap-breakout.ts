import { Component, OnDestroy, OnInit, PLATFORM_ID, inject, signal, computed } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval, of, Subscription } from 'rxjs';
import { catchError, startWith, switchMap } from 'rxjs/operators';
import { TradingService } from '../../services/trading.service';
import { VwapBreakoutTradeDto, VwapBreakoutTradeService } from '../../services/vwap-breakout-trade.service';
import { VwapBreakoutLegPick, VwapBreakoutService, VwapBreakoutStartRequest, VwapBreakoutState, VwapPreview } from '../../services/vwap-breakout.service';
import { VwapBreakoutPreset, VwapBreakoutPresetService } from '../../services/vwap-breakout-preset.service';

const INDEX_META = { exchange: 'NSE', symbol: 'Nifty 50', token: '99926000' };
const DEFAULT_PREMIUM_RANGE = { from: 150, to: 250 };

export interface PremiumMatch {
  strike: number;
  premium: number;
  symbol: string;
  token: string;
}

const OPEN_LEG_STATUSES = new Set(['ENTRY_PLACED', 'ENTRY_CONFIRMED']);
const ORDER_EVENT_TYPES = new Set([
  'ORDER_PLACED', 'ORDER_FAILED', 'ENTRY_CONFIRMED', 'ENTRY_FAILED', 'REVERSAL_ENTRY',
]);

/**
 * Thin display layer over the server-side VwapBreakoutStrategyEngine - all monitoring
 * (candle polling, VWAP calc, entry/exit detection, order firing) runs in the backend,
 * so it survives this component being destroyed. This just lets you search/select
 * strikes, calls start()/stop() on the engine, and polls getState() to render it.
 */
@Component({
  selector: 'app-vwap-breakout',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './vwap-breakout.html',
  styleUrl: './vwap-breakout.scss',
})
export class VwapBreakoutComponent implements OnInit, OnDestroy {
  quantity = 5;
  mode: 'PAPER' | 'LIVE' = 'PAPER';
  quantityOptions = [1, 2, 3, 5, 10];
  targetPoints = 15;
  /** POINTS = today's per-trade points target (unchanged). PNL = individual trades still
   *  resolve the same way (points target + VWAP-cross SL), but the session keeps cycling
   *  trades regardless of win/loss until cumulative realized P&L hits pnlTarget (or its
   *  trailing stop, if pnlTrailingStep is set) - see the engine's checkPnlGovernor(). */
  targetType: 'POINTS' | 'PNL' = 'POINTS';
  pnlTarget: number | null = 5000;
  pnlTrailingStep: number | null = null;
  maxTrades = 3;
  entryWindowStart = '09:25';
  entryCutoff = '15:00';
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL' = 'VWAP_CROSS';

  newPresetName = '';
  presets = signal<VwapBreakoutPreset[]>([]);

  errorMsg = signal('');

  indexLtp = signal<number | null>(null);

  premiumFrom: number | null = DEFAULT_PREMIUM_RANGE.from;
  premiumTo: number | null = DEFAULT_PREMIUM_RANGE.to;
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

  /** Live VWAP for whichever strikes are picked, refreshed while you're still choosing -
   *  before a run exists to compute it, so you can see it before committing to Start. */
  ceVwapPreview = signal<VwapPreview | null>(null);
  peVwapPreview = signal<VwapPreview | null>(null);

  deployedFromPresetId: number | null = null;

  runState = signal<VwapBreakoutState | null>(null);
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

  tradeHistory = signal<VwapBreakoutTradeDto[]>([]);
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
    private tradeHistoryService: VwapBreakoutTradeService,
    private vwapBreakoutService: VwapBreakoutService,
    private vwapBreakoutPresetService: VwapBreakoutPresetService,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.vwapBreakoutPresetService.list().subscribe({
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
        switchMap(() => this.tradingService.getLTP(INDEX_META.exchange, INDEX_META.symbol, INDEX_META.token))
      )
      .subscribe({
        next: (r) => {
          if (r?.status && r.data?.ltp != null) this.indexLtp.set(Number(r.data.ltp));
        },
        error: () => {},
      });

    this.stateSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => this.vwapBreakoutService.getState().pipe(catchError(() => of(null)))),
      )
      .subscribe((state) => {
        if (!state) return;
        this.runState.set(state);
        if (state.active) {
          if (state.quantity != null) this.quantity = state.quantity;
          if (state.targetPoints != null) this.targetPoints = state.targetPoints;
          if (state.targetType) this.targetType = state.targetType;
          if (state.pnlTarget != null) this.pnlTarget = state.pnlTarget;
          if (state.pnlTrailingStep !== undefined) this.pnlTrailingStep = state.pnlTrailingStep ?? null;
          if (state.maxTrades != null) this.maxTrades = state.maxTrades;
          if (state.entryWindowStart) this.entryWindowStart = state.entryWindowStart;
          if (state.entryCutoff) this.entryCutoff = state.entryCutoff;
          if (state.exitMode) this.exitMode = state.exitMode as 'VWAP_CROSS' | 'TRAILING_SL';
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

  searchPremium(): void {
    this.premiumSearchError.set('');
    if (this.premiumFrom == null || this.premiumTo == null) {
      this.premiumSearchError.set('Enter both a from and to premium value.');
      return;
    }
    this.premiumSearching.set(true);
    this.selectedCe.set(null);
    this.selectedPe.set(null);
    this.tradingService.searchPremium('NIFTY', this.premiumFrom, this.premiumTo).subscribe({
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
    this.ceVwapPreview.set(null);
  }

  selectPe(m: PremiumMatch): void {
    const isDeselect = this.selectedPe()?.strike === m.strike;
    this.selectedPe.set(isDeselect ? null : m);
    this.peVwapPreview.set(null);
  }

  /** Manual, on-demand fetch (Preview button) - no auto-polling, so this is the only thing
   *  that ever calls the VWAP-preview endpoint before a run exists. Keeps API load to
   *  exactly what you ask for, rather than an ambient poll that risks Angel One's rate
   *  limit (see the 5s-poll incident this replaced). */
  previewVwap(): void {
    this.premiumSearchError.set('');
    const ce = this.selectedCe();
    const pe = this.selectedPe();
    if (!ce && !pe) {
      this.premiumSearchError.set('Select at least one of CE or PE first.');
      return;
    }
    if (ce) {
      this.ceVwapPreview.set(null);
      this.refreshVwapPreview('CE', ce.token);
    }
    if (pe) {
      this.peVwapPreview.set(null);
      this.refreshVwapPreview('PE', pe.token);
    }
  }

  private refreshVwapPreview(side: 'CE' | 'PE', token: string): void {
    this.vwapBreakoutService.getVwapPreview(this.resultsExchSeg(), token).subscribe({
      next: (r) => (side === 'CE' ? this.ceVwapPreview.set(r) : this.peVwapPreview.set(r)),
      error: () => {
        const failed: VwapPreview = { status: false, message: 'Failed to fetch VWAP.' };
        if (side === 'CE') this.ceVwapPreview.set(failed); else this.peVwapPreview.set(failed);
      },
    });
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
    if (!this.maxTrades || this.maxTrades <= 0) {
      this.errorMsg.set('Max trades must be greater than 0.');
      return;
    }
    if (this.exitMode === 'TRAILING_SL') {
      this.errorMsg.set('Trailing stop-loss isn’t available yet - use VWAP cross.');
      return;
    }
    if (this.targetType === 'PNL' && (!this.pnlTarget || this.pnlTarget <= 0)) {
      this.errorMsg.set('P&L target (₹) must be greater than 0.');
      return;
    }

    const toPick = (m: PremiumMatch | null): VwapBreakoutLegPick | null =>
      m ? { strike: m.strike, symbol: m.symbol, token: m.token } : null;

    const request: VwapBreakoutStartRequest = {
      indexName: 'NIFTY',
      exchSeg: this.resultsExchSeg(),
      quantity: this.quantity,
      targetPoints: this.targetPoints,
      targetType: this.targetType,
      pnlTarget: this.targetType === 'PNL' ? this.pnlTarget : null,
      pnlTrailingStep: this.targetType === 'PNL' ? this.pnlTrailingStep : null,
      maxTrades: this.maxTrades,
      entryWindowStart: this.entryWindowStart,
      entryCutoff: this.entryCutoff,
      exitMode: this.exitMode,
      mode: this.mode,
      ce: toPick(ce),
      pe: toPick(pe),
      presetId: this.deployedFromPresetId,
    };

    this.vwapBreakoutService.start(request).subscribe({
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
    this.vwapBreakoutService.stop(false).subscribe({
      next: (state) => {
        if (state.error) {
          if (confirm('A position is open. Square it off and stop?')) {
            this.vwapBreakoutService.stop(true).subscribe({
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

  viewTradeLog(trade: VwapBreakoutTradeDto): void {
    this.viewingTradeId.set(trade.id);
    this.viewingEvents.set([]);
    this.viewingEventsError.set('');

    if (!trade.runId) {
      this.viewingEventsError.set('No log available for this trade.');
      return;
    }

    this.viewingEventsLoading.set(true);
    this.vwapBreakoutService.getRunEvents(trade.runId).subscribe({
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
    this.premiumFrom = preset.premiumFrom;
    this.premiumTo = preset.premiumTo;
    this.quantity = preset.quantity;
    this.targetPoints = preset.targetPoints;
    this.targetType = preset.targetType;
    this.pnlTarget = preset.pnlTarget ?? 5000;
    this.pnlTrailingStep = preset.pnlTrailingStep ?? null;
    this.maxTrades = preset.maxTrades;
    this.entryWindowStart = preset.entryWindowStart;
    this.entryCutoff = preset.entryCutoff;
    this.exitMode = preset.exitMode;
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
    this.vwapBreakoutPresetService
      .save({
        name: this.newPresetName.trim(),
        indexName: 'NIFTY',
        premiumFrom: this.premiumFrom,
        premiumTo: this.premiumTo,
        quantity: this.quantity,
        targetPoints: this.targetPoints,
        targetType: this.targetType,
        pnlTarget: this.targetType === 'PNL' ? this.pnlTarget : null,
        pnlTrailingStep: this.targetType === 'PNL' ? this.pnlTrailingStep : null,
        maxTrades: this.maxTrades,
        entryWindowStart: this.entryWindowStart,
        entryCutoff: this.entryCutoff,
        exitMode: this.exitMode,
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
      case 'WATCHING': return 'Watching for VWAP cross';
      case 'ENTRY_PLACED': return 'Entry order placed…';
      case 'ENTRY_CONFIRMED': return 'Entered - open';
      case 'CLOSED': return 'Closed';
      case 'ENTRY_FAILED': return 'Entry failed - check broker';
      case 'SKIPPED': return 'Skipped - strategy stopped';
      default: return status || '—';
    }
  }
}
