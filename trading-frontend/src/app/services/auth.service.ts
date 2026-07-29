import { Injectable, signal, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export interface LoginResponse {
  status: boolean;
  message: string;
  data?: {
    clientId: string;
    name: string;
    email: string;
    feedToken: string;
    sessionInfo: { isAuthenticated: boolean; expiresAt: string };
  };
}

const SESSION_KEY = 'angel_session';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly apiUrl = '/api';
  private readonly platformId = inject(PLATFORM_ID);

  isLoggedIn = signal(false);
  clientName = signal('');
  clientId = signal('');

  constructor(private http: HttpClient) {
    if (isPlatformBrowser(this.platformId)) {
      const stored = localStorage.getItem(SESSION_KEY);
      if (stored) {
        try {
          const session = JSON.parse(stored);
          this.isLoggedIn.set(true);
          this.clientName.set(session.name || '');
          this.clientId.set(session.clientId || '');
        } catch {
          localStorage.removeItem(SESSION_KEY);
        }
      }
    }
  }

  login(clientId: string, password: string, totp: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.apiUrl}/auth/login`, { clientId, password, totp }).pipe(
      tap((res) => {
        if (res.status && res.data && isPlatformBrowser(this.platformId)) {
          this.isLoggedIn.set(true);
          this.clientName.set(res.data.name);
          this.clientId.set(res.data.clientId);
          localStorage.setItem(
            SESSION_KEY,
            JSON.stringify({ clientId: res.data.clientId, name: res.data.name, email: res.data.email })
          );
        }
      })
    );
  }

  logout(): Observable<any> {
    return this.http.post(`${this.apiUrl}/auth/logout`, { clientId: this.clientId() }).pipe(
      tap(() => {
        this.isLoggedIn.set(false);
        this.clientName.set('');
        this.clientId.set('');
        if (isPlatformBrowser(this.platformId)) {
          localStorage.removeItem(SESSION_KEY);
        }
      })
    );
  }

  getProfile(): Observable<any> {
    return this.http.get(`${this.apiUrl}/auth/profile`);
  }

  getStatus(): Observable<any> {
    return this.http.get(`${this.apiUrl}/auth/status`);
  }
}
