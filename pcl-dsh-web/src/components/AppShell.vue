<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'

import { useJobStore } from '@/stores/jobs'
import { useNotifyStore } from '@/stores/notify'
import { POLL_BUSY_MS, POLL_IDLE_MS, useAppStateStore } from '@/stores/state'
import { resolveBackground } from '@/utils/backgrounds'

import FooterBar from './FooterBar.vue'
import PageHeader from './PageHeader.vue'
import SideNav from './SideNav.vue'

const appState = useAppStateStore()
const jobs = useJobStore()
const notify = useNotifyStore()

const backdrop = computed(() => 'url("' + resolveBackground(appState.settings.background) + '")')

// Plan 5.3: idle polling is 1.5s; while a background task runs it degrades to 3s
// because SSE is the source of truth for the task itself.
watch(
  () => jobs.active,
  (active) => {
    appState.setPollInterval(active ? POLL_BUSY_MS : POLL_IDLE_MS)
  },
  { immediate: true },
)

// Central task completion notices + consistency refresh (SSE is authoritative).
watch(
  () => jobs.state,
  (next, previous) => {
    if (next === previous) return
    if (next === 'SUCCEEDED') {
      notify.success('任务完成：' + jobs.typeLabel + '。')
      void appState.refresh()
    } else if (next === 'FAILED') {
      notify.error('任务未完成：' + (jobs.error?.message || jobs.message || jobs.typeLabel))
      void appState.refresh()
    } else if (next === 'CANCELLED') {
      notify.info('任务已取消。')
      void appState.refresh()
    }
  },
)

onMounted(() => {
  appState.startPolling()
  // A page refresh during a long task restores the SSE subscription from localStorage.
  void jobs.restore()
})

onBeforeUnmount(() => {
  appState.stopPolling()
})
</script>

<template>
  <div class="app-shell">
    <div class="shell-backdrop" :style="{ backgroundImage: backdrop }" aria-hidden="true"></div>
    <PageHeader />
    <div class="shell-body">
      <SideNav />
      <main class="shell-main" data-testid="shell-content">
        <div class="shell-content">
          <slot />
        </div>
      </main>
    </div>
    <FooterBar />
  </div>
</template>
