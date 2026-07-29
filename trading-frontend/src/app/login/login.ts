import { Component, signal, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

type Step = 'credentials' | 'totp';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class LoginComponent {
  @ViewChild('totpInput') totpInputRef!: ElementRef<HTMLInputElement>;

  // ── Form fields ──────────────────────────────────────────────────────────
  clientId = '';
  password = '';
  totp = '';
  showPassword = false;

  // ── UI state ─────────────────────────────────────────────────────────────
  step = signal<Step>('credentials');
  error = signal('');
  loading = signal(false);

  constructor(private router: Router, private authService: AuthService) {}

  onContinue(): void {
    this.error.set('');
    if (!this.clientId.trim()) {
      this.error.set('Enter your Angel One Client ID.');
      return;
    }
    if (!this.password.trim()) {
      this.error.set('Enter your MPIN / password.');
      return;
    }
    this.step.set('totp');
    this.totp = '';
    setTimeout(() => this.focusTotpInput(), 50);
  }

  focusTotpInput(): void {
    this.totpInputRef?.nativeElement?.focus();
  }

  onTotpInput(): void {
    this.totp = this.totp.replace(/\D/g, '').slice(0, 6);
    if (this.totp.length === 6) {
      this.submitLogin();
    }
  }

  onTotpLogin(): void {
    this.error.set('');
    if (this.totp.length < 6) {
      this.error.set('Enter all 6 digits from your authenticator app.');
      return;
    }
    this.submitLogin();
  }

  private submitLogin(): void {
    if (this.loading()) return;

    this.error.set('');
    this.loading.set(true);

    this.authService.login(this.clientId.trim(), this.password, this.totp.trim()).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.status) {
          this.router.navigate(['/dashboard']);
        } else {
          this.error.set(res.message || 'Login rejected by SmartAPI. Check your credentials or TOTP.');
          this.totp = '';
          setTimeout(() => this.focusTotpInput(), 50);
        }
      },
      error: (err) => {
        this.loading.set(false);
        const msg =
          err.error?.message || err.message || 'Cannot reach the backend. Make sure it is running on port 8080.';
        this.error.set(msg);
        this.totp = '';
        setTimeout(() => this.focusTotpInput(), 50);
      },
    });
  }

  goBack(): void {
    this.step.set('credentials');
    this.totp = '';
    this.error.set('');
  }

  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }

  get totpDigits(): string[] {
    return Array.from({ length: 6 }, (_, i) => this.totp[i] ?? '');
  }
}
