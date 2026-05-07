import { inject, Injectable, signal } from '@angular/core';
import { BrowserStorageService } from '../middleware/browser-storage.service';
import { AgentHistoryMessage, ChatMessage, ResponseUsage, SessionRecord } from './chat.models';

const SESSIONS_KEY = 'edge-ai-gateway.sessions';
const ACTIVE_SESSION_KEY = 'edge-ai-gateway.activeSession';
const MESSAGES_PREFIX = 'edge-ai-gateway.messages.';

@Injectable({ providedIn: 'root' })
export class SessionStoreService {
  private readonly storage = inject(BrowserStorageService);

  readonly sessions = signal<SessionRecord[]>(this.readSessions());
  readonly activeSessionId = signal(this.storage.getItem(ACTIVE_SESSION_KEY) ?? '');
  readonly messages = signal<ChatMessage[]>([]);
  readonly activeUsage = signal<ResponseUsage>({ promptTokens: 0, completionTokens: 0, totalTokens: 0 });

  constructor() {
    if (!this.storage.isAvailable()) return;
    if (!this.activeSessionId()) {
      this.createSession();
      return;
    }
    this.loadMessages(this.activeSessionId());
  }

  createSession(): string {
    const id = this.createId();
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
    this.updateActiveUsage();
    this.persistMessages();
  }

  clearActiveMessages(): void {
    this.messages.set([]);
    this.updateActiveUsage();
    this.touchActiveSession();
    this.persistMessages();
  }

  deleteSession(sessionId: string): string {
    const deletedId = sessionId.trim();
    if (!deletedId) return this.activeSessionId();

    this.storage.removeItem(`${MESSAGES_PREFIX}${deletedId}`);
    const remaining = this.sessions().filter((session) => session.id !== deletedId);
    this.sessions.set(remaining);
    this.persistSessions();

    if (this.activeSessionId() !== deletedId) return this.activeSessionId();

    const nextId = remaining[0]?.id ?? this.createSession();
    this.selectSession(nextId);
    return nextId;
  }

  getHistoryForRequest(): AgentHistoryMessage[] {
    return this.messages()
      .filter((message) => !message.streaming && !message.error && message.content.trim())
      .map((message) => ({ role: message.role, content: message.content }));
  }

  private loadMessages(sessionId: string): void {
    this.messages.set(this.readJson<ChatMessage[]>(`${MESSAGES_PREFIX}${sessionId}`, []));
    this.updateActiveUsage();
  }

  private persistMessages(): void {
    const sessionId = this.activeSessionId();
    if (!sessionId) return;
    this.storage.setItem(`${MESSAGES_PREFIX}${sessionId}`, JSON.stringify(this.messages()));
  }

  private touchActiveSession(): void {
    const activeId = this.activeSessionId();
    const now = Date.now();
    const usage = this.sumUsage(this.messages());
    this.sessions.update((sessions) => sessions
      .map((session) => session.id === activeId ? { ...session, updatedAt: now, usage } : session)
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

  private updateActiveUsage(): void {
    const usage = this.sumUsage(this.messages());
    this.activeUsage.set(usage);
  }

  private sumUsage(messages: ChatMessage[]): ResponseUsage {
    return messages.reduce<ResponseUsage>((total, message) => ({
      promptTokens: total.promptTokens + (message.usage?.promptTokens ?? 0),
      completionTokens: total.completionTokens + (message.usage?.completionTokens ?? 0),
      totalTokens: total.totalTokens + (message.usage?.totalTokens ?? 0)
    }), { promptTokens: 0, completionTokens: 0, totalTokens: 0 });
  }

  private createId(): string {
    return globalThis.crypto?.randomUUID() ?? `session-${Date.now()}`;
  }
}
