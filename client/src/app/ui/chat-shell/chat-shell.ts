import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentChatService } from '../../services/agent-chat.service';
import { ChatMessage, ChatStatus, ResponseUsage } from '../../services/chat.models';
import { DocumentService, RagChunk, UploadedDocument } from '../../services/document.service';
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
  @ViewChild('ragTitleInput') private ragTitleInput?: ElementRef<HTMLInputElement>;
  @ViewChild('ragSearchInput') private ragSearchInput?: ElementRef<HTMLInputElement>;

  readonly input = signal('');
  readonly sessionInput = signal(this.sessionStore.activeSessionId());
  readonly status = signal<ChatStatus>('idle');
  readonly isBusy = computed(() => this.status() !== 'idle');
  readonly activeUsage = this.sessionStore.activeUsage;
  readonly contextLength = this.sessionStore.contextLength;
  readonly contextLimit = 131072;
  readonly knowledgeTitle = signal('');
  readonly knowledgeDescription = signal('');
  readonly knowledgeContent = signal('');
  readonly knowledgeEnabled = signal(true);
  readonly knowledgeStatus = signal('');
  readonly knowledgeDocuments = signal<UploadedDocument[]>([]);
  readonly selectedKnowledgeTitle = signal('');
  readonly isEditingKnowledge = computed(() => Boolean(this.selectedKnowledgeTitle()));
  readonly isLoadingKnowledge = signal(false);
  readonly isSavingKnowledge = signal(false);
  readonly isDeletingKnowledge = signal(false);
  readonly isKnowledgeDialogOpen = signal(false);
  readonly isKnowledgeBusy = computed(() =>
    this.isLoadingKnowledge() || this.isSavingKnowledge() || this.isDeletingKnowledge()
  );
  readonly ragTitle = signal('');
  readonly ragSource = signal('');
  readonly ragContent = signal('');
  readonly ragQuery = signal('');
  readonly ragStatus = signal('');
  readonly ragChunks = signal<RagChunk[]>([]);
  readonly ragResults = signal<RagChunk[]>([]);
  readonly selectedRagChunkId = signal('');
  readonly isEditingRag = computed(() => Boolean(this.selectedRagChunkId()));
  readonly ragWordCount = computed(() => this.countWords(this.ragContent()));
  readonly isRagContentTooLong = computed(() => this.ragWordCount() > 200);
  readonly isLoadingRag = signal(false);
  readonly isSavingRag = signal(false);
  readonly isDeletingRag = signal(false);
  readonly isSearchingRag = signal(false);
  readonly isRagDialogOpen = signal(false);
  readonly isRagBusy = computed(() =>
    this.isLoadingRag() || this.isSavingRag() || this.isDeletingRag() || this.isSearchingRag()
  );

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

  async deleteActiveSession(): Promise<void> {
    if (this.isBusy()) return;
    const nextId = await this.sessionStore.deleteSession(this.sessionStore.activeSessionId());
    this.sessionInput.set(nextId);
    queueMicrotask(() => this.focusInput());
  }

  applySessionInput(): void {
    this.selectSession(this.sessionInput());
  }

  openKnowledgeDialog(): void {
    if (this.isBusy() || this.isKnowledgeBusy()) return;
    this.isKnowledgeDialogOpen.set(true);
    void this.loadKnowledgeDocuments();
    queueMicrotask(() => this.knowledgeTitleInput?.nativeElement.focus());
  }

  openRagDialog(): void {
    if (this.isBusy() || this.isRagBusy()) return;
    this.isRagDialogOpen.set(true);
    void this.loadRagChunks();
    queueMicrotask(() => this.ragSearchInput?.nativeElement.focus());
  }

  closeRagDialog(): void {
    if (this.isSavingRag() || this.isDeletingRag()) return;
    this.isRagDialogOpen.set(false);
  }

  closeKnowledgeDialog(): void {
    if (this.isSavingKnowledge() || this.isDeletingKnowledge()) return;
    this.isKnowledgeDialogOpen.set(false);
  }

  createKnowledgeDocument(): void {
    if (this.isKnowledgeBusy()) return;
    this.selectedKnowledgeTitle.set('');
    this.knowledgeTitle.set('');
    this.knowledgeDescription.set('');
    this.knowledgeContent.set('');
    this.knowledgeEnabled.set(true);
    this.knowledgeStatus.set('Creating a new document');
    queueMicrotask(() => this.knowledgeTitleInput?.nativeElement.focus());
  }

  async loadKnowledgeDocuments(): Promise<void> {
    if (this.isLoadingKnowledge()) return;

    this.isLoadingKnowledge.set(true);
    this.knowledgeStatus.set('Loading knowledge...');
    try {
      const result = await this.documentService.listDocuments();
      this.knowledgeDocuments.set(result.documents);
      this.knowledgeStatus.set(result.documents.length > 0 ? `${result.documents.length} document(s)` : 'No documents yet');
    } catch (error) {
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Load failed');
    } finally {
      this.isLoadingKnowledge.set(false);
    }
  }

  async readKnowledge(title: string): Promise<void> {
    if (!title || this.isBusy() || this.isKnowledgeBusy()) return;

    this.isLoadingKnowledge.set(true);
    this.knowledgeStatus.set(`Reading ${title}...`);
    try {
      const document = await this.documentService.getDocument(title);
      this.selectedKnowledgeTitle.set(document.title);
      this.knowledgeTitle.set(document.title);
      this.knowledgeDescription.set(document.description);
      this.knowledgeContent.set(document.content);
      this.knowledgeEnabled.set(document.enabled);
      this.knowledgeStatus.set(`Loaded ${document.title}`);
      if (!this.isKnowledgeDialogOpen()) {
        this.isKnowledgeDialogOpen.set(true);
      }
    } catch (error) {
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Read failed');
    } finally {
      this.isLoadingKnowledge.set(false);
    }
  }

  async saveKnowledge(): Promise<void> {
    const title = this.knowledgeTitle().trim();
    const description = this.knowledgeDescription().trim();
    const content = this.knowledgeContent().trim();
    const enabled = this.knowledgeEnabled();
    if (!title || !description || !content || this.isBusy() || this.isKnowledgeBusy()) return;

    this.isSavingKnowledge.set(true);
    this.knowledgeStatus.set(`Saving ${title}...`);
    try {
      const document = await this.documentService.saveDocument({ title, description, content, enabled });
      this.knowledgeTitle.set('');
      this.knowledgeDescription.set('');
      this.knowledgeContent.set('');
      this.knowledgeEnabled.set(true);
      this.selectedKnowledgeTitle.set('');
      await this.loadKnowledgeDocuments();
      this.knowledgeStatus.set(`Saved ${document.title}`);
    } catch (error) {
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Save failed');
    } finally {
      this.isSavingKnowledge.set(false);
    }
  }

  async deleteKnowledge(title = this.selectedKnowledgeTitle() || this.knowledgeTitle().trim()): Promise<void> {
    if (!title || this.isBusy() || this.isKnowledgeBusy()) return;
    if (!this.documentService.confirm(`Delete "${title}" from the knowledge base?`)) return;

    this.isDeletingKnowledge.set(true);
    this.knowledgeStatus.set(`Deleting ${title}...`);
    try {
      await this.documentService.deleteDocument(title);
      if (this.selectedKnowledgeTitle() === title || this.knowledgeTitle().trim() === title) {
        this.selectedKnowledgeTitle.set('');
        this.knowledgeTitle.set('');
        this.knowledgeDescription.set('');
        this.knowledgeContent.set('');
        this.knowledgeEnabled.set(true);
      }
      await this.loadKnowledgeDocuments();
      this.knowledgeStatus.set(`Deleted ${title}`);
    } catch (error) {
      this.knowledgeStatus.set(error instanceof Error ? error.message : 'Delete failed');
    } finally {
      this.isDeletingKnowledge.set(false);
    }
  }

  createRagChunk(): void {
    if (this.isRagBusy()) return;
    this.selectedRagChunkId.set('');
    this.ragTitle.set('');
    this.ragSource.set('');
    this.ragContent.set('');
    this.ragStatus.set('Creating a new RAG chunk');
    queueMicrotask(() => this.ragTitleInput?.nativeElement.focus());
  }

  async loadRagChunks(): Promise<void> {
    if (this.isLoadingRag()) return;

    this.isLoadingRag.set(true);
    this.ragStatus.set('Loading RAG chunks...');
    try {
      const result = await this.documentService.listRagChunks();
      this.ragChunks.set(result.chunks);
      this.ragStatus.set(result.chunks.length > 0 ? `${result.chunks.length} chunk(s)` : 'No RAG chunks yet');
    } catch (error) {
      this.ragStatus.set(error instanceof Error ? error.message : 'Load failed');
    } finally {
      this.isLoadingRag.set(false);
    }
  }

  async readRagChunk(chunkId: string): Promise<void> {
    if (!chunkId || this.isBusy() || this.isRagBusy()) return;

    this.isLoadingRag.set(true);
    this.ragStatus.set('Reading RAG chunk...');
    try {
      const chunk = await this.documentService.getRagChunk(chunkId);
      this.selectedRagChunkId.set(chunk.chunk_id);
      this.ragTitle.set(chunk.title);
      this.ragSource.set(chunk.source || '');
      this.ragContent.set(chunk.content);
      this.ragStatus.set(`Loaded ${chunk.title}`);
      if (!this.isRagDialogOpen()) this.isRagDialogOpen.set(true);
    } catch (error) {
      this.ragStatus.set(error instanceof Error ? error.message : 'Read failed');
    } finally {
      this.isLoadingRag.set(false);
    }
  }

  async saveRagChunk(): Promise<void> {
    const title = this.ragTitle().trim();
    const source = this.ragSource().trim();
    const content = this.ragContent().trim();
    if (!title || !content || this.isBusy() || this.isRagBusy() || this.isRagContentTooLong()) return;

    this.isSavingRag.set(true);
    this.ragStatus.set(`Saving ${title}...`);
    try {
      const chunk = await this.documentService.saveRagChunk({
        ...(this.selectedRagChunkId() ? { chunk_id: this.selectedRagChunkId() } : {}),
        title,
        source,
        content
      });
      this.ragTitle.set('');
      this.ragSource.set('');
      this.ragContent.set('');
      this.selectedRagChunkId.set('');
      await this.loadRagChunks();
      this.ragStatus.set(`Saved ${chunk.title}`);
    } catch (error) {
      this.ragStatus.set(error instanceof Error ? error.message : 'Save failed');
    } finally {
      this.isSavingRag.set(false);
    }
  }

  async deleteRagChunk(chunkId = this.selectedRagChunkId()): Promise<void> {
    if (!chunkId || this.isBusy() || this.isRagBusy()) return;
    if (!this.documentService.confirm('Delete this RAG chunk?')) return;

    this.isDeletingRag.set(true);
    this.ragStatus.set('Deleting RAG chunk...');
    try {
      await this.documentService.deleteRagChunk(chunkId);
      if (this.selectedRagChunkId() === chunkId) {
        this.selectedRagChunkId.set('');
        this.ragTitle.set('');
        this.ragSource.set('');
        this.ragContent.set('');
      }
      this.ragResults.update((chunks) => chunks.filter((chunk) => chunk.chunk_id !== chunkId));
      await this.loadRagChunks();
      this.ragStatus.set('Deleted RAG chunk');
    } catch (error) {
      this.ragStatus.set(error instanceof Error ? error.message : 'Delete failed');
    } finally {
      this.isDeletingRag.set(false);
    }
  }

  async searchRag(): Promise<void> {
    const query = this.ragQuery().trim();
    if (!query || this.isBusy() || this.isRagBusy()) return;

    this.isSearchingRag.set(true);
    this.ragStatus.set('Searching RAG chunks...');
    try {
      const result = await this.documentService.searchRagChunks(query);
      this.ragResults.set(result.chunks);
      this.ragStatus.set(result.chunks.length > 0 ? `${result.chunks.length} result(s)` : 'No matching chunks');
    } catch (error) {
      this.ragResults.set([]);
      this.ragStatus.set(error instanceof Error ? error.message : 'Search failed');
    } finally {
      this.isSearchingRag.set(false);
    }
  }

  send(): void {
    const text = this.input().trim();
    if (!text || this.isBusy() || this.isKnowledgeBusy() || this.isRagBusy()) return;

    const sessionId = this.sessionStore.activeSessionId() || this.sessionStore.createSession();
    this.sessionInput.set(sessionId);
    this.input.set('');
    this.resizeInput();
    this.status.set('thinking');

    this.sessionStore.addMessage(this.createMessage('user', text, false));
    const assistant = this.createMessage('assistant', '', true);
    this.sessionStore.addMessage(assistant);
    queueMicrotask(() => this.scrollToBottom());

    const startedAt = performance.now();
    let tokenBuffer = '';
    let finalBuffer = '';

    void this.agentChat.stream(sessionId, text, {
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
        this.sessionStore.updateMessage(assistant.id, {
          content: finalBuffer || tokenBuffer,
          streaming: false,
          elapsedMs: performance.now() - startedAt
        });
        this.status.set('idle');
        this.activeSource = null;
        queueMicrotask(() => this.focusInput());
      },
      error: () => {
        const content = tokenBuffer || '[Connection error - please try again]';
        this.sessionStore.updateMessage(assistant.id, {
          content,
          streaming: false,
          error: !tokenBuffer,
          elapsedMs: performance.now() - startedAt
        });
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

  onRagDialogKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    this.closeRagDialog();
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
    const parts = [
      `${usage.totalTokens} total`,
      `${usage.promptTokens} in`,
      `${usage.completionTokens} out`
    ];
    if (usage.promptPerSecond !== undefined) {
      parts.push(`PP ${this.formatTokensPerSecond(usage.promptPerSecond)}`);
    }
    if (usage.predictedPerSecond !== undefined) {
      parts.push(`TG ${this.formatTokensPerSecond(usage.predictedPerSecond)}`);
    }
    return parts.join(' · ');
  }

  formatMessageMeta(message: ChatMessage): string {
    const parts: string[] = [];
    const usage = this.formatUsage(message.usage);
    if (usage) parts.push(usage);
    if (message.elapsedMs !== undefined) {
      parts.push(`time ${this.formatElapsed(message.elapsedMs)}`);
    }
    return parts.join(' · ');
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

  private formatTokensPerSecond(value: number): string {
    return `${value.toFixed(value >= 10 ? 1 : 2)} tok/s`;
  }

  private formatElapsed(milliseconds: number): string {
    const seconds = milliseconds / 1000;
    if (seconds < 10) return `${seconds.toFixed(2)}s`;
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.round(seconds % 60).toString().padStart(2, '0');
    return `${minutes}:${remainingSeconds}`;
  }

  private countWords(value: string): number {
    const text = value.trim();
    if (!text) return 0;
    return text.split(/\s+/).length;
  }
}
