export type ChatStatus = 'idle' | 'thinking' | 'streaming';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  tools: string[][];
  streaming: boolean;
  error?: boolean;
  usage?: ResponseUsage;
  elapsedMs?: number;
}

export interface SessionRecord {
  id: string;
  label: string;
  createdAt: number;
  updatedAt: number;
  usage?: ResponseUsage;
}

export interface ResponseUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  promptPerSecond?: number;
  predictedPerSecond?: number;
}

export interface AgentHistoryMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface AgentStreamHandlers {
  turn(tools: string[]): void;
  token(token: string): void;
  final(text: string, usage: ResponseUsage | null): void;
  done(): void;
  error(): void;
}
