import { DOCUMENT } from '@angular/common';
import { inject, Injectable, NgZone } from '@angular/core';
import { AgentHistoryMessage, AgentStreamHandlers, ResponseUsage } from './chat.models';

@Injectable({ providedIn: 'root' })
export class AgentChatService {
  private readonly document = inject(DOCUMENT);
  private readonly zone = inject(NgZone);

  async stream(sessionId: string, message: string, history: AgentHistoryMessage[], handlers: AgentStreamHandlers): Promise<EventSource | null> {
    const EventSourceCtor = this.document.defaultView?.EventSource;
    if (!EventSourceCtor) {
      handlers.error();
      return null;
    }

    const streamId = await this.startStream(sessionId, message, history);
    if (!streamId) {
      handlers.error();
      return null;
    }

    const source = new EventSourceCtor(`/api/agent/chat/stream?stream_id=${encodeURIComponent(streamId)}`);

    source.addEventListener('turn', (event) => {
      this.zone.run(() => handlers.turn(this.parseTools(event as MessageEvent)));
    });
    source.addEventListener('token', (event) => {
      this.zone.run(() => {
        const data = this.parseJson(event as MessageEvent);
        if (typeof data['token'] === 'string') handlers.token(data['token']);
      });
    });
    source.addEventListener('final', (event) => {
      this.zone.run(() => {
        const data = this.parseJson(event as MessageEvent);
        if (typeof data['final_text'] === 'string') handlers.final(data['final_text'], this.parseUsage(data['usage']));
      });
    });
    source.addEventListener('done', () => {
      this.zone.run(() => {
        source.close();
        handlers.done();
      });
    });
    source.addEventListener('error', () => {
      this.zone.run(() => {
        source.close();
        handlers.error();
      });
    });

    return source;
  }

  private async startStream(sessionId: string, message: string, history: AgentHistoryMessage[]): Promise<string | null> {
    try {
      const response = await fetch('/api/agent/chat/stream/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: sessionId, message, history })
      });
      if (!response.ok) return null;
      const data: unknown = await response.json();
      if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
      const streamId = (data as Record<string, unknown>)['stream_id'];
      return typeof streamId === 'string' && streamId ? streamId : null;
    } catch {
      return null;
    }
  }

  private parseTools(event: MessageEvent): string[] {
    const data = this.parseJson(event);
    return Array.isArray(data['tools']) ? data['tools'].filter((item): item is string => typeof item === 'string') : [];
  }

  private parseJson(event: MessageEvent): Record<string, unknown> {
    try {
      const value: unknown = JSON.parse(event.data || '{}');
      return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
    } catch {
      return {};
    }
  }

  private parseUsage(value: unknown): ResponseUsage | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const usage = value as Record<string, unknown>;
    const promptTokens = this.readNumber(usage['promptTokens'] ?? usage['prompt_tokens']);
    const completionTokens = this.readNumber(usage['completionTokens'] ?? usage['completion_tokens']);
    const totalTokens = this.readNumber(usage['totalTokens'] ?? usage['total_tokens']);
    if (promptTokens === null && completionTokens === null && totalTokens === null) return null;
    return {
      promptTokens: promptTokens ?? 0,
      completionTokens: completionTokens ?? 0,
      totalTokens: totalTokens ?? ((promptTokens ?? 0) + (completionTokens ?? 0))
    };
  }

  private readNumber(value: unknown): number | null {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  }
}
