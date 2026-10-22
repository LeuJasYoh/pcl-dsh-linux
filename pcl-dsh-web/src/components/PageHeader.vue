<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import iconUrl from '@/assets/icons/hdsl.png'
import { useAuthStore } from '@/stores/auth'
import { useAppStateStore } from '@/stores/state'
import { shortStatus } from '@/utils/display'

import AppIcon from './AppIcon.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const appState = useAppStateStore()

const isHome = computed(() => route.name === 'home')
const title = computed(
  () => 'PCL-DSH-Linux ' + auth.version + (isHome.value ? '' : '  /  ' + String(route.meta.title ?? '')),
)
const statusText = computed(() => shortStatus(appState.statusText, 160))
const taskActive = computed(() => appState.taskActive)

function goHome(): void {
  if (!isHome.value) void router.push({ name: 'home' })
}

function goAbout(): void {
  void router.push({ name: 'settings' })
}
</script>

<template>
  <header class="page-header" data-testid="page-header">
    <button
      v-if="!isHome"
      class="page-header__home"
      type="button"
      title="返回启动页"
      aria-label="返回启动页"
      data-testid="window-home"
      @click="goHome"
    >
      <AppIcon name="home" :size="19" />
    </button>
    <img v-if="isHome" class="page-header__logo" :src="iconUrl" alt="" />
    <span class="page-header__title" :title="title">{{ title }}</span>
    <div class="page-header__status" data-testid="task-status">
      <span v-if="taskActive" class="task-spinner" aria-hidden="true"></span>
      <span class="page-header__status-text" :title="statusText">{{ statusText }}</span>
    </div>
    <div class="page-header__window-buttons">
      <button
        class="window-button"
        type="button"
        title="关于与许可"
        aria-label="关于与许可"
        data-testid="window-about"
        @click="goAbout"
      >
        ?
      </button>
      <button
        class="window-button"
        type="button"
        disabled
        title="浏览器不支持窗口操作"
        aria-label="最小化（浏览器不支持）"
        data-testid="window-minimize"
      >
        −
      </button>
      <button
        class="window-button"
        type="button"
        disabled
        title="浏览器不支持窗口操作"
        aria-label="最大化（浏览器不支持）"
        data-testid="window-maximize"
      >
        □
      </button>
      <button
        class="window-button"
        type="button"
        disabled
        title="浏览器不支持窗口操作"
        aria-label="关闭（浏览器不支持）"
        data-testid="window-close"
      >
        ×
      </button>
    </div>
  </header>
</template>
