import { Routes } from '@angular/router';
import { LoginComponent } from './login/login';
import { DashboardComponent } from './dashboard/dashboard';
import { StrategyListComponent } from './strategies/strategy-list/strategy-list';
import { BreakoutComponent } from './strategies/breakout/breakout';
import { BreakoutHistoryComponent } from './strategies/breakout-history/breakout-history';
import { Breakout925Component } from './strategies/breakout925/breakout925';
import { VwapBreakoutComponent } from './strategies/vwap-breakout/vwap-breakout';
import { authGuard } from './guards/auth.guard';
import { canDeactivateGuard } from './guards/can-deactivate.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },
  { path: 'login', component: LoginComponent },
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'strategies', component: StrategyListComponent, canActivate: [authGuard] },
  { path: 'strategies/breakout', component: BreakoutComponent, canActivate: [authGuard] },
  { path: 'strategies/breakout/history', component: BreakoutHistoryComponent, canActivate: [authGuard] },
  {
    path: 'strategies/breakout925',
    component: Breakout925Component,
    canActivate: [authGuard],
    canDeactivate: [canDeactivateGuard],
  },
  {
    path: 'strategies/vwap-breakout',
    component: VwapBreakoutComponent,
    canActivate: [authGuard],
    canDeactivate: [canDeactivateGuard],
  },
  { path: '**', redirectTo: 'login' },
];
