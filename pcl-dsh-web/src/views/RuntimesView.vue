<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import DevPlaceholder from '@/components/DevPlaceholder.vue'
</script>

<template>
  <div class="page page--solid" data-testid="page-runtimes">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">运行时</span>
        <span class="badge badge--blue">开发中</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-secondary" type="button" disabled title="后端尚未实现">刷新版本</button>
        <button class="btn btn-primary" type="button" disabled title="后端尚未实现">安装</button>
      </div>

      <section class="card content-card">
        <p class="card-title">安装 Harness 运行时</p>
        <p class="paragraph">
          从发布列表选择版本，或直接输入完整版本号。已安装版本会保留，实例升级可以单独进行。
        </p>
        <div class="form-field">
          <span class="field-label">Harness 版本</span>
          <input class="input" disabled placeholder="输入完整版本号，或刷新版本列表" />
        </div>
        <div class="toolbar">
          <button class="btn btn-secondary" type="button" disabled>
            <AppIcon name="refresh" :size="15" />刷新版本
          </button>
          <button class="btn btn-primary" type="button" disabled>
            <AppIcon name="download" :size="15" />安装
          </button>
          <span class="small-muted">安装为长任务（≤20 分钟），进度将通过任务事件展示。</span>
        </div>
      </section>

      <DevPlaceholder
        title="本地运行时"
        description="还没有运行时。输入版本并安装后，可在实例设置中选择它。"
        icon="cpu"
        :planned="[
          'GET /api/runtimes 已安装版本与兼容性摘要',
          'POST /api/runtimes 安装（pnpm，20 分钟上限）',
          'POST /api/runtimes/{version}/probe 命令能力探测',
          'DELETE /api/runtimes/{version} 移除（有实例引用时 409）',
        ]"
        :api="[
          'GET /api/runtimes',
          'GET /api/runtimes/available',
          'POST /api/runtimes',
          'DELETE /api/runtimes/{version}',
        ]"
      />
    </div>
  </div>
</template>
