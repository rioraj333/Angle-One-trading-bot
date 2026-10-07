import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ScalpingTick {
  seq: number;
  /** Time the bot received the tick (IST, HH:mm:ss.SSS). */
  time: string;
  /** Exchange timestamp of the tick (IST, HH:mm:ss.SSS), if the feed sent one. */
  exchangeTime: string;
  /** "SENSEX", "CE <strike>" or "PE <strike>". */
  instrument: string;
  ltp: number;
  /** Change vs the previous tick of the same instrument. */
  change: number | null;
}

export interface ScalpingState {
  status: 'IDLE' | 'WAITING_OPEN' | 'STREAMING' | 'STOPPED' | 'ERROR';
  active: boolean;
  message?: string | null;
  /** True when the backend starts the run by itself at 09:14 on weekdays. */
  autoStart?: boolean;
  /** Recording window, e.g. "09:15-09:16". */
  window?: string;
  runDate?: string | null;
  openPrice?: number | null;
  prevClose?: number | null;
  strike?: number | null;
  expiry?: string | null;
  ceSymbol?: string | null;
  peSymbol?: string | null;
  sensexLtp?: number | null;
  ceLtp?: number | null;
  peLtp?: number | null;
  tickCount?: number;
  lastSeq?: number;
  error?: string;
  events?: { time: string; type: string; message: string }[];
}

@Injectable({ providedIn: 'root' })
export class ScalpingService {
  private readonly apiUrl = '/api/scalping';

  constructor(private http: HttpClient) {}

  start(): Observable<ScalpingState> {
    return this.http.post<ScalpingState>(`${this.apiUrl}/start`, {});
  }

  stop(): Observable<ScalpingState> {
    return this.http.post<ScalpingState>(`${this.apiUrl}/stop`, {});
  }

  getState(): Observable<ScalpingState> {
    return this.http.get<ScalpingState>(`${this.apiUrl}/state`);
  }

  getTicks(afterSeq: number, limit = 500): Observable<ScalpingTick[]> {
    return this.http.get<ScalpingTick[]>(`${this.apiUrl}/ticks?afterSeq=${afterSeq}&limit=${limit}`);
  }
}
