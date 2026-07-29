import { Component, OnInit, PLATFORM_ID, inject, signal } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BreakoutState, RunSummary, StrategyService } from '../../services/strategy.service';

@Component({
  selector: 'app-breakout-history',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './breakout-history.html',
  styleUrl: './breakout-history.scss',
})
export class BreakoutHistoryComponent implements OnInit {
  private platformId = inject(PLATFORM_ID);

  runs = signal<RunSummary[]>([]);
  selectedRun = signal<BreakoutState | null>(null);
  selectedId = signal<number | null>(null);
  loading = signal(false);

  constructor(private strategyService: StrategyService) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.strategyService.getHistory().subscribe({
      next: (runs) => this.runs.set(runs),
      error: () => {},
    });
  }

  select(id: number): void {
    this.selectedId.set(id);
    this.loading.set(true);
    this.strategyService.getRunDetail(id).subscribe({
      next: (detail) => {
        this.loading.set(false);
        this.selectedRun.set(detail);
      },
      error: () => this.loading.set(false),
    });
  }

  fmt(n: number | undefined | null): string {
    return n === undefined || n === null ? '—' : n.toFixed(2);
  }
}
