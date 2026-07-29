import { inject, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CanActivateFn, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { catchError, map, of } from 'rxjs';

export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  const platformId = inject(PLATFORM_ID);
  const http = inject(HttpClient);

  if (!isPlatformBrowser(platformId)) return true;

  if (localStorage.getItem('angel_session')) return true;

  // No local session yet — the backend may already have one from its own
  // scheduled auto-login (see BrokerService.autoLogin()). Check before
  // bouncing to the login page, so opening the app after auto-login doesn't
  // force you through the manual TOTP flow again.
  return http.get<{ authenticated: boolean; clientId?: string }>('/api/auth/status').pipe(
    map((status) => {
      if (status.authenticated) {
        localStorage.setItem(
          'angel_session',
          JSON.stringify({ clientId: status.clientId || '', name: '', email: '' })
        );
        return true;
      }
      router.navigate(['/login']);
      return false;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
};
