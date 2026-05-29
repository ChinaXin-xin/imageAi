<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  CircleCheck,
  Expand,
  Fold,
  Monitor,
  Refresh,
} from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import appIcon from './assets/imageai-icon.png';
import { loadCodexQuotaAccounts } from './services/codexQuotaApi';
import { loadSystemOverview } from './services/systemApi';
import type { CodexQuotaAccount, DashboardStats, SystemOverview } from './types/quota';

const accounts = ref<CodexQuotaAccount[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const lastRefreshAt = ref('');
const isCollapsed = ref(false);
const systemOverview = ref<SystemOverview | null>(null);

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

function progressColor(value: number | null): string {
  if (value === null) return '#cbd5e1';
  if (value >= 70) return '#2563eb';
  if (value >= 30) return '#d97706';
  return '#dc2626';
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
    const [quotaResult, systemResult] = await Promise.allSettled([
      loadCodexQuotaAccounts(),
      loadSystemOverview(),
    ]);

    if (quotaResult.status === 'fulfilled') {
      accounts.value = quotaResult.value;
    } else {
      throw quotaResult.reason;
    }

    if (systemResult.status === 'fulfilled') {
      systemOverview.value = systemResult.value;
    }

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

function formatBytes(value: number | null | undefined): string {
  if (!value || value <= 0) return '--';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size >= 10 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`;
}

function formatMetricPercent(value: number | null | undefined): string {
  return typeof value === 'number' ? `${value.toFixed(value % 1 === 0 ? 0 : 1)}%` : '--';
}

onMounted(refreshQuota);
</script>

<template>
  <el-config-provider>
    <el-container class="app-layout">
      <el-aside :width="isCollapsed ? '68px' : '292px'" class="sidebar" :class="{ collapsed: isCollapsed }">
        <div class="brand-block">
          <div class="brand-main">
            <img class="brand-icon" :src="appIcon" alt="ImageAI" />
            <div v-if="!isCollapsed" class="brand-copy">
              <strong>ImageAI</strong>
            </div>
          </div>
          <button class="collapse-button" type="button" title="折叠菜单" @click="isCollapsed = !isCollapsed">
            <el-icon>
              <Expand v-if="isCollapsed" />
              <Fold v-else />
            </el-icon>
          </button>
        </div>

        <div v-if="!isCollapsed" class="sidebar-section-label">工作台</div>

        <div class="nav-list">
          <button class="nav-item active" type="button">
            <el-icon><Monitor /></el-icon>
            <span v-if="!isCollapsed">Codex 额度</span>
          </button>
        </div>

        <div v-if="!isCollapsed" class="sidebar-footer">
          <span>系统状态</span>
          <strong>{{ formatMetricPercent(systemOverview?.cpuUsagePercent) }} CPU</strong>
        </div>
      </el-aside>

      <el-container class="main-area">
        <el-header class="topbar">
          <div class="title-block">
            <p class="eyebrow">CLI Proxy API Management</p>
            <h1>Codex 额度监控</h1>
            <p class="page-subtitle">集中查看账号额度、图片生成余量与服务器资源状态。</p>
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

          <section class="summary-grid">
            <article class="summary-card">
              <span class="summary-label">Codex 账号</span>
              <strong>{{ stats.totalAccounts }}</strong>
              <small>{{ stats.activeAccounts }} 个正常</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">平均 5 小时额度</span>
              <strong>{{ formatPercent(stats.averageFiveHourPercent) }}</strong>
              <small>可生成 {{ stats.fiveHourImages.toLocaleString() }} 张图</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">平均每周额度</span>
              <strong>{{ formatPercent(stats.averageWeeklyPercent) }}</strong>
              <small>可生成 {{ stats.weeklyImages.toLocaleString() }} 张图</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">系统版本</span>
              <strong>{{ systemOverview?.appVersion || '--' }}</strong>
              <small>{{ systemOverview?.systemVersion || '等待后端返回' }}</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">CPU 占比</span>
              <strong>{{ formatMetricPercent(systemOverview?.cpuUsagePercent) }}</strong>
              <small>{{ errorMessage ? '接口异常' : '服务器实时负载' }}</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">内存信息</span>
              <strong>{{ formatMetricPercent(systemOverview?.memoryUsagePercent) }}</strong>
              <small>
                {{ formatBytes(systemOverview?.memoryUsedBytes) }} /
                {{ formatBytes(systemOverview?.memoryTotalBytes) }}
              </small>
            </article>
            <article class="summary-card">
              <span class="summary-label">外存信息</span>
              <strong>{{ formatMetricPercent(systemOverview?.diskUsagePercent) }}</strong>
              <small>
                {{ formatBytes(systemOverview?.diskUsedBytes) }} /
                {{ formatBytes(systemOverview?.diskTotalBytes) }}
              </small>
            </article>
            <article class="summary-card">
              <span class="summary-label">查询状态</span>
              <strong>{{ loading ? '刷新中' : '已就绪' }}</strong>
              <small>{{ errorMessage ? '需要检查后端接口' : '通过后端代理实时查询' }}</small>
            </article>
          </section>

          <el-skeleton v-if="loading && accounts.length === 0" :rows="8" animated class="quota-skeleton" />

          <el-empty
            v-else-if="!loading && accounts.length === 0 && !errorMessage"
            description="暂无 Codex 账号额度数据"
          />

          <section v-else class="accounts-panel">
            <div class="panel-head">
              <div>
                <h2>账号额度</h2>
                <p>每 5 小时 1% 约等于 1 张图，每周 1% 约等于 8 张图。</p>
              </div>
              <span>{{ accounts.length }} 个账号</span>
            </div>
            <div class="account-scroll">
              <div class="account-grid">
                <div v-for="account in accounts" :key="account.id" class="account-cell">
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
                    :color="progressColor(account.fiveHour.remainingPercent)"
                    :stroke-width="8"
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
                    :color="progressColor(account.weekly.remainingPercent)"
                    :stroke-width="8"
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
                </div>
              </div>
            </div>
          </section>
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
