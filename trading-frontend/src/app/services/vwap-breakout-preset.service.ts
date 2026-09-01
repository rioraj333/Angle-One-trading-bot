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
  targetType: 'POINTS' | 'PNL';
  pnlTarget?: number | null;
  pnlTrailingStep?: number | null;
  maxDailyLoss?: number | null;
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  candleInterval?: 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE';
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  requireFreshBreakout?: boolean;
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
  targetType: 'POINTS' | 'PNL';
  pnlTarget?: number | null;
  pnlTrailingStep?: number | null;
  maxDailyLoss?: number | null;
  maxTrades: number;
  entryWindowStart: string;
  entryCutoff: string;
  candleInterval?: 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE';
  exitMode: 'VWAP_CROSS' | 'TRAILING_SL';
  requireFreshBreakout?: boolean;
  mode: 'PAPER' | 'LIVE';
}

export interface VwapBreakoutDeployResult {
  error?: string;
  scheduled?: boolean;
  triggerAt?: string;
  [key: string]: unknown;
}

export interface VwapBreakoutDeployStatus {
  pending: boolean;
  presetId?: number;
  presetName?: string;
  triggerAt?: string;
  status?: 'SCHEDULED' | 'DONE' | 'FAILED';
  message?: string;
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

  /** Defers strike selection to the preset's own entryWindowStart, or runs immediately
   *  if that's already passed today - see VwapBreakoutDeployScheduler. */
  deploy(id: number): Observable<VwapBreakoutDeployResult> {
    return this.http.post<VwapBreakoutDeployResult>(`${this.apiUrl}/${id}/deploy`, {});
  }

  deployStatus(): Observable<VwapBreakoutDeployStatus> {
    return this.http.get<VwapBreakoutDeployStatus>(`${this.apiUrl}/deploy-status`);
  }

  cancelDeploy(): Observable<{ cancelled?: boolean; error?: string }> {
    return this.http.post<{ cancelled?: boolean; error?: string }>(`${this.apiUrl}/deploy-status/cancel`, {});
  }
}
