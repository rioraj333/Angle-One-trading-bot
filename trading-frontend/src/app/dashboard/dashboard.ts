import { Component, OnInit, OnDestroy, signal, computed, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, interval, Subscription } from 'rxjs';
import { startWith } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';
import { TradingService } from '../services/trading.service';
import { getMarketStatus, MarketStatus } from '../utils/market-hours';
import { Breakout925DeployStatus, Breakout925Preset, Breakout925PresetService } from '../services/breakout925-preset.service';

export interface Position {
  tradingsymbol: string;
  exchange: string;
  symboltoken: string;
  producttype: string;
  netqty: string;
  avgnetprice: string;
  ltp: string;
  pnl: string;
  unrealised: string;
}

export interface Order {
  orderid: string;
  tradingsymbol: string;
  transactiontype: string;
  quantity: string;
  price: string;
  status: string;
  updatetime: string;
  exchange: string;
  producttype: string;
  ordertype: string;
}

export interface Holding {
  tradingsymbol: string;
  exchange: string;
  quantity: string;
  profitandloss: string;
  pnlpercentage: string;
  ltp: string;
  symboltoken: string;
  averageprice: string;
}

export interface Funds {
  net: string;
  availablecash: string;
  availableintradaypayin: string;
  utiliseddebits: string;
  m2mrealized: string;
  m2munrealized: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardComponent implements OnInit, OnDestroy {
  private platformId = inject(PLATFORM_ID);

  clientId = signal(this.getStoredField('clientId'));
  clientName = signal(this.getStoredField('name'));

  activeSection = signal<'home' | 'positions' | 'orders' | 'holdings' | 'funds' | 'strategies'>('home');
  sidebarOpen = signal(false);
  currentTime = signal(new Date());
  marketStatus = signal<MarketStatus>(getMarketStatus());
  loadingData = signal(true);
  dataError = signal('');

  positions = signal<Position[]>([]);
  orders = signal<Order[]>([]);
  holdings = signal<Holding[]>([]);
  funds = signal<Funds | null>(null);

  showOrderPanel = signal(false);
  orderSide = signal<'BUY' | 'SELL'>('BUY');
  orderType = signal<'MARKET' | 'LIMIT'>('MARKET');
  orderSymbol = '';
  orderToken = '';
  orderExchange = 'NSE';
  orderQty = 1;
  orderPrice = 0;
  orderLoading = signal(false);
  orderResult = signal('');

  totalPnl = computed(() => this.positions().reduce((sum, p) => sum + parseFloat(p.pnl || '0'), 0));
  totalInvested = computed(() =>
    this.positions().reduce((sum, p) => sum + parseFloat(p.avgnetprice || '0') * Math.abs(parseInt(p.netqty || '0')), 0)
  );
  totalPnlPct = computed(() => (this.totalInvested() > 0 ? (this.totalPnl() / this.totalInvested()) * 100 : 0));

  greeting = computed(() => {
    const h = this.currentTime().getHours();
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  });

  strategyPresets = signal<Breakout925Preset[]>([]);
  presetsLoading = signal(false);
  deployingId = signal<number | null>(null);
  deployResult = signal<Record<number, { status: 'ok' | 'error'; message: string }>>({});
  pendingDeploy = signal<Breakout925DeployStatus | null>(null);
  cancelError = signal('');

  private clockSub?: Subscription;
  private refreshSub?: Subscription;
  private deployStatusSub?: Subscription;

  constructor(
    private router: Router,
    private authService: AuthService,
    private tradingService: TradingService,
    private breakout925PresetService: Breakout925PresetService
  ) {}

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.clockSub = interval(1000)
      .pipe(startWith(0))
      .subscribe(() => {
        this.currentTime.set(new Date());
        this.marketStatus.set(getMarketStatus());
      });

    this.refreshSub = interval(30_000)
      .pipe(startWith(0))
      .subscribe(() => this.loadAllData());
  }

