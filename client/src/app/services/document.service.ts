import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';

export interface UploadedDocument {
  title: string;
  description: string;
  enabled: boolean;
  updated_at: number;
  distance?: number;
}

export interface KnowledgeDocument extends UploadedDocument {
  content: string;
  created_at: number;
}

export interface KnowledgeDocumentInput {
  title: string;
  description: string;
  content: string;
  enabled?: boolean;
}

export interface RagChunk {
  chunk_id: string;
  title: string;
  source: string;
  content: string;
  created_at: number;
  updated_at: number;
  distance?: number;
}

export interface RagChunkInput {
  chunk_id?: string;
  title: string;
  source: string;
  content: string;
}

export interface DocumentListResult {
  documents: UploadedDocument[];
}

export interface DocumentSearchResult {
  query: string;
  documents: UploadedDocument[];
}

export interface RagChunkListResult {
  chunks: RagChunk[];
}

export interface RagChunkSearchResult {
  query: string;
  chunks: RagChunk[];
}

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly document = inject(DOCUMENT);

  async listDocuments(): Promise<DocumentListResult> {
    const fetchFn = this.getFetch('Knowledge list is only available in the browser.');

    const response = await fetchFn('/api/documents');
    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as DocumentListResult;
  }

  async getDocument(title: string): Promise<KnowledgeDocument> {
    const fetchFn = this.getFetch('Knowledge reading is only available in the browser.');

    const response = await fetchFn(`/api/documents/${encodeURIComponent(title)}`);
    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as KnowledgeDocument;
  }

  async saveDocument(input: KnowledgeDocumentInput): Promise<KnowledgeDocument> {
    const fetchFn = this.getFetch('Knowledge editing is only available in the browser.');

    const response = await fetchFn('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as KnowledgeDocument;
  }

  async deleteDocument(title: string): Promise<void> {
    const fetchFn = this.getFetch('Knowledge deletion is only available in the browser.');

    const response = await fetchFn(`/api/documents/${encodeURIComponent(title)}`, {
      method: 'DELETE'
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
  }

  async searchDocuments(query: string, topK = 5): Promise<DocumentSearchResult> {
    const normalizedQuery = query.trim().toLowerCase();
    const result = await this.listDocuments();
    const documents = normalizedQuery
      ? result.documents.filter((document) =>
          document.title.toLowerCase().includes(normalizedQuery) ||
          document.description.toLowerCase().includes(normalizedQuery)
        )
      : result.documents;
    return { query, documents: documents.slice(0, topK) };
  }

  async listRagChunks(): Promise<RagChunkListResult> {
    const fetchFn = this.getFetch('RAG list is only available in the browser.');

    const response = await fetchFn('/api/rag/chunks');
    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as RagChunkListResult;
  }

  async getRagChunk(chunkId: string): Promise<RagChunk> {
    const fetchFn = this.getFetch('RAG reading is only available in the browser.');

    const response = await fetchFn(`/api/rag/chunks/${encodeURIComponent(chunkId)}`);
    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as RagChunk;
  }

  async saveRagChunk(input: RagChunkInput): Promise<RagChunk> {
    const fetchFn = this.getFetch('RAG editing is only available in the browser.');

    const response = await fetchFn('/api/rag/chunks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as RagChunk;
  }

  async deleteRagChunk(chunkId: string): Promise<void> {
    const fetchFn = this.getFetch('RAG deletion is only available in the browser.');

    const response = await fetchFn(`/api/rag/chunks/${encodeURIComponent(chunkId)}`, {
      method: 'DELETE'
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
  }

  async searchRagChunks(query: string, topK = 5): Promise<RagChunkSearchResult> {
    const fetchFn = this.getFetch('RAG search is only available in the browser.');

    const response = await fetchFn('/api/rag/chunks/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, top_k: topK })
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as RagChunkSearchResult;
  }

  confirm(message: string): boolean {
    return this.document.defaultView?.confirm(message) ?? false;
  }

  private getFetch(errorMessage: string): typeof fetch {
    const fetchFn = this.document.defaultView?.fetch;
    if (!fetchFn) throw new Error(errorMessage);
    return fetchFn.bind(this.document.defaultView);
  }
}
