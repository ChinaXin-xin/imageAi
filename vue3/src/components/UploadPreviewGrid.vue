<script setup lang="ts">
import { Download } from '@element-plus/icons-vue';
import type { UploadUserFile } from 'element-plus';

defineProps<{
  files: UploadUserFile[];
  filePreviewUrl: (file: UploadUserFile) => string;
  uploadKey: (file: UploadUserFile) => string;
  compact?: boolean;
  removeLabel?: string;
}>();

const emit = defineEmits<{
  preview: [src: string, name: string];
  download: [src: string, name: string];
  remove: [file: UploadUserFile];
}>();
</script>

<template>
  <div v-if="files.length" class="preview-grid" :class="{ 'compact-preview-grid': compact }">
    <div v-for="file in files" :key="uploadKey(file)" class="preview-tile">
      <div
        class="image-action-wrap"
        role="button"
        tabindex="0"
        @click="emit('preview', filePreviewUrl(file), file.name)"
        @keydown.enter="emit('preview', filePreviewUrl(file), file.name)"
      >
        <img :src="filePreviewUrl(file)" :alt="file.name" />
        <button
          class="image-download-button"
          type="button"
          title="下载图片"
          @click.stop="emit('download', filePreviewUrl(file), file.name)"
        >
          <el-icon><Download /></el-icon>
        </button>
      </div>
      <span>{{ file.name }}</span>
      <button class="remove-upload-button" type="button" @click="emit('remove', file)">
        {{ removeLabel || '移除' }}
      </button>
    </div>
  </div>
</template>