  ngOnDestroy(): void {
    this.clockSub?.unsubscribe();
    this.refreshSub?.unsubscribe();
    this.deployStatusSub?.unsubscribe();
  }

  loadAllData(): void {
    this.loadingData.set(true);
    this.dataError.set('');

    forkJoin({
      positions: this.tradingService.getPositions(),
      orders: this.tradingService.getOrderBook(),
      holdings: this.tradingService.getHoldings(),
      funds: this.tradingService.getFunds(),
    }).subscribe({
      next: (results) => {
        this.loadingData.set(false);

        if (results.positions?.status) {
          const pos = results.positions.data;
          this.positions.set(Array.isArray(pos) ? pos : pos?.net ?? pos?.day ?? []);
        }
        if (results.orders?.status) {
          this.orders.set(Array.isArray(results.orders.data) ? results.orders.data : []);
        }
        if (results.holdings?.status) {
          const hld = results.holdings.data;
          this.holdings.set(Array.isArray(hld) ? hld : hld?.holdings ?? []);
        }
        if (results.funds?.status) {
          this.funds.set(results.funds.data ?? null);
        }
      },
      error: (err) => {
        this.loadingData.set(false);
        this.dataError.set(err.error?.message || err.message || 'Failed to load data.');
      },
    });
  }

  openOrderPanel(symbol: string, token: string, exchange: string, side: 'BUY' | 'SELL', ltp = 0): void {
    this.orderSymbol = symbol;
    this.orderToken = token;
    this.orderExchange = exchange;
    this.orderSide.set(side);
    this.orderQty = 1;
    this.orderPrice = ltp;
    this.orderResult.set('');
    this.showOrderPanel.set(true);
  }

  closeOrderPanel(): void {
    this.showOrderPanel.set(false);
    this.orderResult.set('');
  }

  placeOrder(): void {
    if (this.orderLoading()) return;
    this.orderLoading.set(true);
    this.orderResult.set('');

    const payload = {
      variety: 'NORMAL',
      tradingsymbol: this.orderSymbol,
      symboltoken: this.orderToken,
      transactiontype: this.orderSide() as 'BUY' | 'SELL',
      exchange: this.orderExchange,
      ordertype: this.orderType() as 'MARKET' | 'LIMIT',
      producttype: 'INTRADAY' as const,
      duration: 'DAY' as const,
      price: this.orderType() === 'MARKET' ? '0' : String(this.orderPrice),
      quantity: String(this.orderQty),
    };

    this.tradingService.placeOrder(payload).subscribe({
      next: (res) => {
        this.orderLoading.set(false);
        if (res.status) {
          this.orderResult.set(`Order placed. ID: ${res.data?.orderid || 'N/A'}`);
          setTimeout(() => {
            this.loadAllData();
            this.closeOrderPanel();
          }, 1500);
        } else {
          this.orderResult.set(res.message || 'Order failed');
        }
      },
      error: (err) => {
        this.orderLoading.set(false);
        this.orderResult.set(err.error?.message || 'Order failed');
      },
    });
  }

