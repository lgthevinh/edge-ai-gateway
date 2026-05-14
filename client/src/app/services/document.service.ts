import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';

export interface UploadedDocument {
  title: string;
  description: string;
  updated_at: number;
}

export interface KnowledgeDocumentInput {
  title: string;
  description: string;
  content: string;
}

export interface DocumentSearchResult {
  query: string;
  documents: UploadedDocument[];
}

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly document = inject(DOCUMENT);

  async saveDocument(input: KnowledgeDocumentInput): Promise<UploadedDocument> {
    const fetchFn = this.document.defaultView?.fetch;
    if (!fetchFn) throw new Error('Knowledge editing is only available in the browser.');

    const response = await fetchFn('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as UploadedDocument;
  }

  async searchDocuments(query: string, topK = 5): Promise<DocumentSearchResult> {
    const fetchFn = this.document.defaultView?.fetch;
    if (!fetchFn) throw new Error('Knowledge search is only available in the browser.');

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
}
