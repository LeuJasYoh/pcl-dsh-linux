<script setup lang="ts">
import AppNotice from '@/components/AppNotice.vue'
import DevPlaceholder from '@/components/DevPlaceholder.vue'
import { useAppStateStore } from '@/stores/state'
import { computed } from 'vue'

const appState = useAppStateStore()
const profileLabel = computed(() => {
  const current = appState.currentInstance
  return current ? '当前 profile · ' + current.profile : '尚未选择实例'
})
</script>

<template>
  <div class="page page--solid" data-testid="page-plugins">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">已装插件</span>
        <span class="badge badge--blue">开发中</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">刷新</button>
        <button class="btn btn-primary" type="button" disabled title="后端尚未实现">安装插件</button>
      </div>

      <AppNotice title="插件与 profile" tone="info">
        <p class="paragraph">
          {{ profileLabel }}。插件由当前实例的 Harness 管理；可指定完整 npm 包名和版本，安装前请确认来源。
        </p>
      </AppNotice>

      <DevPlaceholder
        title="暂无插件记录"
        description="点击刷新读取当前实例，或安装一个插件。插件页属于 M3 范围，本增量只提供结构占位。"
        icon="puzzle"
        :planned="[
          '读取当前实例插件列表（GET /api/instances/{id}/plugins）',
          '安装/更新/移除插件（长任务，202 + SSE 进度）',
          '社区插件目录搜索与安装目标检查（/api/plugin-catalog）',
          '插件名与版本校验、安装目标确认',
        ]"
        :api="[
          'GET /api/instances/{id}/plugins',
          'POST /api/instances/{id}/plugins',
          'PATCH /api/instances/{id}/plugins/{name}',
          'DELETE /api/instances/{id}/plugins/{name}',
        ]"
      />
    </div>
  </div>
</template>