  logout(): void {
    this.authService.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login']),
    });
  }

  setSection(section: 'home' | 'positions' | 'orders' | 'holdings' | 'funds' | 'strategies'): void {
    this.activeSection.set(section);
    if (section === 'strategies' && this.strategyPresets().length === 0) this.loadPresets();
  }

  toggleSidebar(): void {
    this.sidebarOpen.update((v) => !v);
  }

  loadPresets(): void {
    this.presetsLoading.set(true);
    this.breakout925PresetService.list().subscribe({
      next: (list) => {
        this.presetsLoading.set(false);
        this.strategyPresets.set(list);
      },
      error: () => {
        this.presetsLoading.set(false);
      },
    });

    // Picks up a deploy that was already scheduled before this page load (e.g. refresh).
    this.breakout925PresetService.deployStatus().subscribe({
      next: (status) => {
        if (status.pending && status.status === 'SCHEDULED') {
          this.pendingDeploy.set(status);
          this.startDeployStatusPolling();
        }
      },
      error: () => {},
    });
  }

  deployPreset(preset: Breakout925Preset): void {
    if (preset.selectionMode === 'MANUAL') {
      this.router.navigate(['/strategies/breakout925'], { queryParams: { presetId: preset.id } });
      return;
    }

    this.deployingId.set(preset.id);
    this.breakout925PresetService.deploy(preset.id).subscribe({
      next: (result) => {
        this.deployingId.set(null);
        if (result.scheduled) {
          this.pendingDeploy.set({
            pending: true,
            presetId: preset.id,
            presetName: preset.name,
            triggerAt: result.triggerAt,
            status: 'SCHEDULED',
          });
          this.startDeployStatusPolling();
          return;
        }
        const results = { ...this.deployResult() };
        results[preset.id] = result.error
          ? { status: 'error', message: result.error }
          : { status: 'ok', message: 'Run started — view it on the Breakout925 page.' };
        this.deployResult.set(results);
      },
      error: (err) => {
        this.deployingId.set(null);
        const results = { ...this.deployResult() };
        results[preset.id] = { status: 'error', message: err.error?.message || 'Deploy failed.' };
        this.deployResult.set(results);
      },
    });
  }

  private startDeployStatusPolling(): void {
    this.deployStatusSub?.unsubscribe();
    this.deployStatusSub = interval(2000).subscribe(() => {
      this.breakout925PresetService.deployStatus().subscribe({
        next: (status) => {
          if (status.pending && status.status === 'SCHEDULED') {
            this.pendingDeploy.set(status);
            return;
          }
          this.deployStatusSub?.unsubscribe();
          const presetId = this.pendingDeploy()?.presetId ?? status.presetId;
          this.pendingDeploy.set(null);
          if (presetId != null) {
            const results = { ...this.deployResult() };
            results[presetId] =
              status.status === 'FAILED'
                ? { status: 'error', message: status.message || 'Deploy failed.' }
                : { status: 'ok', message: status.message || 'Run started.' };
            this.deployResult.set(results);
          }
        },
        error: () => {},
      });
    });
  }

  cancelPendingDeploy(): void {
    this.cancelError.set('');
    this.breakout925PresetService.cancelDeploy().subscribe({
      next: () => {
        this.deployStatusSub?.unsubscribe();
        this.pendingDeploy.set(null);
      },
      error: (err) => this.cancelError.set(err.error?.error || 'Failed to cancel.'),
    });
  }

  deletePreset(id: number): void {
    this.breakout925PresetService.delete(id).subscribe({
      next: () => this.strategyPresets.set(this.strategyPresets().filter((p) => p.id !== id)),
      error: () => {},
    });
  }

  formatCurrency(val: number | string): string {
    const n = typeof val === 'string' ? parseFloat(val) : val;
    if (isNaN(n)) return '0.00';
    return new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
  }

  formatNum(val: string | number): number {
    const n = typeof val === 'string' ? parseFloat(val) : val;
    return isNaN(n) ? 0 : n;
  }

  pnlClass(val: string | number): string {
    return this.formatNum(val) >= 0 ? 'up' : 'down';
  }

  orderStatusClass(status: string): string {
    const s = (status || '').toLowerCase();
    if (s === 'complete') return 'status-complete';
    if (s === 'rejected' || s === 'cancelled') return 'status-rejected';
    return 'status-pending';
  }

  private getStoredField(field: 'clientId' | 'name'): string {
    if (isPlatformBrowser(this.platformId)) {
      try {
        const s = localStorage.getItem('angel_session');
        return s ? JSON.parse(s)[field] || '' : '';
      } catch {
        return '';
      }
    }
    return '';
  }
}
