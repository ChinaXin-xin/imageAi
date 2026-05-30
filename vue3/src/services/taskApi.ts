import type { DefaultPromptSettings, UploadImageAnalysis } from '../types/quota';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

async function readError(response: Response): Promise<string> {
  const text = await response.text().catch(() => '');
  return `${response.status} ${text || response.statusText}`;
}

export async function loadDefaultPromptSettings(): Promise<DefaultPromptSettings> {
  const response = await fetch(`${API_BASE_URL}/api/default-settings/prompts`, {
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as DefaultPromptSettings;
}

export async function saveDefaultPromptSettings(settings: DefaultPromptSettings): Promise<DefaultPromptSettings> {
  const response = await fetch(`${API_BASE_URL}/api/default-settings/prompts`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(settings),
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as DefaultPromptSettings;
}

export async function analyzeUploadedImages(type: string, files: File[], prompt: string): Promise<UploadImageAnalysis> {
  const formData = new FormData();
  formData.append('type', type);
  formData.append('prompt', prompt);
  files.forEach((file) => formData.append('files', file));

  const response = await fetch(`${API_BASE_URL}/api/task/analyze-upload`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as UploadImageAnalysis;
}
