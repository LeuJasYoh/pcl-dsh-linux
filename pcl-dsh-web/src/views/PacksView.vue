<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import AppNotice from '@/components/AppNotice.vue'
import DevPlaceholder from '@/components/DevPlaceholder.vue'
import { useAppStateStore } from '@/stores/state'
import { computed } from 'vue'

const appState = useAppStateStore()
const exportHint = computed(() => {
  const current = appState.currentInstance
  return current
    ? '将「' + current.name + '」的当前 profile 或整个实例导出为 PackForge 整合包。'
    : '创建或选择实例后，即可导出为整合包。'
})
</script>

<template>
  <div class="page page--solid" data-testid="page-packs">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">整合包</span>
        <span class="badge badge--blue">开发中</span>
        <span class="toolbar__spacer"></span>
        <span class="small-muted">.dspack / 兼容快照 ZIP</span>
      </div>

      <AppNotice title="先预览，再导入" tone="info">
        <p class="paragraph">
          导入会读取清单并检查文件路径。带平台依赖的快照可能只能在对应系统运行；请查看预览中的兼容提示。
        </p>
      </AppNotice>
      <AppNotice title="分享包的导出范围" tone="info">
        <p class="paragraph">
          分享包不包含凭据、会话和可能含密钥的 patch 配置；导入后可能需要重新配置。
        </p>
      </AppNotice>

      <div class="card content-card">
        <p class="card-title">导入整合包</p>
        <p class="paragraph">选择 .dspack 或兼容的快照 ZIP，先检查包信息，再导入为新实例。</p>
        <button class="btn btn-primary" type="button" disabled title="后端尚未实现">
          <AppIcon name="download" :size="15" />选择整合包
        </button>
      </div>

      <div class="card content-card">
        <p class="card-title">导出当前实例</p>
        <p class="paragraph">{{ exportHint }}</p>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">
          <AppIcon name="upload" :size="15" />导出 .dspack
        </button>
      </div>

      <DevPlaceholder
        title="整合包导入与导出"
        description="上传检查、导入新实例、导出当前 profile 或整个实例属于 M3 范围，本增量只提供结构占位。"
        icon="box"
        :planned="[
          '上传文件并检查整合包（POST /api/packs/preview，返回 Preview + previewId）',
          '确认导入为新实例（POST /api/packs/import，长任务）',
          '选择范围并导出（POST /api/packs/export，长任务 → artifact 下载）',
          '失败回滚、路径与符号链接安全检查',
        ]"
        :api="['POST /api/packs/preview', 'POST /api/packs/import', 'POST /api/packs/export']"
      />
    </div>
  </div>
</template>
