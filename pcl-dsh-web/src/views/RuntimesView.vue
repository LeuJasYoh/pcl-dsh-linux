<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ApiError } from '@/api'
import { runtimesApi } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import AppNotice from '@/components/AppNotice.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import TaskProgressStrip from '@/components/TaskProgressStrip.vue'
import { useJobStore } from '@/stores/jobs'
import { useNotifyStore } from '@/stores/notify'
import { useAppStateStore } from '@/stores/state'
import { describeApiError } from '@/utils/errors'
import { validateVersion } from '@/utils/validation'

const appState = useAppStateStore()
const jobs = useJobStore()
const notify = useNotifyStore()

const available = ref<string[]>([])
const availableStatus = ref('')
const availableError = ref(false)
const querying = ref(false)
const versionInput = ref('')
const validationError = ref('')
const pending = ref(false)
const removeTarget = ref<string | null>(null)
const removing = ref(false)
/** Compatibility summaries returned by install/probe job results (state.runtimes may lag). */
const compatibilityOverrides = ref<Record<string, string>>({})

const installedRuntimes = computed(() => appState.runtimes.filter((runtime) => runtime.installed))
const versionChoices = computed(() => {
  const set = new Set<string>()
  for (const runtime of appState.runtimes) if (runtime.version) set.add(runtime.version)
  for (const version of available.value) if (version) set.add(version)
  return Array.from(set)
})
const busy = computed(() => jobs.active || querying.value || pending.value || removing.value)

function isInstalled(version: string): boolean {
  return installedRuntimes.value.some((runtime) => runtime.version === version)
}

function compatibilityText(version: string, compatibility: string): string {
  const value = (compatibilityOverrides.value[version] || compatibility || '').trim()
  if (!value || value === '待检测') return '安装后会检测可用命令与能力。'
  return value
}

// Probe/install jobs carry {"version","compatibility"} in their result (contract with server-dev2).
// Watch the result as well: the terminal "state" event arrives before the "result" event,
// so a state-only watcher can miss the payload.
watch(
  [() => jobs.state, () => jobs.result],
  () => {
    if (jobs.state !== 'SUCCEEDED') return
    const payload = jobs.result
    if (!payload || typeof payload !== 'object') return
    const record = payload as Record<string, unknown>
    const version = typeof record.version === 'string' ? record.version : ''
    const compatibility = typeof record.compatibility === 'string' ? record.compatibility : ''
    if (version && compatibility) {
      compatibilityOverrides.value = { ...compatibilityOverrides.value, [version]: compatibility }
    }
  },
)

async function refreshAvailable(): Promise<void> {
  if (querying.value) return
  querying.value = true
  availableError.value = false
  availableStatus.value = '正在获取发布版本…'
  try {
    const result = await runtimesApi.available()
    available.value = result.versions
    availableStatus.value = '已获取 ' + String(result.versions.length) + ' 个发布版本。'
  } catch (caught) {
    availableError.value = true
    const base = describeApiError(caught, '获取版本列表')
    availableStatus.value = caught instanceof ApiError && caught.status === 404
      ? '获取版本列表接口尚未实现（后端开发中）；可手动输入完整版本号安装。'
      : base + ' 可重试，或手动输入完整版本号。'
  } finally {
    querying.value = false
  }
}

onMounted(() => {
  void appState.refresh()
  void refreshAvailable()
})

function startJob(job: Awaited<ReturnType<typeof runtimesApi.install>>, context: { version?: string }): void {
  jobs.begin(job, context)
  notify.success('已提交任务：' + jobs.typeLabel + '（' + job.id + '）。')
}

async function install(version: string): Promise<void> {
  if (jobs.active || pending.value) {
    notify.info('当前有任务正在执行，请等待完成或取消。')
    return
  }
  const error = validateVersion(version)
  if (error) {
    validationError.value = error
    return
  }
  validationError.value = ''
  pending.value = true
  try {
    const job = await runtimesApi.install(version.trim())
    startJob(job, { version: version.trim() })
    versionInput.value = ''
    await appState.refresh()
  } catch (caught) {
    validationError.value = describeApiError(caught, '安装运行时')
    notify.error(validationError.value)
  } finally {
    pending.value = false
  }
}

async function installInput(): Promise<void> {
  await install(versionInput.value)
}

async function probe(version: string): Promise<void> {
  if (jobs.active || pending.value) {
    notify.info('当前有任务正在执行，请等待完成或取消。')
    return
  }
  pending.value = true
  try {
    const job = await runtimesApi.probe(version)
    startJob(job, { version })
  } catch (caught) {
    notify.error(describeApiError(caught, '检测运行时能力'))
  } finally {
    pending.value = false
  }
}

async function confirmRemove(): Promise<void> {
  const version = removeTarget.value
  if (!version || removing.value) return
  removing.value = true
  try {
    const result = await runtimesApi.remove(version)
    notify.success(
      result.trashedPath
        ? '运行时已移入回收目录：' + result.trashedPath
        : '运行时已移入回收目录。',
    )
    removeTarget.value = null
    await appState.refresh()
  } catch (caught) {
    // Close the confirmation on failure so the page is not blocked by a stale dialog.
    removeTarget.value = null
    notify.error(describeApiError(caught, '移除运行时'))
  } finally {
    removing.value = false
  }
}
</script>

