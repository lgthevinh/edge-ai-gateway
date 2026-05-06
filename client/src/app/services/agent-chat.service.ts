import { DOCUMENT } from '@angular/common';
import { inject, Injectable, NgZone } from '@angular/core';
import { AgentStreamHandlers } from './chat.models';

@Injectable({ providedIn: 'root' })
export class AgentChatService {
  private readonly document = inject(DOCUMENT);
  private readonly zone = inject(NgZone);

  stream(sessionId: string, message: string, handlers: AgentStreamHandlers): EventSource | null {
    const EventSourceCtor = this.document.defaultView?.EventSource;
    if (!EventSourceCtor) {
      handlers.error();
      return null;
    }

    const body = encodeURIComponent(JSON.stringify({ session_id: sessionId, message }));
    const source = new EventSourceCtor(`/api/agent/chat/stream?body=${body}`);

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
        if (typeof data['final_text'] === 'string') handlers.final(data['final_text']);
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
}
