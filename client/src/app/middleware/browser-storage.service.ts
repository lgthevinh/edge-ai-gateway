import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { inject, Injectable, PLATFORM_ID } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class BrowserStorageService {
  private readonly document = inject(DOCUMENT);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  isAvailable(): boolean {
    return this.isBrowser;
  }

  getItem(key: string): string | null {
    if (!this.isBrowser) return null;
    return this.document.defaultView?.localStorage.getItem(key) ?? null;
  }

  setItem(key: string, value: string): void {
    if (!this.isBrowser) return;
    this.document.defaultView?.localStorage.setItem(key, value);
  }

  removeItem(key: string): void {
    if (!this.isBrowser) return;
    this.document.defaultView?.localStorage.removeItem(key);
  }
}
