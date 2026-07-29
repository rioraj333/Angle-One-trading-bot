import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Breakout925TradeDto {
  id: number;
  mode: 'PAPER' | 'LIVE';
  indexName: string;
  side: 'CE' | 'PE';
  strike: number;
  symbol: string;
  token: string;
  quantity: number;
  entryPrice: number;
  target: number;
  stopLoss: number;
  exitPrice?: number;
  exitReason?: string;
  maxProfit?: number;
  maxDrawdown?: number;
  realizedPnl?: number;
  status: 'OPEN' | 'CLOSED';
  entryTime: string;
  exitTime?: string;
}

export interface OpenTradeRequest {
  mode: 'PAPER' | 'LIVE';
  indexName: string;
  side: 'CE' | 'PE';
  strike: number;
  symbol: string;
  token: string;
  quantity: number;
  entryPrice: number;
  target: number;
  stopLoss: number;
}

export interface CloseTradeRequest {
  exitPrice: number;
  exitReason: string;
  maxProfit: number;
  maxDrawdown: number;
  realizedPnl: number;
}

@Injectable({ providedIn: 'root' })
export class Breakout925TradeService {
  private readonly apiUrl = '/api/breakout925/trades';

  constructor(private http: HttpClient) {}

  list(mode?: 'PAPER' | 'LIVE'): Observable<Breakout925TradeDto[]> {
    let params = new HttpParams();
    if (mode) params = params.set('mode', mode);
    return this.http.get<Breakout925TradeDto[]>(this.apiUrl, { params });
  }

  open(request: OpenTradeRequest): Observable<Breakout925TradeDto> {
    return this.http.post<Breakout925TradeDto>(this.apiUrl, request);
  }

  close(id: number, request: CloseTradeRequest): Observable<Breakout925TradeDto> {
    return this.http.put<Breakout925TradeDto>(`${this.apiUrl}/${id}/close`, request);
  }
}
