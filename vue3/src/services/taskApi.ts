import type {
  DefaultPromptSettings,
  ImageTaskDetail,
  ImageTaskPayload,
  ImageTaskSummary,
  TargetTemplate,
  TargetTemplateType,
  UploadImageAnalysis,
} from '../types/quota';

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

export async function loadImageTasks(): Promise<ImageTaskSummary[]> {
  const response = await fetch(`${API_BASE_URL}/api/tasks`, {
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskSummary[];
}

export async function loadImageTask(taskId: string): Promise<ImageTaskDetail> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}`, {
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}

export async function retryImageTask(taskId: string): Promise<ImageTaskDetail> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}/retry`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}

export async function pauseImageTask(taskId: string): Promise<ImageTaskDetail> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}/pause`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}

export async function resumeImageTask(taskId: string): Promise<ImageTaskDetail> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}/resume`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}

export async function deleteImageTask(taskId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
}

export async function loadTargetTemplates(): Promise<TargetTemplate[]> {
  const response = await fetch(`${API_BASE_URL}/api/target-templates`, {
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as TargetTemplate[];
}

export async function createTargetTemplate(
  templateType: TargetTemplateType,
  file: File,
  name?: string,
): Promise<TargetTemplate> {
  const formData = new FormData();
  formData.append('templateType', templateType);
  formData.append('file', file);
  if (name?.trim()) {
    formData.append('name', name.trim());
  }
  const response = await fetch(`${API_BASE_URL}/api/target-templates`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as TargetTemplate;
}

export async function deleteTargetTemplate(templateId: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/target-templates/${templateId}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
}

export async function createImageTask(
  payload: ImageTaskPayload,
  files: {
    realPhotoFiles: File[];
    packageImageFiles: File[];
    templateFiles: File[];
    logoFiles: File[];
    wallpaperFiles: File[];
  },
): Promise<ImageTaskDetail> {
  const formData = new FormData();
  formData.append('payload', JSON.stringify(payload));
  files.realPhotoFiles.forEach((file) => formData.append('realPhotoFiles', file));
  files.packageImageFiles.forEach((file) => formData.append('packageImageFiles', file));
  files.templateFiles.forEach((file) => formData.append('templateFiles', file));
  files.logoFiles.forEach((file) => formData.append('logoFiles', file));
  files.wallpaperFiles.forEach((file) => formData.append('wallpaperFiles', file));

  const response = await fetch(`${API_BASE_URL}/api/tasks`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}
