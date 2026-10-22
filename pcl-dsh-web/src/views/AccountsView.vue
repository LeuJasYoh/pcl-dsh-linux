<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import AppNotice from '@/components/AppNotice.vue'
import DevPlaceholder from '@/components/DevPlaceholder.vue'
</script>

<template>
  <div class="page page--solid" data-testid="page-accounts">
    <div class="card-list">
      <div class="toolbar">
        <span class="section-title">账户管理</span>
        <span class="badge badge--blue">开发中</span>
        <span class="toolbar__spacer"></span>
        <button class="btn btn-primary" type="button" disabled title="后端尚未实现">
          <AppIcon name="plus" :size="15" />添加账户
        </button>
      </div>

      <AppNotice title="账户与实例" tone="info">
        <p class="paragraph">
          保存 API 提供商、地址和模型后，在实例中选择账户。保存配置不会测试 API 连接，也不会发送模型请求。
        </p>
      </AppNotice>

      <DevPlaceholder
        title="尚未保存账户"
        description="添加一个 API 账户，即可供多个实例选择。"
        icon="user"
        :planned="[
          '账户列表与绑定关系（GET /api/accounts）',
          '添加/编辑账户（POST/PUT /api/accounts，API key 加密保存）',
          '删除账户（被实例引用时 409）',
          '绑定到当前实例（PUT /api/instances/{id}/account）',
        ]"
        :api="['GET /api/accounts', 'POST /api/accounts', 'PUT /api/accounts/{id}', 'DELETE /api/accounts/{id}']"
      />
    </div>
  </div>
</template>
