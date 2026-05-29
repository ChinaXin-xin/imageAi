import type { CodexQuotaAccount } from '../types/quota';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

async function apiFetch<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`${response.status} ${text || response.statusText}`);
  }

  return (await response.json()) as T;
}

export function loadCodexQuotaAccounts(): Promise<CodexQuotaAccount[]> {
  return apiFetch<CodexQuotaAccount[]>('/api/codex/quota/accounts');
}
