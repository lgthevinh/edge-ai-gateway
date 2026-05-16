import { inject, Injectable, signal } from '@angular/core';
import { BrowserStorageService } from '../middleware/browser-storage.service';
import { AgentHistoryMessage, AgentHistoryResult, AgentSessionsResult, ChatMessage, PersistedAgentMessage, PersistedSession, ResponseUsage, SessionRecord } from './chat.models';

const SESSIONS_KEY = 'edge-ai-gateway.sessions';
const ACTIVE_SESSION_KEY = 'edge-ai-gateway.activeSession';
const USAGE_PREFIX = 'edge-ai-gateway.usage.';
const CONTEXT_PREFIX = 'edge-ai-gateway.context.';
const EMPTY_USAGE: ResponseUsage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };

@Injectable({ providedIn: 'root' })
export class SessionStoreService {
  private readonly storage = inject(BrowserStorageService);

  readonly sessions = signal<SessionRecord[]>(this.readSessions());
  readonly activeSessionId = signal(this.storage.getItem(ACTIVE_SESSION_KEY) ?? '');
  readonly messages = signal<ChatMessage[]>([]);
  readonly activeUsage = signal<ResponseUsage>({ promptTokens: 0, completionTokens: 0, totalTokens: 0 });
  readonly contextLength = signal(0);

  constructor() {
    if (!this.storage.isAvailable()) return;
    void this.initialize();
  }

  private async initialize(): Promise<void> {
    await this.loadSessionsFromServer();
    if (!this.activeSessionId()) {
      this.createSession();
      return;
    }
    if (!this.sessions().some((session) => session.id === this.activeSessionId())) {
      const nextSession = this.sessions()[0];
      if (nextSession) {
        this.activeSessionId.set(nextSession.id);
        this.storage.setItem(ACTIVE_SESSION_KEY, nextSession.id);
      } else {
        this.createSession();
        return;
      }
    }
    this.activeUsage.set(this.readSessionUsage(this.activeSessionId()));
    this.contextLength.set(this.readSessionContextLength(this.activeSessionId()));
    await this.loadMessages(this.activeSessionId());
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
    this.activeUsage.set(this.readSessionUsage(cleanId));
    this.contextLength.set(this.readSessionContextLength(cleanId));
    void this.loadMessages(cleanId);
  }

  addMessage(message: ChatMessage): void {
    this.messages.update((messages) => [...messages, message]);
    this.touchActiveSession();
  }

  updateMessage(id: string, patch: Partial<ChatMessage>): void {
    this.messages.update((messages) => messages.map((message) => message.id === id ? { ...message, ...patch } : message));
    if (patch.usage) this.updateContextLength(patch.usage.totalTokens);
    this.touchActiveSession();
    this.updateActiveUsage();
  }

  clearActiveMessages(): void {
    this.messages.set([]);
    this.updateActiveUsage(false);
    this.touchActiveSession();
  }

