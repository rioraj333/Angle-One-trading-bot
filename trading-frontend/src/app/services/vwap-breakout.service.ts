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
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  mode: 'PAPER' | 'LIVE';
  ce: VwapBreakoutLegPick | null;
  pe: VwapBreakoutLegPick | null;
  presetId?: number | null;
}

export interface VwapBreakoutLegState {
  symbol: string;
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
  cumulativeRealizedPnl?: number;
  pnlTrailingActive?: boolean;
  peakCumulativePnl?: number | null;
  maxTrades?: number;
  entryCount?: number;
  entryWindowStart?: string;
  entryCutoff?: string;
  exitMode?: string;
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
}
