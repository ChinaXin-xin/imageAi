import type { SystemOverview } from '../types/quota';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

export async function loadSystemOverview(): Promise<SystemOverview> {
  const response = await fetch(`${API_BASE_URL}/api/system/overview`, {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`${response.status} ${text || response.statusText}`);
  }

  return (await response.json()) as SystemOverview;
}
