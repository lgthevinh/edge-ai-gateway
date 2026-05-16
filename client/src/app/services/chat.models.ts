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

export interface PersistedSession {
  session_id: string;
  agent_id?: string;
  created_at: number;
  updated_at: number;
}

export interface AgentSessionsResult {
  sessions: PersistedSession[];
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

export interface PersistedAgentMessage {
  message_id: string;
  role: 'user' | 'assistant' | 'tool' | 'system';
  content: string;
  sequence: number;
  tool_calls?: unknown[];
  tool_call_id?: string;
  created_at: number;
}

export interface AgentHistoryResult {
  session_id: string;
  messages: PersistedAgentMessage[];
}

export interface AgentStreamHandlers {
  turn(tools: string[]): void;
  token(token: string): void;
  final(text: string, usage: ResponseUsage | null): void;
  done(): void;
  error(): void;
}
