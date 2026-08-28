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
const DEFAULT_PREMIUM_RANGE = { from: 150, to: 200 };
const SPARKLINE_MAX_POINTS = 40;

export interface QuoteSnap {
  ltp: number;
  netChange: number;
  percentChange: number;
  volume: number;
  oi: number;
}

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
  /** Optional safety net, independent of targetType/maxTrades - stops the whole session the
   *  instant cumulative realized loss for the day reaches this many rupees. Guards against a
   *  choppy day burning through every configured trade in a string of small VWAP-cross losses. */
  maxDailyLoss: number | null = null;
  maxTrades = 5;
  entryWindowStart = '09:15';
  entryCutoff = '15:00';
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL' = 'VWAP_CROSS';
  /** Off by default. When on, a leg can't enter on whatever above/below-VWAP state
   *  already exists the moment it starts watching - it must first see a close at/below
   *  VWAP, then later close back above it. Mainly for starting mid-day, so you don't
   *  immediately chase a breakout that already happened before you clicked Start. */
  requireFreshBreakout = false;
  /** MANUAL (default) - pick a CE/PE strike from the searched list yourself. AUTO - the
   *  highest-premium strike in range on each side is picked automatically the moment
   *  Search Premium returns, same "highest premium in range" rule Breakout925's AUTO
   *  preset mode uses. */
  selectionMode: 'MANUAL' | 'AUTO' = 'MANUAL';

  newPresetName = '';
  showSavePresetModal = signal(false);
  presets = signal<VwapBreakoutPreset[]>([]);

  errorMsg = signal('');

  indexLtp = signal<number | null>(null);
  indexQuote = signal<QuoteSnap | null>(null);
  indexSparkline = signal<number[]>([]);
  ceQuote = signal<QuoteSnap | null>(null);
  peQuote = signal<QuoteSnap | null>(null);

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

  /** Live LTP for the previewed strikes - REST-polled every 1s once Preview is clicked
   *  (same lightweight quote endpoint the NIFTY spot price already polls, not the
   *  historical-candle endpoint VWAP uses - that one's manual-only, see previewVwap()). */
  ceLiveLtp = signal<number | null>(null);
  peLiveLtp = signal<number | null>(null);

  /** The preset currently loaded into the form (via the dropdown, or navigated in from
   *  the Dashboard's Deploy button) - null means "custom", not tied to any saved preset.
   *  Drives whether the header shows Save (new) or Update/Delete (existing). */
  deployedFromPresetId = signal<number | null>(null);
  selectedPreset = computed(() => {
    const id = this.deployedFromPresetId();
    return id != null ? this.presets().find((p) => p.id === id) ?? null : null;
  });
  presetActionMsg = signal('');

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

  /** Live wall-clock for the running-view header - purely cosmetic. */
  currentTime = signal(new Date());

  /** This run's own trades only (tradeHistory holds every past trade for the selected
   *  mode, so filter down to the currently active run for the Trades Today / Trade
   *  Summary panels). */
  todaysRunTrades = computed(() => {
    const runId = this.runState()?.id;
    if (runId == null) return [];
    return this.tradeHistory().filter((t) => t.runId === runId);
  });
  private closedRunTrades = computed(() => this.todaysRunTrades().filter((t) => t.status !== 'OPEN'));
  winsCount = computed(() => this.closedRunTrades().filter((t) => (t.realizedPnl ?? 0) > 0).length);
  lossesCount = computed(() => this.closedRunTrades().filter((t) => (t.realizedPnl ?? 0) <= 0).length);
  winRatePct = computed(() => {
    const closed = this.closedRunTrades().length;
    return closed > 0 ? (this.winsCount() / closed) * 100 : 0;
  });

  /** How far the session is toward whatever stops it - null when there's nothing
   *  meaningful to show a bar for (Points mode with no max daily loss configured). */
  sessionProgressPct = computed(() => {
    const s = this.runState();
    if (!s?.active) return null;
    const pnl = s.cumulativeRealizedPnl ?? 0;
    if (s.targetType === 'PNL' && s.pnlTarget) {
      return Math.max(0, Math.min(100, (pnl / s.pnlTarget) * 100));
    }
    if (s.maxDailyLoss) {
      return Math.max(0, Math.min(100, (-pnl / s.maxDailyLoss) * 100));
    }
    return null;
  });

  /** Plain-language status line for the STATUS stat card and the Current Trade panel. */
  statusHeadline = computed(() => {
    const s = this.runState();
    if (!s?.active) return '';
    if (s.status === 'DONE') return 'Session complete for the day.';
    if (this.cePositionOpen() || this.pePositionOpen()) return 'In position - watching for target or VWAP-cross exit.';
    return 'Monitoring - waiting for breakout above VWAP.';
  });

  /** Strategy Workflow strip - both views use this, but most steps only mean something
   *  once a run exists; the config view shows them all as pending. */
  workflowSteps = computed(() => {
    const s = this.runState();
    const active = s?.active === true;
    const vwapMarked = active && ((this.ceLeg()?.vwap ?? null) != null || (this.peLeg()?.vwap ?? null) != null);
    const everBrokeOut = active && (this.cePositionOpen() || this.pePositionOpen() || !!s?.lastExitSide);
    const entryWindowOpen = active && this.currentTime() >= this.parseHhmmToday(s!.entryWindowStart ?? this.entryWindowStart);
    const done = active && s?.status === 'DONE';
    return [
      { label: 'Market Open', sub: '9:15 AM', done: active },
      { label: 'Entry Window', sub: (s?.entryWindowStart ?? this.entryWindowStart), done: entryWindowOpen },
      { label: 'Premium Selected', sub: this.strikeSummary(), done: active },
      { label: 'VWAP Marked', sub: 'CE & PE', done: vwapMarked },
      { label: 'Monitoring', sub: 'Breakout watch', done: everBrokeOut, active: active && !everBrokeOut && !done },
      { label: 'Target / Exit', sub: 'Target or SL', done: done },
    ];
  });

  private strikeSummary(): string {
    const ce = this.selectedCe();
    const pe = this.selectedPe();
    if (ce && pe) return `${ce.strike} / ${pe.strike}`;
    if (ce) return `${ce.strike} CE`;
    if (pe) return `${pe.strike} PE`;
    return '—';
  }

  private parseHhmmToday(hhmm: string): Date {
    const [h, m] = hhmm.split(':').map(Number);
    const d = new Date();
    d.setHours(h, m, 0, 0);
    return d;
  }

  /** OTM/ITM tag for a leg's option chip, computed against the live index spot. */
  moneyness(side: 'CE' | 'PE', strike: number | undefined): string {
    const spot = this.indexLtp();
    if (spot == null || strike == null) return '';
    if (side === 'CE') return strike > spot ? 'OTM' : strike < spot ? 'ITM' : 'ATM';
    return strike < spot ? 'OTM' : strike > spot ? 'ITM' : 'ATM';
  }

  private lastHistoryLoadEntryCount: number | null = null;

  private indexPollSub?: Subscription;
  private legQuoteSub?: Subscription;
  private stateSub?: Subscription;
  private previewLtpSub?: Subscription;
  private clockSub?: Subscription;
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

    this.clockSub = interval(1000)
      .pipe(startWith(0))
      .subscribe(() => this.currentTime.set(new Date()));

    this.vwapBreakoutPresetService.list().subscribe({
      next: (list) => {
        this.presets.set(list);
        const presetId = this.route.snapshot.queryParamMap.get('presetId');
        if (presetId) this.applyPresetById(Number(presetId), true);
      },
      error: () => {},
    });

    // FULL quote (not just LTP) so the running view can show real net change/%/volume/OI
    // alongside the spot price - same broker endpoint, just the richer mode.
    this.indexPollSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => this.tradingService.getQuote('FULL', { [INDEX_META.exchange]: [INDEX_META.token] }).pipe(catchError(() => of(null))))
      )
      .subscribe((r) => {
        const entry = r?.status ? r.data?.fetched?.[0] : null;
        if (!entry) return;
        const snap = this.parseQuoteEntry(entry);
        this.indexLtp.set(snap.ltp);
        this.indexQuote.set(snap);
        this.indexSparkline.update((arr) => {
          const next = [...arr, snap.ltp];
          return next.length > SPARKLINE_MAX_POINTS ? next.slice(next.length - SPARKLINE_MAX_POINTS) : next;
        });
      });

    // Same FULL quote for whichever CE/PE legs are actually in the current run - only
    // while running, since that's the only view that shows volume/OI/change.
    this.legQuoteSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => {
          if (!this.running()) return of(null);
          const exch = this.runState()?.exchSeg ?? this.resultsExchSeg();
          const tokens = [this.ceLeg()?.token, this.peLeg()?.token].filter((t): t is string => !!t);
          if (tokens.length === 0) return of(null);
          return this.tradingService.getQuote('FULL', { [exch]: tokens }).pipe(catchError(() => of(null)));
        })
      )
      .subscribe((r) => {
        if (!r?.status) return;
        const rows: any[] = r.data?.fetched ?? [];
        const ceToken = this.ceLeg()?.token;
        const peToken = this.peLeg()?.token;
        for (const row of rows) {
          const snap = this.parseQuoteEntry(row);
          if (row.symbolToken === ceToken) this.ceQuote.set(snap);
          if (row.symbolToken === peToken) this.peQuote.set(snap);
        }
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
          if (state.maxDailyLoss !== undefined) this.maxDailyLoss = state.maxDailyLoss ?? null;
          if (state.maxTrades != null) this.maxTrades = state.maxTrades;
          if (state.entryWindowStart) this.entryWindowStart = state.entryWindowStart;
          if (state.entryCutoff) this.entryCutoff = state.entryCutoff;
          if (state.exitMode) this.exitMode = state.exitMode as 'VWAP_CROSS' | 'TRAILING_SL';
          if (state.requireFreshBreakout !== undefined) this.requireFreshBreakout = state.requireFreshBreakout;
          if (state.mode) this.mode = state.mode;

          // Keep the Trades Today / Trade Summary panels current as trades close out,
          // without hammering the endpoint every second - only reload when the count
          // of trades taken this run actually changes (or on the very first tick).
          if (state.entryCount !== this.lastHistoryLoadEntryCount) {
            this.lastHistoryLoadEntryCount = state.entryCount ?? 0;
            this.historyMode.set(state.mode ?? this.historyMode());
            this.loadHistory();
          }
        }
      });
  }

  ngOnDestroy(): void {
    this.indexPollSub?.unsubscribe();
    this.legQuoteSub?.unsubscribe();
    this.stateSub?.unsubscribe();
    this.previewLtpSub?.unsubscribe();
    this.clockSub?.unsubscribe();
  }

  private parseQuoteEntry(entry: any): QuoteSnap {
    return {
      ltp: Number(entry?.ltp ?? 0),
      netChange: Number(entry?.netChange ?? 0),
      percentChange: Number(entry?.percentChange ?? 0),
      volume: Number(entry?.tradeVolume ?? 0),
      oi: Number(entry?.opnInterest ?? 0),
    };
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
        const ce = (r.ce || []).slice().sort((a: PremiumMatch, b: PremiumMatch) => b.premium - a.premium);
        const pe = (r.pe || []).slice().sort((a: PremiumMatch, b: PremiumMatch) => b.premium - a.premium);
        this.ceMatches.set(ce);
        this.peMatches.set(pe);
        this.resultsExchSeg.set(r.exchSeg || 'NFO');
        this.searchedFrom.set(this.premiumFrom);
        this.searchedTo.set(this.premiumTo);

        // AUTO: pick the highest-premium strike in range on each side automatically -
        // same rule Breakout925's AUTO preset mode uses. You can still click a different
        // row afterwards to override it.
        if (this.selectionMode === 'AUTO') {
          if (ce.length > 0) this.selectCe(ce[0]);
          if (pe.length > 0) this.selectPe(pe[0]);
        }
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
    this.ceLiveLtp.set(null);
  }

  selectPe(m: PremiumMatch): void {
    const isDeselect = this.selectedPe()?.strike === m.strike;
    this.selectedPe.set(isDeselect ? null : m);
    this.peVwapPreview.set(null);
    this.peLiveLtp.set(null);
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
    this.startPreviewLiveTicks();
  }

  /** Live LTP ticking for both previewed sides, restarted fresh on every Preview click so
   *  it always tracks whatever's currently selected. Stops once a run starts - the
   *  running-state stat boxes take over from there. */
  private startPreviewLiveTicks(): void {
    this.previewLtpSub?.unsubscribe();
    this.previewLtpSub = interval(1000)
      .pipe(startWith(0))
      .subscribe(() => {
        if (this.running()) return;
        const ce = this.selectedCe();
        if (ce) {
          this.tradingService.getLTP(this.resultsExchSeg(), ce.symbol, ce.token).subscribe({
            next: (r) => { if (r?.status && r.data?.ltp != null) this.ceLiveLtp.set(Number(r.data.ltp)); },
            error: () => {},
          });
        }
        const pe = this.selectedPe();
        if (pe) {
          this.tradingService.getLTP(this.resultsExchSeg(), pe.symbol, pe.token).subscribe({
            next: (r) => { if (r?.status && r.data?.ltp != null) this.peLiveLtp.set(Number(r.data.ltp)); },
            error: () => {},
          });
        }
      });
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
    this.ceQuote.set(null);
    this.peQuote.set(null);
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
    if (this.maxDailyLoss != null && this.maxDailyLoss <= 0) {
      this.errorMsg.set('Max daily loss (₹) must be greater than 0.');
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
      maxDailyLoss: this.maxDailyLoss,
      maxTrades: this.maxTrades,
      entryWindowStart: this.entryWindowStart,
      entryCutoff: this.entryCutoff,
      exitMode: this.exitMode,
      requireFreshBreakout: this.requireFreshBreakout,
      mode: this.mode,
      ce: toPick(ce),
      pe: toPick(pe),
      presetId: this.deployedFromPresetId(),
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

  /** "View Report" button on the running page - opens (and scrolls to) the same Trade
   *  History card the config page has, rather than a separate, fake reporting screen. */
  viewReport(): void {
    if (!this.historyOpen()) this.toggleHistory();
    if (isPlatformBrowser(this.platformId)) {
      setTimeout(() => document.querySelector('.history-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50);
    }
  }

  fmtClock(d: Date): string {
    return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
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
    if (!idStr) {
      this.deployedFromPresetId.set(null); // "Custom..." picked - back to Save mode
      return;
    }
    this.applyPresetById(Number(idStr), false);
  }

  private applyPresetById(id: number, autoSearch: boolean): void {
    const preset = this.presets().find((p) => p.id === id);
    if (!preset) return;
    this.deployedFromPresetId.set(id);
    this.premiumFrom = preset.premiumFrom;
    this.premiumTo = preset.premiumTo;
    this.quantity = preset.quantity;
    this.targetPoints = preset.targetPoints;
    this.targetType = preset.targetType;
    this.pnlTarget = preset.pnlTarget ?? 5000;
    this.pnlTrailingStep = preset.pnlTrailingStep ?? null;
    this.maxDailyLoss = preset.maxDailyLoss ?? null;
    this.maxTrades = preset.maxTrades;
    this.entryWindowStart = preset.entryWindowStart;
    this.entryCutoff = preset.entryCutoff;
    this.exitMode = preset.exitMode;
    this.requireFreshBreakout = preset.requireFreshBreakout ?? false;
    this.mode = preset.mode;
    if (autoSearch) this.searchPremium();
  }

  openSavePresetModal(): void {
    this.errorMsg.set('');
    this.newPresetName = '';
    this.showSavePresetModal.set(true);
  }

  closeSavePresetModal(): void {
    this.showSavePresetModal.set(false);
  }

  private buildPresetRequest(name: string) {
    return {
      name,
      indexName: 'NIFTY',
      premiumFrom: this.premiumFrom!,
      premiumTo: this.premiumTo!,
      quantity: this.quantity,
      targetPoints: this.targetPoints,
      targetType: this.targetType,
      pnlTarget: this.targetType === 'PNL' ? this.pnlTarget : null,
      pnlTrailingStep: this.targetType === 'PNL' ? this.pnlTrailingStep : null,
      maxDailyLoss: this.maxDailyLoss,
      maxTrades: this.maxTrades,
      entryWindowStart: this.entryWindowStart,
      entryCutoff: this.entryCutoff,
      exitMode: this.exitMode,
      requireFreshBreakout: this.requireFreshBreakout,
      mode: this.mode,
    };
  }

  private flashPresetActionMsg(msg: string): void {
    this.presetActionMsg.set(msg);
    setTimeout(() => this.presetActionMsg.set(''), 2500);
  }

  confirmSavePreset(): void {
    this.errorMsg.set('');
    if (!this.newPresetName.trim()) return;
    if (this.premiumFrom == null || this.premiumTo == null) {
      this.errorMsg.set('Search a premium range before saving a preset.');
      return;
    }
    this.vwapBreakoutPresetService.save(this.buildPresetRequest(this.newPresetName.trim())).subscribe({
      next: (preset) => {
        this.presets.set([preset, ...this.presets()]);
        this.deployedFromPresetId.set(preset.id);
        this.newPresetName = '';
        this.showSavePresetModal.set(false);
        this.flashPresetActionMsg('Preset saved.');
      },
      error: (err) => this.errorMsg.set(err.error?.message || 'Failed to save preset.'),
    });
  }

  /** Header "Update" button - only shown once a saved preset is loaded (deployedFromPresetId
   *  set). Pushes the current form values back onto that same preset, keeping its name. */
  updatePreset(): void {
    this.errorMsg.set('');
    const preset = this.selectedPreset();
    if (!preset) return;
    if (this.premiumFrom == null || this.premiumTo == null) {
      this.errorMsg.set('Search a premium range before updating this preset.');
      return;
    }
    this.vwapBreakoutPresetService.update(preset.id, this.buildPresetRequest(preset.name)).subscribe({
      next: (updated) => {
        this.presets.set(this.presets().map((p) => (p.id === updated.id ? updated : p)));
        this.flashPresetActionMsg('Preset updated.');
      },
      error: (err) => this.errorMsg.set(err.error?.message || 'Failed to update preset.'),
    });
  }

  /** Header "Delete" button - removes the currently loaded preset and drops back to
   *  Custom/Save mode. */
  deletePreset(): void {
    const preset = this.selectedPreset();
    if (!preset) return;
    if (!confirm(`Delete preset "${preset.name}"? This cannot be undone.`)) return;
    this.vwapBreakoutPresetService.delete(preset.id).subscribe({
      next: () => {
        this.presets.set(this.presets().filter((p) => p.id !== preset.id));
        this.deployedFromPresetId.set(null);
        this.flashPresetActionMsg('Preset deleted.');
      },
      error: (err) => this.errorMsg.set(err.error?.message || 'Failed to delete preset.'),
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

  fmtChange(q: QuoteSnap | null): string {
    if (!q) return '—';
    const sign = q.netChange >= 0 ? '+' : '';
    return `${sign}${q.netChange.toFixed(2)} (${sign}${q.percentChange.toFixed(2)}%)`;
  }

  fmtVolume(n: number | undefined): string {
    if (!n) return '—';
    if (n >= 1e7) return (n / 1e7).toFixed(2) + 'Cr';
    if (n >= 1e5) return (n / 1e5).toFixed(2) + 'L';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
  }

  /** Simple inline SVG sparkline (last ~40 spot samples) - no charting library, just a
   *  normalized polyline over a fixed viewBox. */
  sparklinePoints(): string {
    const data = this.indexSparkline();
    if (data.length < 2) return '';
    const min = Math.min(...data);
    const max = Math.max(...data);
    const range = max - min || 1;
    const w = 100;
    const h = 28;
    return data
      .map((v, i) => {
        const x = (i / (data.length - 1)) * w;
        const y = h - ((v - min) / range) * h;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
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
