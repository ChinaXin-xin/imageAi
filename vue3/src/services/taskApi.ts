import type {
  DefaultPromptSettings,
  ExtraAccessory,
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

export async function editTaskResult(taskId: string, resultId: number, suggestion: string): Promise<ImageTaskDetail> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/${taskId}/results/${resultId}/edit`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ suggestion }),
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ImageTaskDetail;
}

export async function downloadTaskImages(taskIds: string[]): Promise<{ blob: Blob; fileName: string }> {
  const response = await fetch(`${API_BASE_URL}/api/tasks/download`, {
    method: 'POST',
    headers: {
      Accept: 'application/octet-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ taskIds }),
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return {
    blob: await response.blob(),
    fileName: responseFileName(response.headers.get('content-disposition')) || 'image-task-results.zip',
  };
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

export async function loadExtraAccessories(): Promise<ExtraAccessory[]> {
  const response = await fetch(`${API_BASE_URL}/api/extra-accessories`, {
    headers: {
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ExtraAccessory[];
}

export async function createExtraAccessory(file: File, name?: string): Promise<ExtraAccessory> {
  const formData = new FormData();
  formData.append('file', file);
  if (name?.trim()) {
    formData.append('name', name.trim());
  }
  const response = await fetch(`${API_BASE_URL}/api/extra-accessories`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return (await response.json()) as ExtraAccessory;
}

export async function deleteExtraAccessory(accessoryId: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/extra-accessories/${accessoryId}`, {
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
    templateFiles: File[];
    logoFiles: File[];
    wallpaperFiles: File[];
  },
): Promise<ImageTaskDetail> {
  const formData = new FormData();
  formData.append('payload', JSON.stringify(payload));
  files.realPhotoFiles.forEach((file) => formData.append('realPhotoFiles', file));
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

function responseFileName(contentDisposition: string | null): string {
  if (!contentDisposition) {
    return '';
  }
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1]);
  }
  const asciiMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  return asciiMatch?.[1] ?? '';
}
