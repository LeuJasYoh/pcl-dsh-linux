<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { elapsedText } from '@/utils/display'

const props = defineProps<{
  phase: string
  startedAt: number | null
}>()

const emit = defineEmits<{
  view: []
}>()

const now = ref(Date.now())
let timer: number | null = null

const elapsed = computed(() => elapsedText(props.startedAt, now.value))

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <div
    class="card home-task-card"
    data-testid="home-task-panel"
    title="任务计数（运行时/依赖/插件/文件）由任务事件接入后提供"
  >
    <div class="task-heading">
      <span>任务进行中</span>
      <span class="task-elapsed" data-testid="home-task-elapsed">{{ elapsed }}</span>
    </div>
    <div class="task-body">
      <span class="task-spinner" aria-hidden="true"></span>
      <span class="task-phase" data-testid="home-task-phase">{{ phase || '正在准备任务…' }}</span>
      <button class="btn btn-secondary btn--small" type="button" data-testid="home-view-task" @click="emit('view')">
        查看任务
      </button>
    </div>
    <div class="task-counts" aria-label="任务计数占位">
      <span class="task-count">运行时<strong>—</strong></span>
      <span class="task-count">依赖<strong>—</strong></span>
      <span class="task-count">插件<strong>—</strong></span>
      <span class="task-count">文件<strong>—</strong></span>
    </div>
  </div>
</template>
