import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface VwapBreakoutTradeDto {
  id: number;
  runId?: number | null;
  mode: 'PAPER' | 'LIVE';
  indexName: string;
  side: 'CE' | 'PE';
  strike: number;
  symbol: string;
  token: string;
  quantity: number;
  entryPrice: number;
  target: number;
  vwapAtEntry?: number;
  reversal: boolean;
  exitPrice?: number;
  exitReason?: string;
  maxProfit?: number;
  maxDrawdown?: number;
  realizedPnl?: number;
  status: 'OPEN' | 'CLOSED';
  entryTime: string;
  exitTime?: string;
}

@Injectable({ providedIn: 'root' })
export class VwapBreakoutTradeService {
  private readonly apiUrl = '/api/vwap-breakout/trades';

  constructor(private http: HttpClient) {}

  list(mode?: 'PAPER' | 'LIVE'): Observable<VwapBreakoutTradeDto[]> {
    let params = new HttpParams();
    if (mode) params = params.set('mode', mode);
    return this.http.get<VwapBreakoutTradeDto[]>(this.apiUrl, { params });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
