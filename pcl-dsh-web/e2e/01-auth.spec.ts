import { test, expect } from '@playwright/test'

import { authStatePath, readInitialPassword } from './helpers'

// Runs in the "auth" project (no storage state) and produces .e2e-data/auth.json
// for the dependent "chromium" project.
test.describe.configure({ mode: 'serial' })

const E2E_NEW_PASSWORD = 'E2e-Password-123'

test('未登录访问首页会跳转到登录页', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByTestId('page-login')).toBeVisible()
  await expect(page.locator('[data-testid="page-header"]')).toHaveCount(0)
})

test('错误密码显示中文提示', async ({ page }) => {
  await page.goto('/login')
  await page.getByTestId('login-password').fill('definitely-not-the-password')
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('login-error')).toContainText('密码错误')
})

test('初始密码登录后强制改密并进入首页', async ({ page }) => {
  const initialPassword = readInitialPassword()
  await page.goto('/login')
  await page.getByTestId('login-password').fill(initialPassword)
  await page.getByTestId('login-submit').click()

  // First login with a generated password must change it before entering the app.
  await expect(page.getByTestId('login-new-password')).toBeVisible()
  await expect(page.getByText('首次登录需要修改初始密码')).toBeVisible()
  await page.getByTestId('login-new-password').fill(E2E_NEW_PASSWORD)
  await page.getByTestId('login-confirm-password').fill(E2E_NEW_PASSWORD)
  await page.getByTestId('login-change-submit').click()

  await expect(page.getByTestId('page-home')).toBeVisible()
  await expect(page.locator('[data-testid="page-header"]')).toBeVisible()

  // Persist the authenticated session for the dependent project.
  await page.context().storageState({ path: authStatePath })
})
