<script setup lang="ts">
import { computed, onMounted } from 'vue'

import AppIcon from '@/components/AppIcon.vue'
import AppNotice from '@/components/AppNotice.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useNotifyStore } from '@/stores/notify'
import { useAppStateStore } from '@/stores/state'

const appState = useAppStateStore()
const notify = useNotifyStore()

const accounts = computed(() => appState.accounts)
const current = computed(() => appState.currentInstance)
const boundAccount = computed(() => {
  const instance = current.value
  if (!instance || !instance.accountId) return null
  return accounts.value.find((account) => account.id === instance.accountId) ?? null
})

onMounted(() => {
  void appState.refresh()
})

function nextIncrement(action: string): void {
  notify.info(action + '将在下一增量提供（M2 F-7：账户新增/编辑/删除/绑定），当前不会提交或伪造任何账户数据。')
}
</script>

<template>
  <div class="page page--solid" data-testid="page-accounts">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">账户管理</span>
        <span class="small-muted">{{ accounts.length }} 个账户</span>
        <span class="toolbar__spacer"></span>
        <button
          class="btn btn-primary"
          type="button"
          data-testid="account-create"
          @click="nextIncrement('添加账户')"
        >
          <AppIcon name="plus" :size="15" />添加账户
        </button>
      </div>

      <AppNotice title="账户与实例" tone="info">
        <p class="paragraph">
          保存 API 提供商、地址和模型后，在实例中选择账户。保存配置不会测试 API 连接，也不会发送模型请求。
        </p>
      </AppNotice>

      <div v-if="current" class="card content-card">
        <p class="card-title">当前实例 · {{ current.name }}</p>
        <p class="paragraph">
          {{ boundAccount
            ? '已绑定账户「' + boundAccount.name + '」（' + (boundAccount.provider || '未知提供商') + '）'
            : '未绑定账户 · 使用实例原有配置' }}
        </p>
        <p class="small-muted">
          不绑定时使用实例原有配置。绑定更改在下次启动时生效，用于默认模型；已有会话可能保留单独选择的模型。
        </p>
        <div class="toolbar">
          <button class="btn btn-secondary btn--small" type="button" @click="nextIncrement('修改实例账户绑定')">
            应用到当前实例
          </button>
        </div>
      </div>

      <AppNotice v-if="appState.error" title="账户列表读取失败" tone="warn">
        <p class="paragraph">{{ appState.error }}</p>
        <div class="toolbar">
          <button class="btn btn-secondary btn--small" type="button" @click="appState.refresh()">重试</button>
          <span class="small-muted">账户列表来自 GET /api/state</span>
        </div>
      </AppNotice>

      <EmptyState
        v-else-if="accounts.length === 0"
        title="尚未保存账户"
        description="添加一个 API 账户，即可供多个实例选择。账户新增/编辑属于下一增量，当前仅展示已保存账户。"
        icon="user"
      >
        <button class="btn btn-primary" type="button" data-testid="account-create-empty" @click="nextIncrement('添加账户')">
          <AppIcon name="plus" :size="15" />添加账户
        </button>
      </EmptyState>

      <div v-else class="card-list" data-testid="account-list">
        <div v-for="account in accounts" :key="account.id" class="card account-card">
          <div class="account-card__info">
            <div class="account-card__heading">
              <span class="card-title">{{ account.name || '未命名账户' }}</span>
              <span v-if="account.hasKey" class="badge badge--green">已保存 API key</span>
              <span v-else class="badge badge--gray">未保存 API key</span>
              <span v-if="account.status" class="badge badge--blue">{{ account.status }}</span>
            </div>
            <p class="paragraph">
              {{ account.provider || '未知提供商' }}
              <template v-if="account.model"> · {{ account.model }}</template>
              <template v-if="account.baseUrl"> · {{ account.baseUrl }}</template>
            </p>
            <p class="small-muted">API 连接未验证</p>
          </div>
          <div class="instance-card__actions">
            <button class="btn btn-secondary" type="button" @click="nextIncrement('编辑账户')">
              <AppIcon name="settings" :size="15" />编辑
            </button>
            <button class="btn btn-subtle btn--danger" type="button" @click="nextIncrement('删除账户')">
              <AppIcon name="trash" :size="15" />删除
            </button>
          </div>
        </div>
      </div>

      <AppNotice title="账户安全" tone="info">
        <p class="paragraph">
          账户密钥加密保存在服务端，不会通过接口返回给浏览器；列表只显示是否已保存 key。删除被实例引用的账户会返回 409，
          需先解除绑定。
        </p>
      </AppNotice>
    </div>
  </div>
</template>
