<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { instancesApi } from '@/api'
import HomeLaunchPane from '@/components/HomeLaunchPane.vue'
import HomeTaskCard from '@/components/HomeTaskCard.vue'
import { useNotifyStore } from '@/stores/notify'
import { useAppStateStore } from '@/stores/state'
import { resolveBackground } from '@/utils/backgrounds'
import { isRunningStatus } from '@/utils/display'
import { describeApiError } from '@/utils/errors'

const router = useRouter()
const appState = useAppStateStore()
const notify = useNotifyStore()

const busy = ref(false)
const taskStartedAt = ref<number | null>(null)
const homeMessage = ref('')

const background = computed(() => 'url("' + resolveBackground(appState.settings.background) + '")')
const current = computed(() => appState.currentInstance)
const running = computed(() => current.value !== null && isRunningStatus(current.value.status))
const runtimeInstalled = computed(
  () =>
    current.value !== null &&
    appState.runtimes.some((item) => item.installed && item.version === current.value?.version),
)
const firstLaunchHint = computed(() => current.value !== null && !running.value && !runtimeInstalled.value)
const taskVisible = computed(() => appState.taskActive || busy.value)
const taskPhase = computed(() => {
  if (appState.taskActive) return appState.taskPhase
  return busy.value ? '正在提交启动任务…' : ''
})

const launchLabel = computed(() => {
  if (!current.value) return '创建实例'
  if (running.value) return '打开 Web 界面'
  return runtimeInstalled.value ? '启动实例' : '下载并启动'
})
const launchSub = computed(() => current.value?.name ?? '')

watch(
  () => appState.taskActive,
  (active) => {
    if (active && taskStartedAt.value === null) taskStartedAt.value = Date.now()
    if (!active && !busy.value) taskStartedAt.value = null
  },
)

function goLogs(): void {
  void router.push({ name: 'logs' })
}

async function handleLaunch(): Promise<void> {
  if (busy.value) return
  homeMessage.value = ''
  if (!current.value) {
    notify.info('还没有实例。创建实例接口将在后续增量提供（M2 F-5）。')
    await router.push({ name: 'instances' })
    return
  }
  if (running.value) {
    await openWeb()
    return
  }
  busy.value = true
  taskStartedAt.value = Date.now()
  try {
    const job = await instancesApi.launch(current.value.id)
    const jobId = job.jobId ?? job.id
    notify.success(
      jobId
        ? '已提交启动任务（' + jobId + '），任务详情将在后续增量接入。'
        : '已提交启动任务，任务详情将在后续增量接入。',
    )
    await appState.refresh()
  } catch (caught) {
    homeMessage.value = describeApiError(caught, '启动实例')
    notify.error(homeMessage.value)
  } finally {
    busy.value = false
    if (!appState.taskActive) taskStartedAt.value = null
  }
}

async function openWeb(): Promise<void> {
  if (!current.value || busy.value) return
  busy.value = true
  homeMessage.value = ''
  try {
    const result = await instancesApi.webUrl(current.value.id)
    const opened = result.url ? window.open(result.url, '_blank', 'noopener') : null
    if (!opened) notify.info('浏览器阻止了新窗口，或后端没有返回工作台地址。')
  } catch (caught) {
    homeMessage.value = describeApiError(caught, '打开工作台')
    notify.error(homeMessage.value)
  } finally {
    busy.value = false
  }
}

async function stopInstance(): Promise<void> {
  if (!current.value || busy.value) return
  busy.value = true
  homeMessage.value = ''
  try {
    await instancesApi.stop(current.value.id)
    notify.success('已发送停止请求。')
    await appState.refresh()
  } catch (caught) {
    homeMessage.value = describeApiError(caught, '停止实例')
    notify.error(homeMessage.value)
  } finally {
    busy.value = false
  }
}

async function selectInstance(id: string): Promise<void> {
  if (id === appState.currentInstanceId) return
  try {
    await instancesApi.select(id)
    appState.setCurrentInstanceId(id)
    await appState.refresh()
    notify.success('已切换当前实例。')
  } catch (caught) {
    notify.error(describeApiError(caught, '切换实例'))
  }
}
</script>

<template>
  <div class="home-page" data-testid="page-home" :style="{ backgroundImage: background }">
    <div class="home-page__shade" aria-hidden="true"></div>

    <div v-if="taskVisible" class="home-task-slot">
      <HomeTaskCard :phase="taskPhase" :started-at="taskStartedAt" @view="goLogs" />
    </div>

    <div v-if="firstLaunchHint" class="first-launch-note card content-card" data-testid="first-launch-hint">
      <p>首次启动将下载 Harness 和依赖。</p>
      <p>下载阶段和已用时会显示在这里，可查看任务或取消。</p>
    </div>

    <button
      v-if="running"
      class="btn btn-secondary home-stop"
      type="button"
      :disabled="busy || appState.taskActive"
      @click="stopInstance"
    >
      停止
    </button>

    <div class="home-launch">
      <HomeLaunchPane
        :instances="appState.instances"
        :current-id="appState.currentInstanceId"
        :launch-label="launchLabel"
        :launch-sub="launchSub"
        :busy="busy || appState.taskActive"
        @launch="handleLaunch"
        @select="selectInstance"
      />
    </div>

    <div v-if="appState.error" class="home-message card content-card" role="alert">
      <p class="paragraph">{{ appState.error }}</p>
      <div class="toolbar">
        <button class="btn btn-secondary btn--small" type="button" @click="appState.refresh()">
          重试读取工作空间
        </button>
      </div>
    </div>
    <div v-else-if="homeMessage" class="home-message card content-card" role="alert">
      <p class="paragraph">{{ homeMessage }}</p>
    </div>

    <p v-if="!current && !appState.loading" class="first-launch-note">
      当前工作空间还没有实例。先创建实例，再回到这里启动。
    </p>
  </div>
</template>
