import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

interface StrategyCard {
  name: string;
  description: string;
  route: string | null;
}

@Component({
  selector: 'app-strategy-list',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './strategy-list.html',
  styleUrl: './strategy-list.scss',
})
export class StrategyListComponent {
  strategies: StrategyCard[] = [
    {
      name: 'NIFTY CE/PE 200-OTM Breakout',
      description: 'Intraday, buy-only. 9:25–9:45 entry window, 15pt target, candle-low stop, alternating recovery.',
      route: '/strategies/breakout',
    },
    {
      name: '9:25 Breakout Strategy',
      description: 'Reference candle 9:20–9:25 IST. Strike/instrument selection logic in progress — UI only for now.',
      route: '/strategies/breakout925',
    },
    {
      name: 'Coming soon',
      description: 'Next strategy slot — not built yet.',
      route: null,
    },
  ];
}