<template>
  <div class="page page--solid" data-testid="page-runtimes">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">运行时</span>
        <span class="small-muted">{{ installedRuntimes.length }} 个已安装</span>
        <span class="toolbar__spacer"></span>
        <button
          class="btn btn-secondary"
          type="button"
          data-testid="runtimes-refresh"
          :disabled="busy"
          @click="refreshAvailable"
        >
          <AppIcon name="refresh" :size="15" />刷新版本
        </button>
      </div>

      <TaskProgressStrip />

      <section class="card content-card">
        <p class="card-title">安装 Harness 运行时</p>
        <p class="paragraph">
          从发布列表选择版本，或直接输入完整版本号。已安装版本会保留，实例升级可以单独进行。
        </p>
        <div class="inline-field">
          <input
            v-model="versionInput"
            class="input"
            data-testid="runtime-version"
            list="runtime-version-options"
            placeholder="输入完整版本号，或刷新版本列表"
            :disabled="busy"
          />
          <datalist id="runtime-version-options">
            <option v-for="version in versionChoices" :key="version" :value="version"></option>
          </datalist>
          <button
            class="btn btn-primary"
            type="button"
            data-testid="runtime-install"
            :disabled="busy"
            @click="installInput"
          >
            <AppIcon name="download" :size="15" />安装
          </button>
        </div>
        <p v-if="validationError" class="validation-error" data-testid="runtime-validation" role="alert">
          {{ validationError }}
        </p>
        <p class="small-muted">安装为长任务（≤20 分钟），进度会显示在首页任务卡与任务与日志页。</p>
      </section>

      <section class="card content-card">
        <div class="toolbar">
          <span class="card-title">可用版本</span>
          <span class="toolbar__spacer"></span>
          <span class="small-muted" data-testid="runtimes-available-status">{{ availableStatus || '尚未获取版本列表' }}</span>
        </div>
        <p v-if="availableError" class="validation-error" role="alert">{{ availableStatus }}</p>
        <div v-else-if="available.length" class="version-list" data-testid="runtimes-available">
          <div v-for="version in available" :key="version" class="version-row">
            <span class="version-row__name">{{ version }}</span>
            <span v-if="isInstalled(version)" class="badge badge--green">已安装</span>
            <span class="toolbar__spacer"></span>
            <button
              class="btn btn-secondary btn--small"
              type="button"
              :disabled="busy || isInstalled(version)"
              @click="install(version)"
            >
              安装
            </button>
          </div>
        </div>
        <p v-else class="paragraph">
          {{ querying ? '正在获取发布版本…' : '暂无可用版本；可点击“刷新版本”，或在上方手动输入完整版本号安装。' }}
        </p>
      </section>

      <span class="section-title">本地运行时</span>
      <EmptyState
        v-if="installedRuntimes.length === 0"
        title="还没有运行时"
        description="输入版本并安装后，可在实例设置中选择它。"
        icon="cpu"
      />
      <div v-else class="card-list" data-testid="runtimes-installed">
        <div v-for="runtime in installedRuntimes" :key="runtime.version" class="card runtime-card">
          <div class="runtime-card__info">
            <div class="runtime-card__heading">
              <span class="card-title">{{ runtime.version }}</span>
              <span class="badge badge--green">已安装</span>
            </div>
            <p class="paragraph" data-testid="runtime-compatibility">{{ compatibilityText(runtime.version, runtime.compatibility) }}</p>
          </div>
          <div class="instance-card__actions">
            <button
              class="btn btn-secondary"
              type="button"
              :disabled="busy"
              :data-testid="'runtime-probe-' + runtime.version"
              @click="probe(runtime.version)"
            >
              <AppIcon name="refresh" :size="15" />检测
            </button>
            <button
              class="btn btn-subtle btn--danger"
              type="button"
              :disabled="busy"
              :data-testid="'runtime-remove-' + runtime.version"
              @click="removeTarget = runtime.version"
            >
              <AppIcon name="trash" :size="15" />移除
            </button>
          </div>
        </div>
      </div>

      <AppNotice v-if="appState.error" title="已安装列表读取失败" tone="warn">
        <p class="paragraph">{{ appState.error }}</p>
        <div class="toolbar">
          <button class="btn btn-secondary btn--small" type="button" @click="appState.refresh()">重试</button>
        </div>
      </AppNotice>

      <AppNotice title="版本兼容" tone="info">
        <p class="paragraph">
          启动器按实际命令能力适配 Harness。未验证的新版本会展示检测结果；实例固定版本后，不会随启动器更新自动升级。
        </p>
      </AppNotice>
    </div>

    <ConfirmDialog
      v-if="removeTarget"
      title="移除运行时"
      :question="'移除 Harness ' + removeTarget + '？'"
      detail="只能移除没有实例引用的版本；如有引用，请先调整对应实例。运行时会移入回收目录。"
      confirm-text="确认移除"
      :busy="removing"
      @confirm="confirmRemove"
      @cancel="removeTarget = null"
    />
  </div>
</template>
