<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'

import { useAppStateStore } from '@/stores/state'
import { resolveBackground } from '@/utils/backgrounds'

import FooterBar from './FooterBar.vue'
import PageHeader from './PageHeader.vue'
import SideNav from './SideNav.vue'

const appState = useAppStateStore()

const backdrop = computed(() => 'url("' + resolveBackground(appState.settings.background) + '")')

onMounted(() => {
  appState.startPolling()
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
