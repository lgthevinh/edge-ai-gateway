import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentChatService } from '../../services/agent-chat.service';
import { ChatMessage, ChatStatus, ResponseUsage } from '../../services/chat.models';
import { DocumentService } from '../../services/document.service';
import { SessionStoreService } from '../../services/session-store.service';
import { MarkdownService } from '../../middleware/markdown.service';

@Component({
  selector: 'app-chat-shell',
  imports: [FormsModule],
  templateUrl: './chat-shell.html',
  styleUrl: './chat-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChatShell {
  private readonly agentChat = inject(AgentChatService);
  private readonly documentService = inject(DocumentService);
  readonly sessionStore = inject(SessionStoreService);
  readonly markdown = inject(MarkdownService);

  @ViewChild('chatBox') private chatBox?: ElementRef<HTMLElement>;
  @ViewChild('inputBox') private inputBox?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('knowledgeTitleInput') private knowledgeTitleInput?: ElementRef<HTMLInputElement>;

  readonly input = signal('');
  readonly sessionInput = signal(this.sessionStore.activeSessionId());
  readonly status = signal<ChatStatus>('idle');
  readonly isBusy = computed(() => this.status() !== 'idle');
  readonly activeUsage = this.sessionStore.activeUsage;
  readonly knowledgeTitle = signal('');
  readonly knowledgeDescription = signal('');
  readonly knowledgeContent = signal('');
  readonly knowledgeQuery = signal('');
  readonly knowledgeStatus = signal('');
  readonly knowledgeResults = signal<string[]>([]);
  readonly isSavingKnowledge = signal(false);
  readonly isSearchingKnowledge = signal(false);
  readonly isKnowledgeDialogOpen = signal(false);
  readonly isKnowledgeBusy = computed(() => this.isSavingKnowledge() || this.isSearchingKnowledge());

  private activeSource: EventSource | null = null;

  selectSession(id: string): void {
    if (this.isBusy()) return;
    this.sessionStore.selectSession(id);
    this.sessionInput.set(id);
    queueMicrotask(() => this.scrollToBottom());
  }

  createSession(): void {
    if (this.isBusy()) return;
    this.sessionInput.set(this.sessionStore.createSession());
    queueMicrotask(() => this.focusInput());
  }

  deleteActiveSession(): void {
    if (this.isBusy()) return;
    const nextId = this.sessionStore.deleteSession(this.sessionStore.activeSessionId());
    this.sessionInput.set(nextId);
    queueMicrotask(() => this.focusInput());
  }

  applySessionInput(): void {
    this.selectSession(this.sessionInput());
  }

  openKnowledgeDialog(): void {
    if (this.isBusy() || this.isKnowledgeBusy()) return;
    this.isKnowledgeDialogOpen.set(true);
    queueMicrotask(() => this.knowledgeTitleInput?.nativeElement.focus());
  }

  closeKnowledgeDialog(): void {
    if (this.isSavingKnowledge()) return;
    this.isKnowledgeDialogOpen.set(false);
  }

  async saveKnowledge(): Promise<void> {
    const title = this.knowledgeTitle().trim();
    const description = this.knowledgeDescription().trim();
    const content = this.knowledgeContent().trim();
    if (!title || !description || !content || this.isBusy() || this.isKnowledgeBusy()) return;

    this.isSavingKnowledge.set(true);
    this.knowledgeStatus.set(`Saving ${title}...`);
    try {
      const document = await this.documentService.saveDocument({ title, description, content });
      this.knowledgeTitle.set('');
      this.knowledgeDescription.set('');
      this.knowledgeContent.set('');
      this.knowledgeStatus.set(`Saved ${document.title}`);
      this.isKnowledgeDialogOpen.set(false);
    } catch (error) {
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Save failed');
    } finally {
      this.isSavingKnowledge.set(false);
    }
  }

  async searchKnowledge(): Promise<void> {
    const query = this.knowledgeQuery().trim();
    if (!query || this.isBusy() || this.isKnowledgeBusy()) return;

    this.isSearchingKnowledge.set(true);
    this.knowledgeStatus.set('Searching knowledge...');
    try {
      const result = await this.documentService.searchDocuments(query);
      this.knowledgeResults.set(result.documents.map((document) => `${document.title} - ${document.description}`));
      this.knowledgeStatus.set(result.documents.length > 0 ? `${result.documents.length} result(s)` : 'No matching documents');
    } catch (error) {
      this.knowledgeResults.set([]);
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Search failed');
    } finally {
      this.isSearchingKnowledge.set(false);
    }
  }

  send(): void {
    const text = this.input().trim();
    if (!text || this.isBusy() || this.isKnowledgeBusy()) return;

    const sessionId = this.sessionStore.activeSessionId() || this.sessionStore.createSession();
    const history = this.sessionStore.getHistoryForRequest();
    this.sessionInput.set(sessionId);
    this.input.set('');
    this.resizeInput();
    this.status.set('thinking');

    this.sessionStore.addMessage(this.createMessage('user', text, false));
    const assistant = this.createMessage('assistant', '', true);
    this.sessionStore.addMessage(assistant);
    queueMicrotask(() => this.scrollToBottom());

    let tokenBuffer = '';
    let finalBuffer = '';

    void this.agentChat.stream(sessionId, text, history, {
      turn: (tools) => {
        this.sessionStore.updateMessage(assistant.id, { tools: [...this.findMessage(assistant.id).tools, tools] });
        this.status.set('thinking');
        queueMicrotask(() => this.scrollToBottom());
      },
      token: (token) => {
        tokenBuffer += token;
        this.sessionStore.updateMessage(assistant.id, { content: tokenBuffer });
        this.status.set('streaming');
        queueMicrotask(() => this.scrollToBottom());
      },
      final: (text, usage) => {
        finalBuffer = text;
        if (usage) this.sessionStore.updateMessage(assistant.id, { usage });
      },
      done: () => {
        this.sessionStore.updateMessage(assistant.id, { content: finalBuffer || tokenBuffer, streaming: false });
        this.status.set('idle');
        this.activeSource = null;
        queueMicrotask(() => this.focusInput());
      },
      error: () => {
        const content = tokenBuffer || '[Connection error - please try again]';
        this.sessionStore.updateMessage(assistant.id, { content, streaming: false, error: !tokenBuffer });
        this.status.set('idle');
        this.activeSource = null;
      }
    }).then((source) => {
      this.activeSource = source;
    });
  }

  onInputKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    this.send();
  }

  onKnowledgeDialogKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    this.closeKnowledgeDialog();
  }

  resizeInput(): void {
    const textarea = this.inputBox?.nativeElement;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
  }

  trackMessage(_index: number, message: ChatMessage): string {
    return message.id;
  }

  formatUsage(usage: ResponseUsage | undefined): string {
    if (!usage) return '';
    return `${usage.totalTokens} total · ${usage.promptTokens} in · ${usage.completionTokens} out`;
  }

  private createMessage(role: ChatMessage['role'], content: string, streaming: boolean): ChatMessage {
    return { id: globalThis.crypto?.randomUUID() ?? `message-${Date.now()}`, role, content, tools: [], streaming };
  }

  private findMessage(id: string): ChatMessage {
    return this.sessionStore.messages().find((message) => message.id === id) ?? this.createMessage('assistant', '', false);
  }

  private scrollToBottom(): void {
    const element = this.chatBox?.nativeElement;
    if (!element) return;
    element.scrollTop = element.scrollHeight;
  }

  private focusInput(): void {
    this.inputBox?.nativeElement.focus();
  }
}
