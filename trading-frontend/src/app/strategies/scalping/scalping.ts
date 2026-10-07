import { Component, OnDestroy, OnInit, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterLink } from '@angular/router';
import { interval, of, Subscription } from 'rxjs';
import { catchError, startWith, switchMap } from 'rxjs/operators';
import { ScalpingService, ScalpingState, ScalpingTick } from '../../services/scalping.service';

/** How many ticks the table keeps on screen (newest first). */
const MAX_ROWS = 500;

type TickFilter = 'ALL' | 'SENSEX' | 'CE' | 'PE';

/**
 * Display layer over the server-side ScalpingStrategyEngine. The engine picks the strike
 * from SENSEX's open price at 09:15 and records every tick of SENSEX + that CE/PE; this
 * page polls the state and the new ticks once a second and shows them live.
 */
@Component({
  selector: 'app-scalping',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './scalping.html',
  styleUrl: './scalping.scss',
})
export class ScalpingComponent implements OnInit, OnDestroy {
  state = signal<ScalpingState | null>(null);
  ticks = signal<ScalpingTick[]>([]);
  filter = signal<TickFilter>('ALL');
  errorMsg = signal('');
  busy = signal(false);

  running = computed(() => this.state()?.active === true);
  gap = computed(() => {
    const s = this.state();
    return s?.openPrice != null && s?.prevClose != null ? s.openPrice - s.prevClose : null;
  });
  visibleTicks = computed(() => {
    const f = this.filter();
    const all = this.ticks();
    return f === 'ALL' ? all : all.filter((t) => t.instrument.startsWith(f));
  });

  private lastSeq = 0;
  private pollSub?: Subscription;
  private platformId = inject(PLATFORM_ID);

  constructor(private scalpingService: ScalpingService) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.pollSub = interval(1000)
      .pipe(
        startWith(0),
        switchMap(() => this.scalpingService.getState().pipe(catchError(() => of(null)))),
      )
      .subscribe((s) => {
        if (!s) return;
        // A new run restarts the server's sequence numbers - drop the old run's rows.
        if ((s.lastSeq ?? 0) < this.lastSeq) {
          this.lastSeq = 0;
          this.ticks.set([]);
        }
        this.state.set(s);
        if ((s.lastSeq ?? 0) > this.lastSeq) this.fetchTicks();
      });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private fetchTicks(): void {
    this.scalpingService.getTicks(this.lastSeq).subscribe({
      next: (rows) => {
        if (!rows.length) return;
        this.lastSeq = rows[rows.length - 1].seq;
        const merged = [...rows.slice().reverse(), ...this.ticks()];
        this.ticks.set(merged.slice(0, MAX_ROWS));
      },
      error: () => {},
    });
  }

  start(): void {
    this.errorMsg.set('');
    this.busy.set(true);
    this.scalpingService.start().subscribe({
      next: (s) => {
        this.busy.set(false);
        if (s.error) this.errorMsg.set(s.error);
        this.lastSeq = 0;
        this.ticks.set([]);
        this.state.set(s);
      },
      error: () => {
        this.busy.set(false);
        this.errorMsg.set('Could not reach the backend.');
      },
    });
  }

  stop(): void {
    this.busy.set(true);
    this.scalpingService.stop().subscribe({
      next: (s) => {
        this.busy.set(false);
        this.state.set(s);
      },
      error: () => this.busy.set(false),
    });
  }

  setFilter(f: TickFilter): void {
    this.filter.set(f);
  }

  fmtEventTime(iso: string): string {
    const m = iso.match(/T(\d{2}:\d{2}:\d{2})/);
    return m ? m[1] : iso;
  }

  rowClass(t: ScalpingTick): string {
    if (t.instrument === 'SENSEX') return 'inst-sensex';
    return t.instrument.startsWith('CE') ? 'inst-ce' : 'inst-pe';
  }
}
