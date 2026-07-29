import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Breakout925Preset {
  id: number;
  name: string;
  selectionMode: 'MANUAL' | 'AUTO';
  indexName: string;
  premiumFrom: number;
  premiumTo: number;
  candleFromTime: string;
  candleToTime: string;
  quantity: number;
  targetPoints: number;
  mode: 'PAPER' | 'LIVE';
  createdAt: string;
}

export interface SaveBreakout925PresetRequest {
  name: string;
  selectionMode: 'MANUAL' | 'AUTO';
  indexName: string;
  premiumFrom: number;
  premiumTo: number;
  candleFromTime: string;
  candleToTime: string;
  quantity: number;
  targetPoints: number;
  mode: 'PAPER' | 'LIVE';
}

export interface Breakout925DeployResult {
  selectionMode: 'MANUAL' | 'AUTO';
  preset?: Breakout925Preset;
  error?: string;
  active?: boolean;
  scheduled?: boolean;
  triggerAt?: string;
  [key: string]: unknown;
}

export interface Breakout925DeployStatus {
  pending: boolean;
  presetId?: number;
  presetName?: string;
  triggerAt?: string;
  status?: 'SCHEDULED' | 'DONE' | 'FAILED';
  message?: string;
}

@Injectable({ providedIn: 'root' })
export class Breakout925PresetService {
  private readonly apiUrl = '/api/breakout925/presets';

  constructor(private http: HttpClient) {}

  list(): Observable<Breakout925Preset[]> {
    return this.http.get<Breakout925Preset[]>(this.apiUrl);
  }

  save(request: SaveBreakout925PresetRequest): Observable<Breakout925Preset> {
    return this.http.post<Breakout925Preset>(this.apiUrl, request);
  }

  update(id: number, request: SaveBreakout925PresetRequest): Observable<Breakout925Preset> {
    return this.http.put<Breakout925Preset>(`${this.apiUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  deploy(id: number): Observable<Breakout925DeployResult> {
    return this.http.post<Breakout925DeployResult>(`${this.apiUrl}/${id}/deploy`, {});
  }

  deployStatus(): Observable<Breakout925DeployStatus> {
    return this.http.get<Breakout925DeployStatus>(`${this.apiUrl}/deploy-status`);
  }

  cancelDeploy(): Observable<{ cancelled?: boolean; error?: string }> {
    return this.http.post<{ cancelled?: boolean; error?: string }>(`${this.apiUrl}/deploy-status/cancel`, {});
  }
}
