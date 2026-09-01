import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface VwapBreakoutLegPick {
  strike: number;
  symbol: string;
  token: string;
}

export interface VwapBreakoutStartRequest {
  indexName: string;
  exchSeg: string;
  quantity: number;
  targetPoints: number;
  /** POINTS (default) or PNL - see targetType on VwapBreakoutState for what each means. */
  targetType: 'POINTS' | 'PNL';
  /** Cumulative-session P&L target in rupees - only used when targetType is PNL. */
  pnlTarget?: number | null;
  /** Optional trailing step in rupees once pnlTarget is first reached - omit/null for a hard stop. */
  pnlTrailingStep?: number | null;
  /** Optional safety net, independent of targetType/maxTrades: stops the whole session the
   *  instant cumulative realized loss for the day reaches this many rupees. */
  maxDailyLoss?: number | null;
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  /** ONE_MINUTE (default), THREE_MINUTE, or FIVE_MINUTE - candle size used for VWAP/breakout checks. */
  candleInterval?: 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE';
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  /** Off by default. When on, a leg can't enter on whatever above/below-VWAP state
   *  already exists the moment it starts watching - it must first see a close at/below
   *  VWAP, then later close back above it. Mainly for starting mid-day, so you don't
   *  immediately chase a breakout that already happened before you clicked Start. */
  requireFreshBreakout?: boolean;
  mode: 'PAPER' | 'LIVE';
  ce: VwapBreakoutLegPick | null;
  pe: VwapBreakoutLegPick | null;
  presetId?: number | null;
}

export interface VwapBreakoutLegState {
  symbol: string;
  token?: string;
  strike: number;
  ltp: number | null;
  vwap: number | null;
  legStatus: string;
  entryPrice: number | null;
  target: number | null;
  vwapAtEntry: number | null;
  maxProfit: number | null;
  maxDrawdown: number | null;
  unrealizedPnl: number | null;
  tradeId: number | null;
}

export interface VwapPreview {
  status: boolean;
  vwap?: number;
  lastClose?: number;
  message?: string;
}

export interface VwapBreakoutState {
  active: boolean;
  id?: number;
  status?: string;
  mode?: 'PAPER' | 'LIVE';
  indexName?: string;
  exchSeg?: string;
  quantity?: number;
  targetPoints?: number;
  targetType?: 'POINTS' | 'PNL';
  pnlTarget?: number | null;
  pnlTrailingStep?: number | null;
  maxDailyLoss?: number | null;
  cumulativeRealizedPnl?: number;
  pnlTrailingActive?: boolean;
  peakCumulativePnl?: number | null;
  maxTrades?: number;
  entryCount?: number;
  entryWindowStart?: string;
  entryCutoff?: string;
  candleInterval?: 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE';
  exitMode?: string;
  requireFreshBreakout?: boolean;
  presetId?: number | null;
  ce?: VwapBreakoutLegState;
  pe?: VwapBreakoutLegState;
  lastExitSide?: string | null;
  lastExitReason?: string | null;
  lastExitPrice?: number | null;
  error?: string;
  events?: { time: string; type: string; message: string }[];
}

@Injectable({ providedIn: 'root' })
export class VwapBreakoutService {
  private readonly apiUrl = '/api/vwap-breakout';

  constructor(private http: HttpClient) {}

  start(request: VwapBreakoutStartRequest): Observable<VwapBreakoutState> {
    return this.http.post<VwapBreakoutState>(`${this.apiUrl}/start`, request);
  }

  stop(forceExit: boolean): Observable<VwapBreakoutState> {
    return this.http.post<VwapBreakoutState>(`${this.apiUrl}/stop?forceExit=${forceExit}`, {});
  }

  getState(): Observable<VwapBreakoutState> {
    return this.http.get<VwapBreakoutState>(`${this.apiUrl}/state`);
  }

  getRunEvents(runId: number): Observable<{ time: string; type: string; message: string }[]> {
    return this.http.get<{ time: string; type: string; message: string }[]>(`${this.apiUrl}/runs/${runId}/events`);
  }

  getVwapPreview(exchSeg: string, token: string, candleInterval?: string): Observable<VwapPreview> {
    const params: Record<string, string> = { exchSeg, token };
    if (candleInterval) params['candleInterval'] = candleInterval;
    return this.http.get<VwapPreview>(`${this.apiUrl}/vwap-preview`, { params });
  }
}
