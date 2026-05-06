import { inject, Injectable, signal } from '@angular/core';
import { BrowserStorageService } from '../middleware/browser-storage.service';
import { ChatMessage, SessionRecord } from './chat.models';

const SESSIONS_KEY = 'edge-ai-gateway.sessions';
const ACTIVE_SESSION_KEY = 'edge-ai-gateway.activeSession';
const MESSAGES_PREFIX = 'edge-ai-gateway.messages.';

@Injectable({ providedIn: 'root' })
export class SessionStoreService {
  private readonly storage = inject(BrowserStorageService);

  readonly sessions = signal<SessionRecord[]>(this.readSessions());
  readonly activeSessionId = signal(this.storage.getItem(ACTIVE_SESSION_KEY) ?? '');
  readonly messages = signal<ChatMessage[]>([]);

  constructor() {
    if (!this.activeSessionId()) {
      this.createSession();
      return;
    }
    this.loadMessages(this.activeSessionId());
  }

  createSession(): string {
    const id = crypto.randomUUID();
    const now = Date.now();
    const session: SessionRecord = { id, label: this.formatLabel(now), createdAt: now, updatedAt: now };
    this.sessions.update((sessions) => [session, ...sessions]);
    this.persistSessions();
    this.selectSession(id);
    return id;
  }

  selectSession(id: string): void {
    const cleanId = id.trim();
    if (!cleanId) return;
    if (!this.sessions().some((session) => session.id === cleanId)) {
      const now = Date.now();
      this.sessions.update((sessions) => [{ id: cleanId, label: this.formatLabel(now), createdAt: now, updatedAt: now }, ...sessions]);
      this.persistSessions();
    }
    this.activeSessionId.set(cleanId);
    this.storage.setItem(ACTIVE_SESSION_KEY, cleanId);
    this.loadMessages(cleanId);
  }

  addMessage(message: ChatMessage): void {
    this.messages.update((messages) => [...messages, message]);
    this.touchActiveSession();
    this.persistMessages();
  }

  updateMessage(id: string, patch: Partial<ChatMessage>): void {
    this.messages.update((messages) => messages.map((message) => message.id === id ? { ...message, ...patch } : message));
    this.touchActiveSession();
    this.persistMessages();
  }

  clearActiveMessages(): void {
    this.messages.set([]);
    this.persistMessages();
  }

  private loadMessages(sessionId: string): void {
    this.messages.set(this.readJson<ChatMessage[]>(`${MESSAGES_PREFIX}${sessionId}`, []));
  }

  private persistMessages(): void {
    const sessionId = this.activeSessionId();
    if (!sessionId) return;
    this.storage.setItem(`${MESSAGES_PREFIX}${sessionId}`, JSON.stringify(this.messages()));
  }

  private touchActiveSession(): void {
    const activeId = this.activeSessionId();
    const now = Date.now();
    this.sessions.update((sessions) => sessions
      .map((session) => session.id === activeId ? { ...session, updatedAt: now } : session)
      .sort((a, b) => b.updatedAt - a.updatedAt));
    this.persistSessions();
  }

  private readSessions(): SessionRecord[] {
    return this.readJson<SessionRecord[]>(SESSIONS_KEY, []).sort((a, b) => b.updatedAt - a.updatedAt);
  }

  private persistSessions(): void {
    this.storage.setItem(SESSIONS_KEY, JSON.stringify(this.sessions()));
  }

  private readJson<T>(key: string, fallback: T): T {
    const raw = this.storage.getItem(key);
    if (!raw) return fallback;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return fallback;
    }
  }

  private formatLabel(timestamp: number): string {
    return new Date(timestamp).toLocaleString();
  }
}