  async deleteSession(sessionId: string): Promise<string> {
    const deletedId = sessionId.trim();
    if (!deletedId) return this.activeSessionId();

    await this.deleteSessionFromServer(deletedId);
    this.storage.removeItem(this.usageKey(deletedId));
    this.storage.removeItem(this.contextKey(deletedId));

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

  async loadActiveMessages(): Promise<void> {
    const sessionId = this.activeSessionId();
    if (!sessionId) return;
    await this.loadMessages(sessionId);
  }

  private async loadMessages(sessionId: string): Promise<void> {
    try {
      const response = await fetch(`/api/agent/chat/history?session_id=${encodeURIComponent(sessionId)}`);
      if (!response.ok) {
        this.messages.set([]);
        this.updateActiveUsage(true);
        return;
      }
      const result = await response.json() as AgentHistoryResult;
      this.messages.set(this.toChatMessages(result.messages));
      this.updateActiveUsage(true);
    } catch {
      this.messages.set([]);
      this.updateActiveUsage(true);
    }
  }

  private async loadSessionsFromServer(): Promise<void> {
    try {
      const response = await fetch('/api/agent/chat/sessions');
      if (!response.ok) return;
      const result = await response.json() as AgentSessionsResult;
      const merged = this.mergeSessions(this.sessions(), result.sessions.map((session) => this.toSessionRecord(session)));
      this.sessions.set(merged);
      this.persistSessions();

      if (!this.activeSessionId() && merged[0]) {
        this.activeSessionId.set(merged[0].id);
        this.storage.setItem(ACTIVE_SESSION_KEY, merged[0].id);
      }
    } catch {
      // Keep the local session list when the backend is not available.
    }
  }

  private async deleteSessionFromServer(sessionId: string): Promise<void> {
    const response = await fetch(`/api/agent/chat/sessions/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE'
    });
    if (!response.ok) {
      throw new Error(await response.text());
    }
  }

  private touchActiveSession(): void {
    const activeId = this.activeSessionId();
    const now = Date.now();
    const usage = this.activeUsage();
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

  private mergeSessions(localSessions: SessionRecord[], serverSessions: SessionRecord[]): SessionRecord[] {
    const byId = new Map<string, SessionRecord>();
    for (const session of localSessions) byId.set(session.id, session);
    for (const session of serverSessions) {
      const existing = byId.get(session.id);
      byId.set(session.id, {
        ...existing,
        ...session,
        usage: existing?.usage ?? this.readSessionUsage(session.id)
      });
    }
    return [...byId.values()].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  private toSessionRecord(session: PersistedSession): SessionRecord {
    return {
      id: session.session_id,
      label: this.formatLabel(session.created_at),
      createdAt: session.created_at,
      updatedAt: session.updated_at,
      usage: this.readSessionUsage(session.session_id)
    };
  }

  private updateActiveUsage(preserveStored = false): void {
    const calculated = this.sumUsage(this.messages());
    const stored = this.readSessionUsage(this.activeSessionId());
    const usage = preserveStored && calculated.totalTokens === 0 && stored.totalTokens > 0 ? stored : calculated;
    this.activeUsage.set(usage);
    this.persistActiveUsage(usage);
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

  private updateContextLength(totalTokens: number): void {
    if (!Number.isFinite(totalTokens) || totalTokens < 0) return;
    this.contextLength.set(totalTokens);
    const sessionId = this.activeSessionId();
    if (!sessionId) return;
    this.storage.setItem(this.contextKey(sessionId), String(totalTokens));
  }

  private persistActiveUsage(usage: ResponseUsage): void {
    const sessionId = this.activeSessionId();
    if (!sessionId) return;
    this.storage.setItem(this.usageKey(sessionId), JSON.stringify(usage));
    this.sessions.update((sessions) => sessions.map((session) => session.id === sessionId ? { ...session, usage } : session));
    this.persistSessions();
  }

  private readSessionUsage(sessionId: string): ResponseUsage {
    if (!sessionId) return { ...EMPTY_USAGE };
    const usage = this.readJson<ResponseUsage>(this.usageKey(sessionId), { ...EMPTY_USAGE });
    return {
      promptTokens: Number.isFinite(usage.promptTokens) ? usage.promptTokens : 0,
      completionTokens: Number.isFinite(usage.completionTokens) ? usage.completionTokens : 0,
      totalTokens: Number.isFinite(usage.totalTokens) ? usage.totalTokens : 0,
      ...(Number.isFinite(usage.promptPerSecond) ? { promptPerSecond: usage.promptPerSecond } : {}),
      ...(Number.isFinite(usage.predictedPerSecond) ? { predictedPerSecond: usage.predictedPerSecond } : {})
    };
  }

  private usageKey(sessionId: string): string {
    return `${USAGE_PREFIX}${sessionId}`;
  }

  private readSessionContextLength(sessionId: string): number {
    if (!sessionId) return 0;
    const value = Number(this.storage.getItem(this.contextKey(sessionId)) ?? 0);
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  private contextKey(sessionId: string): string {
    return `${CONTEXT_PREFIX}${sessionId}`;
  }

  private toChatMessages(messages: PersistedAgentMessage[]): ChatMessage[] {
    const chatMessages: ChatMessage[] = [];
    let pendingTools: string[][] = [];
    let pendingToolMessageId = '';

    for (const message of messages) {
      if (message.role === 'tool' || message.role === 'system') continue;

      const content = message.content ?? '';
      const toolNames = this.readToolNames(message.tool_calls);

      if (message.role === 'assistant' && toolNames.length > 0) {
        if (content.trim()) {
          chatMessages.push({
            id: message.message_id,
            role: 'assistant',
            content,
            tools: [...pendingTools, toolNames],
            streaming: false
          });
          pendingTools = [];
          pendingToolMessageId = '';
        } else {
          pendingTools = [...pendingTools, toolNames];
          pendingToolMessageId = message.message_id;
        }
        continue;
      }

      if (!content.trim()) continue;

      chatMessages.push({
        id: message.message_id,
        role: message.role,
        content,
        tools: message.role === 'assistant' ? pendingTools : [],
        streaming: false
      });
      if (message.role === 'assistant') {
        pendingTools = [];
        pendingToolMessageId = '';
      }
    }

    if (pendingTools.length > 0) {
      chatMessages.push({
        id: pendingToolMessageId || `tools-${Date.now()}`,
        role: 'assistant',
        content: '',
        tools: pendingTools,
        streaming: false
      });
    }

    return chatMessages;
  }

  private readToolNames(toolCalls: unknown[] | undefined): string[] {
    if (!Array.isArray(toolCalls)) return [];
    return toolCalls
      .map((toolCall) => {
        if (!toolCall || typeof toolCall !== 'object' || Array.isArray(toolCall)) return '';
        const functionValue = (toolCall as Record<string, unknown>)['function'];
        if (!functionValue || typeof functionValue !== 'object' || Array.isArray(functionValue)) return '';
        const name = (functionValue as Record<string, unknown>)['name'];
        return typeof name === 'string' ? name : '';
      })
      .filter((name) => name.length > 0);
  }
}
