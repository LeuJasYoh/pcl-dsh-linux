<script setup lang="ts">
import { computed, onMounted } from 'vue'

import AppNotice from '@/components/AppNotice.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore } from '@/stores/settings'
import { BACKGROUNDS, backgroundTitle } from '@/utils/backgrounds'

const store = useSettingsStore()
const auth = useAuthStore()
const notify = useNotifyStore()

const settings = computed(() => store.settings)
const proxyValue = computed(() => settings.value?.proxy || '（未设置 · 直连）')
const registryValue = computed(() => settings.value?.registry || '（默认软件源）')
const backgroundValue = computed(() => backgroundTitle(settings.value?.background))

onMounted(() => {
  void store.load()
})

function notImplemented(action: string): void {
  notify.info(action + '将在后续增量提供（F-9），当前为只读展示。')
}
</script>

<template>
  <div class="page page--solid" data-testid="page-settings">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">设置</span>
        <span class="badge badge--gray">只读预览</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-primary" type="button" @click="notImplemented('保存设置')">保存设置</button>
      </div>

      <div v-if="store.loading && !settings" class="card card--flat">
        <div class="loading-row"><span class="spinner"></span>正在读取设置…</div>
      </div>

      <AppNotice v-else-if="store.error" title="加载失败" tone="warn">
        <p class="paragraph">{{ store.error }}</p>
        <div class="toolbar">
          <button class="btn btn-secondary btn--small" type="button" @click="store.load()">重试</button>
          <span class="small-muted">
            GET /api/settings{{ store.errorStatus === 404 ? ' 尚未实现' : '' }}
          </span>
        </div>
      </AppNotice>

      <template v-else>
        <section class="card content-card">
          <p class="card-title">网络与下载</p>
          <p class="paragraph">代理用于下载和版本查询。留空关闭启动器的代理设置。</p>
          <div class="form-field">
            <span class="field-label">HTTP / HTTPS 代理</span>
            <input class="input" :value="proxyValue" readonly />
          </div>
          <div class="form-field">
            <span class="field-label">npm 软件源</span>
            <input class="input" :value="registryValue" readonly />
          </div>
          <p class="small-muted">示例：http://127.0.0.1:7890；留空使用直连。</p>
        </section>

        <section class="card content-card">
          <p class="card-title">外观</p>
          <p class="paragraph">
            内置背景按右对齐显示；当前为「{{ backgroundValue }}」。缩略图来自桌面版内置背景资源。
          </p>
          <div class="background-grid">
            <div
              v-for="entry in BACKGROUNDS"
              :key="entry.id"
              class="background-choice"
              :class="{ 'is-selected': settings?.background === entry.value }"
            >
              <img class="background-thumb" :src="entry.src" :alt="entry.title" />
              <span class="background-choice__label">{{ entry.title }}</span>
            </div>
          </div>
          <p class="small-muted">
            背景选择与自定义上传将在后续增量提供；资源目前为桌面版原始 PNG（后续转 WebP，见方案 5.7）。
          </p>
        </section>

        <section class="card content-card">
          <p class="card-title">关于 HDSL</p>
          <div class="about-lines paragraph">
            <p>界面改编自 Hello Minecraft! Launcher</p>
            <p>版权所有 © 2013-2026 huangyuhui 及贡献者</p>
            <p>PCL-DSH-Linux {{ auth.version }} · Copyright (c) 2026 jiefing · GPLv3 或后续版本</p>
          </div>
          <p class="paragraph">HDSL 保留上游可见版权，并以独立名称和版本区别于原版。</p>
          <div class="about-links">
            <button class="btn btn-secondary btn--small" type="button" @click="notImplemented('GPL 许可离线查看')">
              查看 GPL 许可
            </button>
            <button class="btn btn-secondary btn--small" type="button" @click="notImplemented('HMCL 附加条款离线查看')">
              HMCL 附加条款
            </button>
          </div>
        </section>

        <AppNotice title="设置 · 阅读与编辑" tone="info">
          <p class="paragraph">
            当前设置页只读取 GET /api/settings 并原样展示；保存（PUT /api/settings）与背景修改将在 F-9 后续增量接入。
          </p>
        </AppNotice>
      </template>
    </div>
  </div>
</template>
