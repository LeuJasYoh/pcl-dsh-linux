/**
 * Typed DTOs for the REST contract in docs/IMPLEMENTATION_PLAN.md 4.2 / 4.9.
 *
 * The M1 backend is implemented in parallel, so every field is read defensively
 * (see api/normalize.ts). Screens must degrade instead of showing made-up data.
 */

/** /api/state mixes a desktop-era status string with the planned {code,message}. */
export type StatusSnapshot = string | { code?: string; message?: string } | null

export interface InstanceSummary {
  id: string
  name: string
  version: string
  profile: string
  port: number | null
  profiles: string[]
  accountId: string | null
  path: string | null
  status: string
}

export interface RuntimeSummary {
  version: string
  installed: boolean
  compatibility: string
}

export interface AccountSummary {
  id: string
  name: string
  provider: string
  baseUrl?: string
  model?: string
  hasKey?: boolean
}

export interface AppSettings {
  proxy: string
  registry: string
  background: string
}

export interface StateResponse {
  currentInstanceId: string | null
  settings: AppSettings
  instances: InstanceSummary[]
  runtimes: RuntimeSummary[]
  accounts: AccountSummary[]
  availableVersions: string[]
  status: StatusSnapshot
}

export interface AuthMe {
  authenticated: boolean
  csrfToken: string | null
  version: string
  initialPasswordPending: boolean
}

export interface WebUrl {
  url: string
}

export interface JobAccepted {
  jobId?: string
  id?: string
  state?: string
  phase?: string
  message?: string
}

/** Unified error body from plan 4.10. */
export interface ApiErrorBody {
  code?: string
  message?: string
  details?: unknown
  traceId?: string
}
