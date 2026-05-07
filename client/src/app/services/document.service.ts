import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';

export interface UploadedDocument {
  document_id: string;
  path: string;
  title: string;
  description: string;
  updated_at: number;
}

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly document = inject(DOCUMENT);

  async uploadTextFile(file: File): Promise<UploadedDocument> {
    const fetchFn = this.document.defaultView?.fetch;
    if (!fetchFn) throw new Error('File upload is only available in the browser.');

    const response = await fetchFn('/api/documents/upload', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        file_name: file.name,
        content: await file.text()
      })
    });

    if (!response.ok) {
      throw new Error(await response.text());
    }
    return await response.json() as UploadedDocument;
  }
}
