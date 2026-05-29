<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  CircleCheck,
  DataAnalysis,
  Expand,
  Fold,
  Monitor,
  Refresh,
  Warning,
} from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import appIcon from './assets/imageai-icon.png';
import { loadCodexQuotaAccounts } from './services/codexQuotaApi';
import type { CodexQuotaAccount, DashboardStats } from './types/quota';

const accounts = ref<CodexQuotaAccount[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const lastRefreshAt = ref('');
const isCollapsed = ref(false);

const stats = computed<DashboardStats>(() => {
  const fiveHourValues = accounts.value
    .map((account) => account.fiveHour.remainingPercent)
    .filter((value): value is number => typeof value === 'number');
  const weeklyValues = accounts.value
    .map((account) => account.weekly.remainingPercent)
    .filter((value): value is number => typeof value === 'number');

  return {
    totalAccounts: accounts.value.length,
    activeAccounts: accounts.value.filter((account) => account.status === 'active').length,
    fiveHourImages: accounts.value.reduce((sum, account) => sum + (account.fiveHourImages ?? 0), 0),
    weeklyImages: accounts.value.reduce((sum, account) => sum + (account.weeklyImages ?? 0), 0),
    averageFiveHourPercent: average(fiveHourValues),
    averageWeeklyPercent: average(weeklyValues),
  };
});

function average(values: number[]): number | null {
  if (values.length === 0) return null;
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length);
}

function formatPercent(value: number | null): string {
  return typeof value === 'number' ? `${Math.round(value)}%` : '--';
}

function formatNumber(value: number | null): string {
  return typeof value === 'number' ? value.toLocaleString() : '--';
}

function progressStatus(value: number | null): '' | 'success' | 'warning' | 'exception' {
  if (value === null) return '';
  if (value >= 70) return 'success';
  if (value >= 30) return 'warning';
  return 'exception';
}

function statusTagType(status: CodexQuotaAccount['status']): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'active') return 'success';
  if (status === 'disabled') return 'info';
  if (status === 'unavailable') return 'warning';
  return 'danger';
}

