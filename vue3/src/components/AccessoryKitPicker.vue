<script setup lang="ts">
import { computed, ref } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { ExtraAccessory, ImageTaskKitSpec } from '../types/quota';

const props = defineProps<{
  modelValue: ImageTaskKitSpec[];
  accessories: ExtraAccessory[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: ImageTaskKitSpec[]];
  preview: [src: string | null | undefined, name: string | null | undefined];
  download: [src: string | null | undefined, name: string | null | undefined];
  openAccessories: [visible: boolean];
}>();

const selectedAccessoryId = ref<number | null>(null);
const selectedAccessoryQuantity = ref(1);

const availableAccessories = computed(() => {
  const selectedIds = new Set(
    props.modelValue
      .map((item) => item.accessoryId)
      .filter((id): id is number => typeof id === 'number' && id > 0),
  );
  const selectedNames = new Set(props.modelValue.map((item) => item.name));
  return props.accessories.filter((item) => !selectedIds.has(item.id) && !selectedNames.has(item.name));
});

function selectedAccessory(): ExtraAccessory | null {
  return props.accessories.find((item) => item.id === selectedAccessoryId.value) ?? null;
}

function accessoryForKitSpec(item: ImageTaskKitSpec): ExtraAccessory | null {
  return props.accessories.find((accessory) => accessory.id === item.accessoryId || accessory.name === item.name) ?? null;
}

function updateSpecs(nextSpecs: ImageTaskKitSpec[]) {
  emit('update:modelValue', nextSpecs);
}

function addSelectedAccessoryToKit() {
  const accessory = selectedAccessory();
  if (!accessory) {
    ElMessage.warning('请先选择配件。');
    return;
  }
  if (props.modelValue.some((item) => item.accessoryId === accessory.id || item.name === accessory.name)) {
    ElMessage.warning('该配件已经添加到套餐规格。');
    return;
  }
  updateSpecs([
    ...props.modelValue,
    {
      accessoryId: accessory.id,
      name: accessory.name,
      quantity: Math.max(1, selectedAccessoryQuantity.value),
    },
  ]);
  selectedAccessoryId.value = null;
  selectedAccessoryQuantity.value = 1;
}

function updateQuantity(index: number, delta: number) {
  updateSpecs(
    props.modelValue.map((item, itemIndex) =>
      itemIndex === index
        ? { ...item, quantity: Math.max(1, Number(item.quantity) + delta) }
        : item,
    ),
  );
}

function removeKitSpec(index: number) {
  updateSpecs(props.modelValue.filter((_, itemIndex) => itemIndex !== index));
}
</script>

<template>
  <div class="accessory-picker">
    <el-select
      v-model="selectedAccessoryId"
      filterable
      clearable
      placeholder="选择额外配件"
      @visible-change="emit('openAccessories', $event)"
    >
      <el-option
        v-for="accessory in availableAccessories"
        :key="accessory.id"
        :label="accessory.name"
        :value="accessory.id"
      >
        <div class="template-option">
          <img :src="accessory.preview" :alt="accessory.name" />
          <div>
            <strong>{{ accessory.name }}</strong>
            <small>{{ accessory.fileName }}</small>
          </div>
        </div>
      </el-option>
    </el-select>
    <el-input-number v-model="selectedAccessoryQuantity" :min="1" :max="99" />
    <el-button type="primary" :icon="Plus" @click="addSelectedAccessoryToKit">添加配件</el-button>
  </div>
  <p v-if="!accessories.length" class="field-hint">暂无额外配件，请先到“排版模板”页下方添加。</p>

  <div v-if="modelValue.length" class="kit-grid">
    <div v-for="(item, index) in modelValue" :key="item.accessoryId || item.name" class="kit-item">
      <button
        v-if="accessoryForKitSpec(item)"
        class="kit-thumb image-action-wrap"
        type="button"
        @click="emit('preview', accessoryForKitSpec(item)?.preview, accessoryForKitSpec(item)?.fileName || item.name)"
      >
        <img :src="accessoryForKitSpec(item)?.preview" :alt="item.name" />
        <span class="sr-only">查看配件图片</span>
      </button>
      <span>{{ item.name }}</span>
      <div class="stepper">
        <button type="button" @click="updateQuantity(index, -1)">-</button>
        <strong>{{ item.quantity }}</strong>
        <button type="button" @click="updateQuantity(index, 1)">+</button>
      </div>
      <button class="kit-remove-button" type="button" @click="removeKitSpec(index)">删除</button>
    </div>
  </div>
  <el-empty v-else class="compact-empty" description="未选择配件" />
</template>
