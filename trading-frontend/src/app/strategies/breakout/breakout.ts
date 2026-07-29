import { Component, OnDestroy, OnInit, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { interval, Subscription } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';
import { BreakoutState, StrategyPreset, StrategyService, StrikePreview } from '../../services/strategy.service';

@Component({
  selector: 'app-breakout',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './breakout.html',
  styleUrl: './breakout.scss',
})
export class BreakoutComponent implements OnInit, OnDestroy {
  quantity = 1;
  mode: 'PAPER' | 'LIVE' = 'PAPER';
  quantityOptions = [1, 2, 3, 5, 10];
  newPresetName = '';
  presets = signal<StrategyPreset[]>([]);

  state = signal<BreakoutState>({ active: false });
  starting = signal(false);
  stopping = signal(false);
  errorMsg = signal('');

  previewResult = signal<StrikePreview | null>(null);
  previewing = signal(false);

  // Backend keeps a finished run's `active: true` (it only tracks whether a
  // run object exists, not whether it's still live) — this flag is what
  // actually drives showing the config form again after DONE/WINDOW_CLOSED.
  showConfigForm = signal(false);

  isFinished = computed(() => {
    const status = this.state().status;
    return status === 'DONE' || status === 'WINDOW_CLOSED';
  });

  showWatch = computed(() => this.state().active && !this.showConfigForm());

  private pollSub?: Subscription;
  private platformId = inject(PLATFORM_ID);

  constructor(private strategyService: StrategyService) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.pollSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => this.strategyService.getState())
      )
      .subscribe({
        next: (s) => this.state.set(s),
        error: () => {},
      });

    this.strategyService.getPresets().subscribe({
      next: (p) => this.presets.set(p),
      error: () => {},
    });
  }

  applyPreset(idStr: string): void {
    if (!idStr) return;
    const preset = this.presets().find((p) => p.id === Number(idStr));
    if (!preset) return;
    this.quantity = preset.quantity;
    this.mode = preset.mode;
  }

  previewStrikes(): void {
    this.previewing.set(true);
    this.strategyService.previewStrike().subscribe({
      next: (r) => {
        this.previewing.set(false);
        this.previewResult.set(r);
      },
      error: (err) => {
        this.previewing.set(false);
        this.previewResult.set({ status: false, message: err.error?.message || 'Failed to fetch premiums.' });
      },
    });
  }

  saveCurrentAsPreset(): void {
    const name = this.newPresetName.trim();
    if (!name) return;
    this.strategyService.savePreset(name, this.quantity, this.mode).subscribe({
      next: (p) => {
        this.presets.update((list) => [p, ...list]);
        this.newPresetName = '';
      },
      error: () => {},
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  start(): void {
    this.errorMsg.set('');
    this.starting.set(true);
    this.strategyService.start(this.quantity, this.mode).subscribe({
      next: (s) => {
        this.starting.set(false);
        if ((s as any).error) {
          this.errorMsg.set((s as any).error);
        } else {
          this.state.set(s);
          this.showConfigForm.set(false);
        }
      },
      error: (err) => {
        this.starting.set(false);
        this.errorMsg.set(err.error?.message || 'Failed to start strategy.');
      },
    });
  }

  startNewRun(): void {
    this.errorMsg.set('');
    this.showConfigForm.set(true);
  }

  stop(): void {
    this.stopping.set(true);
    this.strategyService.stop().subscribe({
      next: (s) => {
        this.stopping.set(false);
        this.state.set(s);
      },
      error: () => this.stopping.set(false),
    });
  }

  statusLabel(status: string | undefined): string {
    const labels: Record<string, string> = {
      WAITING: 'Waiting for 9:22 strike selection',
      STRIKE_SELECTED: 'Strike selected, waiting for 9:20–9:25 candle',
      WATCHING_BREAKOUT: 'Watching for breakout',
      POSITION_OPEN: 'Position open',
      WINDOW_CLOSED: 'Window closed — no trade taken',
      DONE: 'Done for today',
    };
    return status ? labels[status] || status : '';
  }

  fmt(n: number | undefined | null): string {
    return n === undefined || n === null ? '—' : n.toFixed(2);
  }

  brokeOut(ltp: number | undefined | null, candleHigh: number | undefined | null): boolean {
    return ltp != null && candleHigh != null && ltp > candleHigh;
  }
}
