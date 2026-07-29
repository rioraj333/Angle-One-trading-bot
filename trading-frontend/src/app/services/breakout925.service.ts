import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Breakout925LegPick {
  strike: number;
  symbol: string;
  token: string;
}

export interface Breakout925StartRequest {
  indexName: string;
  exchSeg: string;
  candleFromTime: string;
  candleToTime: string;
  quantity: number;
  targetPoints: number;
  mode: 'PAPER' | 'LIVE';
  ce: Breakout925LegPick | null;
  pe: Breakout925LegPick | null;
  presetId?: number | null;
}

export interface Breakout925LegState {
  symbol: string;
  strike: number;
  candleHigh: number | null;
  candleLow: number | null;
  ltp: number | null;
  legStatus: string;
  entryPrice: number | null;
  target: number | null;
  stopLoss: number | null;
  maxProfit: number | null;
  maxDrawdown: number | null;
  unrealizedPnl: number | null;
  tradeId: number | null;
  entryOrderId: string | null;
  targetOrderId: string | null;
  stopLossOrderId: string | null;
}

export interface Breakout925State {
  active: boolean;
  id?: number;
  status?: string;
  mode?: 'PAPER' | 'LIVE';
  indexName?: string;
  exchSeg?: string;
  quantity?: number;
  targetPoints?: number;
  candleFromTime?: string;
  candleToTime?: string;
  presetId?: number | null;
  ce?: Breakout925LegState;
  pe?: Breakout925LegState;
  lastExitSide?: string | null;
  lastExitReason?: string | null;
  lastExitPrice?: number | null;
  error?: string;
  events?: { time: string; type: string; message: string }[];
}

@Injectable({ providedIn: 'root' })
export class Breakout925Service {
  private readonly apiUrl = '/api/breakout925';

  constructor(private http: HttpClient) {}

  start(request: Breakout925StartRequest): Observable<Breakout925State> {
    return this.http.post<Breakout925State>(`${this.apiUrl}/start`, request);
  }

  stop(forceExit: boolean): Observable<Breakout925State> {
    return this.http.post<Breakout925State>(`${this.apiUrl}/stop?forceExit=${forceExit}`, {});
  }

  getState(): Observable<Breakout925State> {
    return this.http.get<Breakout925State>(`${this.apiUrl}/state`);
  }

  modify(side: 'CE' | 'PE', newTarget?: number, newStopLoss?: number): Observable<Breakout925State> {
    return this.http.post<Breakout925State>(`${this.apiUrl}/modify`, { side, newTarget, newStopLoss });
  }
}