async function refreshQuota() {
  loading.value = true;
  errorMessage.value = '';

  try {
    accounts.value = await loadCodexQuotaAccounts();
    lastRefreshAt.value = new Date().toLocaleString();
    if (accounts.value.length === 0) {
      ElMessage.warning('未发现 Codex 凭据文件。');
    } else {
      ElMessage.success('额度数据已刷新。');
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

onMounted(refreshQuota);
</script>

<template>
  <el-config-provider>
    <el-container class="app-layout">
      <el-aside :width="isCollapsed ? '76px' : '236px'" class="sidebar">
        <div class="brand-block">
          <img class="brand-icon" :src="appIcon" alt="ImageAI" />
          <div v-if="!isCollapsed" class="brand-copy">
            <strong>ImageAI</strong>
            <span>额度监控台</span>
          </div>
        </div>

        <el-menu
          class="nav-menu"
          default-active="codex-quota"
          :collapse="isCollapsed"
          background-color="transparent"
          text-color="#bfcbda"
          active-text-color="#ffffff"
        >
          <el-menu-item index="codex-quota">
            <el-icon><Monitor /></el-icon>
            <template #title>Codex 额度</template>
          </el-menu-item>
        </el-menu>

        <button class="collapse-button" type="button" @click="isCollapsed = !isCollapsed">
          <el-icon>
            <Expand v-if="isCollapsed" />
            <Fold v-else />
          </el-icon>
          <span v-if="!isCollapsed">折叠菜单</span>
        </button>
      </el-aside>

      <el-container>
        <el-header class="topbar">
          <div>
            <p class="eyebrow">CLI Proxy API Management</p>
            <h1>Codex 额度监控</h1>
          </div>
          <div class="topbar-actions">
            <span v-if="lastRefreshAt" class="refresh-time">刷新于 {{ lastRefreshAt }}</span>
            <el-button :icon="Refresh" type="primary" :loading="loading" @click="refreshQuota">
              刷新
            </el-button>
          </div>
        </el-header>

        <el-main class="content-wrap">
          <el-alert
            v-if="errorMessage"
            class="alert-block"
            type="error"
            :title="errorMessage"
            show-icon
            :closable="false"
          />

          <el-row :gutter="16" class="stat-grid">
            <el-col :xs="24" :sm="12" :lg="6">
              <el-card shadow="never" class="stat-card">
                <el-statistic title="Codex 账号" :value="stats.totalAccounts">
                  <template #prefix>
                    <el-icon><Monitor /></el-icon>
                  </template>
                </el-statistic>
                <span class="stat-note">{{ stats.activeAccounts }} 个正常</span>
              </el-card>
            </el-col>
            <el-col :xs="24" :sm="12" :lg="6">
              <el-card shadow="never" class="stat-card">
                <el-statistic title="平均 5 小时额度" :value="formatPercent(stats.averageFiveHourPercent)" />
                <span class="stat-note">可生成 {{ stats.fiveHourImages.toLocaleString() }} 张图</span>
              </el-card>
            </el-col>
            <el-col :xs="24" :sm="12" :lg="6">
              <el-card shadow="never" class="stat-card">
                <el-statistic title="平均每周额度" :value="formatPercent(stats.averageWeeklyPercent)" />
                <span class="stat-note">可生成 {{ stats.weeklyImages.toLocaleString() }} 张图</span>
              </el-card>
            </el-col>
            <el-col :xs="24" :sm="12" :lg="6">
              <el-card shadow="never" class="stat-card">
                <el-statistic title="查询状态" :value="loading ? '刷新中' : '已就绪'">
                  <template #prefix>
                    <el-icon>
                      <DataAnalysis v-if="!errorMessage" />
                      <Warning v-else />
                    </el-icon>
                  </template>
                </el-statistic>
                <span class="stat-note">
                  {{ errorMessage ? '需要检查后端接口或管理配置' : '通过后端代理实时查询' }}
                </span>
              </el-card>
            </el-col>
          </el-row>

          <el-skeleton v-if="loading && accounts.length === 0" :rows="8" animated class="quota-skeleton" />

          <el-empty
            v-else-if="!loading && accounts.length === 0 && !errorMessage"
            description="暂无 Codex 账号额度数据"
          />

          <el-row v-else :gutter="16" class="account-grid">
            <el-col v-for="account in accounts" :key="account.id" :xs="24" :lg="12" :xl="8">
              <el-card shadow="hover" class="account-card">
                <template #header>
                  <div class="account-header">
                    <div class="account-title">
                      <el-icon class="account-icon"><CircleCheck /></el-icon>
                      <div>
                        <h2>{{ account.name }}</h2>
                        <p>{{ account.fileName }}</p>
                      </div>
                    </div>
                    <el-tag :type="statusTagType(account.status)" effect="light">
                      {{ account.statusText }}
                    </el-tag>
                  </div>
                </template>

                <el-alert
                  v-if="account.error"
                  class="account-error"
                  type="error"
                  :title="account.error"
                  show-icon
                  :closable="false"
                />

                <div class="meta-row">
                  <span>套餐</span>
                  <strong>{{ account.planType || '未知' }}</strong>
                </div>

                <div class="quota-block">
                  <div class="quota-head">
                    <div>
                      <span class="quota-label">5 小时额度</span>
                      <strong>{{ formatPercent(account.fiveHour.remainingPercent) }}</strong>
                    </div>
                    <div class="image-count">{{ formatNumber(account.fiveHourImages) }} 张</div>
                  </div>
                  <el-progress
                    :percentage="account.fiveHour.remainingPercent ?? 0"
                    :status="progressStatus(account.fiveHour.remainingPercent)"
                    :stroke-width="14"
                    striped
                    striped-flow
                  />
                  <div class="quota-foot">
                    <span>已用 {{ formatPercent(account.fiveHour.usedPercent) }}</span>
                    <span>重置 {{ account.fiveHour.resetLabel }}</span>
                  </div>
                </div>

                <div class="quota-block">
                  <div class="quota-head">
                    <div>
                      <span class="quota-label">每周额度</span>
                      <strong>{{ formatPercent(account.weekly.remainingPercent) }}</strong>
                    </div>
                    <div class="image-count">{{ formatNumber(account.weeklyImages) }} 张</div>
                  </div>
                  <el-progress
                    :percentage="account.weekly.remainingPercent ?? 0"
                    :status="progressStatus(account.weekly.remainingPercent)"
                    :stroke-width="14"
                    striped
                    striped-flow
                  />
                  <div class="quota-foot">
                    <span>已用 {{ formatPercent(account.weekly.usedPercent) }}</span>
                    <span>重置 {{ account.weekly.resetLabel }}</span>
                  </div>
                </div>

                <div class="card-footer">
                  <span>最后刷新时间</span>
                  <strong>{{ account.lastRefreshTime }}</strong>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
