export type ChatStatus = 'idle' | 'thinking' | 'streaming';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  tools: string[][];
  streaming: boolean;
  error?: boolean;
}

export interface SessionRecord {
  id: string;
  label: string;
  createdAt: number;
  updatedAt: number;
}

export interface AgentStreamHandlers {
  turn(tools: string[]): void;
  token(token: string): void;
  final(text: string): void;
  done(): void;
  error(): void;
}
