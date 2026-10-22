import type {
  AccountSummary,
  AppSettings,
  InstanceSummary,
  RuntimeSummary,
  StateResponse,
  StatusSnapshot,
} from './types'

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown, fallback = ''): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return fallback
}

function asNullableString(value: unknown): string | null {
  const text = asString(value)
  return text ? text : null
}

function asBoolean(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback
}

function asNumberOrNull(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function asStatusText(value: unknown): string {
  if (typeof value === 'string') return value
  if (isRecord(value)) {
    const message = asString(value.message)
    if (message) return message
    return asString(value.code)
  }
  return ''
}

function unwrapRecord(raw: unknown, key: string): Record<string, unknown> {
  if (!isRecord(raw)) return {}
  const nested = raw[key]
  return isRecord(nested) ? nested : raw
}

export function normalizeSettings(raw: unknown): AppSettings {
  const record = isRecord(raw) ? (isRecord(raw.settings) ? raw.settings : raw) : {}
  return {
    proxy: asString(record.proxy),
    registry: asString(record.registry),
    // The desktop default is builtin:whale-01; an absent value falls back to it.
    background: asString(record.background) || 'builtin:whale-01',
  }
}

export function normalizeInstances(raw: unknown): InstanceSummary[] {
  const record = isRecord(raw) ? raw : {}
  const list = Array.isArray(raw) ? raw : asArray(record.instances ?? record.items)
  return list.filter(isRecord).map((item, index) => {
    const id = asString(item.id) || 'instance-' + String(index + 1)
    return {
      id,
      name: asString(item.name) || id,
      version: asString(item.version) || asString(item.runtimeVersion),
      profile: asString(item.profile) || 'web',
      port: asNumberOrNull(item.port),
      profiles: asArray(item.profiles).map((value) => asString(value)).filter(Boolean),
      accountId: asNullableString(item.accountId),
      path: asNullableString(item.path),
      status: asStatusText(item.status),
    }
  })
}

export function normalizeRuntimes(raw: unknown): RuntimeSummary[] {
  const record = isRecord(raw) ? raw : {}
  const list = Array.isArray(raw) ? raw : asArray(record.runtimes ?? record.items)
  return list.filter(isRecord).map((item) => ({
    version: asString(item.version),
    installed: asBoolean(item.installed, true),
    compatibility: asString(item.compatibility ?? item.compatibilityMessage),
  })).filter((item) => item.version !== '')
}

export function normalizeAccounts(raw: unknown): AccountSummary[] {
  const record = isRecord(raw) ? raw : {}
  const list = Array.isArray(raw) ? raw : asArray(record.accounts ?? record.items)
  return list.filter(isRecord).map((item, index) => ({
    id: asString(item.id) || 'account-' + String(index + 1),
    name: asString(item.name),
    provider: asString(item.provider),
    baseUrl: asString(item.baseUrl) || undefined,
    model: asString(item.model) || undefined,
    hasKey: typeof item.hasKey === 'boolean' ? item.hasKey : undefined,
  }))
}

function normalizeStatus(raw: unknown): StatusSnapshot {
  if (typeof raw === 'string') return raw
  if (isRecord(raw)) {
    return { code: asString(raw.code) || undefined, message: asString(raw.message) || undefined }
  }
  return null
}

export function normalizeState(raw: unknown): StateResponse {
  const record = unwrapRecord(raw, 'state')
  return {
    currentInstanceId: asNullableString(record.currentInstanceId),
    settings: normalizeSettings(record.settings),
    instances: normalizeInstances(record.instances),
    runtimes: normalizeRuntimes(record.runtimes),
    accounts: normalizeAccounts(record.accounts),
    availableVersions: asArray(record.availableVersions).map((value) => asString(value)).filter(Boolean),
    status: normalizeStatus(record.status),
  }
}
