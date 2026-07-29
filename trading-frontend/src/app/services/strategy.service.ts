import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface StrategyEventDto {
  time: string;
  type: string;
  message: string;
}

export interface RunSummary {
  id: number;
  runDate: string;
  status: string;
  mode: string;
  quantity: number;
  ceStrike?: number;
  peStrike?: number;
  realizedPnlPoints: number;
  cumulativeLossPoints: number;
}

export interface StrikePreview {
  status: boolean;
  message?: string;
  spot?: number;
  atmStrike?: number;
  ceSymbol?: string;
  ceStrike?: number;
  cePremium?: number;
  peSymbol?: string;
  peStrike?: number;
  pePremium?: number;
}

export interface StrategyPreset {
  id: number;
  name: string;
  quantity: number;
  mode: 'PAPER' | 'LIVE';
}

export interface BreakoutState {
  id?: number;
  runDate?: string;
  active: boolean;
  status?: string;
  mode?: 'PAPER' | 'LIVE';
  quantity?: number;
  spotAtSelection?: number;
  ceSymbol?: string;
  ceStrike?: number;
  cePremiumAtSelection?: number;
  ceCandleHigh?: number;
  ceCandleLow?: number;
  ceLtp?: number;
  peSymbol?: string;
  peStrike?: number;
  pePremiumAtSelection?: number;
  peCandleHigh?: number;
  peCandleLow?: number;
  peLtp?: number;
  activeSide?: 'CE' | 'PE' | null;
  entryPrice?: number;
  targetPrice?: number;
  stopLossPrice?: number;
  cumulativeLossPoints?: number;
  realizedPnlPoints?: number;
  lastExitReason?: string;
  events?: StrategyEventDto[];
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class StrategyService {
  private readonly apiUrl = '/api/strategy/breakout';

  constructor(private http: HttpClient) {}

  start(quantity: number, mode: 'PAPER' | 'LIVE'): Observable<BreakoutState> {
    return this.http.post<BreakoutState>(`${this.apiUrl}/start`, { quantity, mode });
  }

  stop(): Observable<BreakoutState> {
    return this.http.post<BreakoutState>(`${this.apiUrl}/stop`, {});
  }

  getState(): Observable<BreakoutState> {
    return this.http.get<BreakoutState>(`${this.apiUrl}/state`);
  }

  previewStrike(): Observable<StrikePreview> {
    return this.http.get<StrikePreview>(`${this.apiUrl}/preview-strike`);
  }

  getHistory(): Observable<RunSummary[]> {
    return this.http.get<RunSummary[]>(`${this.apiUrl}/history`);
  }

  getRunDetail(id: number): Observable<BreakoutState> {
    return this.http.get<BreakoutState>(`${this.apiUrl}/history/${id}`);
  }

  getPresets(): Observable<StrategyPreset[]> {
    return this.http.get<StrategyPreset[]>(`${this.apiUrl}/presets`);
  }

  savePreset(name: string, quantity: number, mode: 'PAPER' | 'LIVE'): Observable<StrategyPreset> {
    return this.http.post<StrategyPreset>(`${this.apiUrl}/presets`, { name, quantity, mode });
  }

  deletePreset(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/presets/${id}`);
  }
}
