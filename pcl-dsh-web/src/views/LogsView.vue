<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import DevPlaceholder from '@/components/DevPlaceholder.vue'

const placeholderLines = [
  '这里会显示安装、兼容性检测和实例运行日志。',
  '',
  '实时日志与任务事件将在 M2 后续增量接入：',
  '  GET  /api/logs/tail?lines=800',
  '  GET  /api/jobs/{id}/events  (text/event-stream)',
  '  POST /api/jobs/{id}/cancel',
  '',
  '在接口就绪前，本页不会伪造任务状态或日志内容。',
]
</script>

<template>
  <div class="page page--solid" data-testid="page-logs">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">当前任务输出</span>
        <span class="badge badge--blue">开发中</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">
          <AppIcon name="stop" :size="15" />取消当前任务
        </button>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">
          <AppIcon name="copy" :size="15" />复制日志
        </button>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">
          <AppIcon name="refresh" :size="15" />刷新日志
        </button>
      </div>

      <div class="log-area" data-testid="log-placeholder" role="log" aria-label="日志占位区">{{ placeholderLines.join('\n') }}</div>

      <DevPlaceholder
        title="实时日志与任务"
        description="任务列表、SSE 实时日志、取消与复制回退属于 M2 后续增量，本增量只提供结构与占位。"
        icon="terminal"
        :planned="[
          '任务历史与状态（GET /api/jobs、/api/jobs/{id}）',
          'SSE 事件流（state / log / result / error，15s 心跳，Last-Event-ID 续传）',
          '取消当前任务（POST /api/jobs/{id}/cancel）',
          '复制日志（navigator.clipboard 失败时回退到只读文本域自动全选）',
        ]"
        :api="['GET /api/logs/tail', 'GET /api/jobs', 'GET /api/jobs/{id}/events']"
      />
    </div>
  </div>
</template>
