import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';

export interface UploadedDocument {
  title: string;
  description: string;
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
}

export interface DocumentListResult {
  documents: UploadedDocument[];
}

export interface DocumentSearchResult {
  query: string;
  documents: UploadedDocument[];
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
    const fetchFn = this.getFetch('Knowledge search is only available in the browser.');

    const response = await fetchFn('/api/documents/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, top_k: topK })
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as DocumentSearchResult;
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
