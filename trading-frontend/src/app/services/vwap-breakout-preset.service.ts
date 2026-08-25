import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface VwapBreakoutPreset {
  id: number;
  name: string;
  indexName: string;
  premiumFrom: number;
  premiumTo: number;
  quantity: number;
  targetPoints: number;
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  mode: 'PAPER' | 'LIVE';
  createdAt: string;
}

export interface SaveVwapBreakoutPresetRequest {
  name: string;
  indexName: string;
  premiumFrom: number;
  premiumTo: number;
  quantity: number;
  targetPoints: number;
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  mode: 'PAPER' | 'LIVE';
}

@Injectable({ providedIn: 'root' })
export class VwapBreakoutPresetService {
  private readonly apiUrl = '/api/vwap-breakout/presets';

  constructor(private http: HttpClient) {}

  list(): Observable<VwapBreakoutPreset[]> {
    return this.http.get<VwapBreakoutPreset[]>(this.apiUrl);
  }

  save(request: SaveVwapBreakoutPresetRequest): Observable<VwapBreakoutPreset> {
    return this.http.post<VwapBreakoutPreset>(this.apiUrl, request);
  }

  update(id: number, request: SaveVwapBreakoutPresetRequest): Observable<VwapBreakoutPreset> {
    return this.http.put<VwapBreakoutPreset>(`${this.apiUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
