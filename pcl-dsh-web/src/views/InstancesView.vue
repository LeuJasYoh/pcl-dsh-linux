<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { instancesApi } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import AppNotice from '@/components/AppNotice.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useInstancesStore } from '@/stores/instances'
import { useNotifyStore } from '@/stores/notify'
import { useAppStateStore } from '@/stores/state'
import type { InstanceSummary } from '@/api/types'
import { displayPort, isRunningStatus } from '@/utils/display'
import { describeApiError } from '@/utils/errors'

const store = useInstancesStore()
const appState = useAppStateStore()
const notify = useNotifyStore()
const busyId = ref<string | null>(null)

const countLabel = computed(() => String(store.items.length) + ' 个实例')

onMounted(() => {
  void store.load()
})

function installed(instance: InstanceSummary): boolean {
  return appState.runtimes.some((item) => item.installed && item.version === instance.version)
}

function notImplemented(action: string): void {
  notify.info(action + '接口尚未实现（后端后续增量），当前操作不会提交。')
}

async function selectInstance(instance: InstanceSummary): Promise<void> {
  if (busyId.value) return
  busyId.value = instance.id
  try {
    await instancesApi.select(instance.id)
    appState.setCurrentInstanceId(instance.id)
    await appState.refresh()
    notify.success('已切换当前实例。')
  } catch (caught) {
    notify.error(describeApiError(caught, '切换实例'))
  } finally {
    busyId.value = null
  }
}

async function toggleLaunch(instance: InstanceSummary): Promise<void> {
  if (busyId.value) return
  const running = isRunningStatus(instance.status)
  busyId.value = instance.id
  try {
    if (running) await instancesApi.stop(instance.id)
    else await instancesApi.launch(instance.id)
    notify.success(running ? '已发送停止请求。' : '已提交任务，任务详情将在后续增量接入。')
    await appState.refresh()
    await store.load()
  } catch (caught) {
    notify.error(describeApiError(caught, running ? '停止实例' : '启动实例'))
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="page page--solid" data-testid="page-instances">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">{{ countLabel }}</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-primary" type="button" data-testid="instance-create" @click="notImplemented('创建实例')">
          <AppIcon name="plus" :size="15" />创建实例
        </button>
      </div>

      <div v-if="store.loading && !store.loadedOnce" class="card card--flat">
        <div class="loading-row"><span class="spinner"></span>正在读取实例列表…</div>
      </div>

      <AppNotice v-else-if="store.error" title="加载失败" tone="warn">
        <p class="paragraph">{{ store.error }}</p>
        <div class="toolbar">
          <button class="btn btn-secondary btn--small" type="button" @click="store.load()">重试</button>
          <span class="small-muted">
            GET /api/instances{{ store.errorStatus === 404 ? ' 尚未实现' : '' }}
          </span>
        </div>
      </AppNotice>

      <template v-else>
        <EmptyState
          v-if="store.items.length === 0"
          title="还没有实例"
          description="创建独立的配置空间，为它选择 Harness 版本与 profile。"
          icon="layers"
        >
          <button class="btn btn-primary" type="button" @click="notImplemented('创建实例')">
            <AppIcon name="plus" :size="15" />创建实例
          </button>
        </EmptyState>

        <div v-else class="card-list" data-testid="instance-list">
          <div
            v-for="instance in store.items"
            :key="instance.id"
            class="card instance-card"
            :class="{ 'is-selected': instance.id === appState.currentInstanceId }"
            :data-testid="'instance-' + instance.id"
          >
            <div class="instance-card__info">
              <div class="instance-card__heading">
                <span class="instance-card__name" :title="instance.name">{{ instance.name }}</span>
                <StatusBadge :status="instance.status" />
                <span v-if="instance.id === appState.currentInstanceId" class="badge badge--blue">当前</span>
              </div>
              <p class="instance-card__meta">
                Harness {{ instance.version || '—' }} · {{ instance.profile }} · 端口 {{ displayPort(instance) }}
              </p>
            </div>
            <div class="instance-card__actions">
              <button
                v-if="instance.id !== appState.currentInstanceId"
                class="btn btn-secondary"
                type="button"
                :disabled="busyId === instance.id"
                @click="selectInstance(instance)"
              >
                <AppIcon name="check" :size="15" />选择
              </button>
              <button
                class="btn btn-primary"
                type="button"
                :disabled="busyId === instance.id"
                @click="toggleLaunch(instance)"
              >
                <AppIcon :name="isRunningStatus(instance.status) ? 'stop' : 'play'" :size="15" />
                {{ isRunningStatus(instance.status) ? '停止' : installed(instance) ? '启动' : '下载并启动' }}
              </button>
              <button class="btn btn-subtle" type="button" @click="notImplemented('编辑/复制/删除实例')">
                更多
              </button>
            </div>
          </div>
        </div>
      </template>

      <AppNotice title="实例管理 · 后续增量" tone="info">
        <p class="paragraph">
          创建、编辑、复制、删除实例的写接口由后端后续增量实现；当前页面只读取 GET /api/instances，
          不会伪造实例数据。启动/停止/选择按钮会调用接口并在未实现时给出明确提示。
        </p>
      </AppNotice>
    </div>
  </div>
</template>
