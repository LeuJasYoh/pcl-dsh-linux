/**
 * Typed DTOs for the REST contract in docs/IMPLEMENTATION_PLAN.md 4.2 / 4.3 / 4.9.
 *
 * The backend is implemented in parallel, so every field is read defensively
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
  status?: string
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

/** POST/PUT instance payload (plan 4.2). port === null means "auto assign". */
export interface InstanceInput {
  name: string
  version?: string
  profile?: string
  port?: number | null
  accountId?: string
}

export interface DeleteInstanceResult {
  trashedPath: string
}

/** GET /api/runtimes/available (plan 4.2). */
export interface AvailableRuntimes {
  versions: string[]
}

export interface LogTail {
  lines: string[]
}

/** Job state machine from plan 4.3. */
export type JobState = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface JobError {
  code: string
  message: string
  details?: unknown
}

export interface JobView {
  id: string
  type: string
  state: JobState
  phase: string
  message: string
  createdAt: number | null
  startedAt: number | null
  finishedAt: number | null
  lastSeq: number
  result: unknown
  error: JobError | null
}

export interface JobLogEntry {
  jobId: string
  seq: number
  ts: number
  level: string
  line: string
}

export interface JobStateEvent {
  jobId: string
  seq: number
  ts: number
  state: JobState
  phase: string
  message: string
}

export interface JobResultEvent {
  jobId: string
  state: JobState
  result: unknown
}

export interface JobErrorEvent {
  jobId: string
  state: JobState
  code: string
  message: string
  details?: unknown
}

/** A 202 response body: either a Job View, or a compact {jobId} envelope. */
export interface JobAccepted {
  jobId?: string
  id?: string
  state?: string
  phase?: string
  message?: string
  type?: string
}

/** Unified error body from plan 4.10. */
export interface ApiErrorBody {
  code?: string
  message?: string
  details?: unknown
  traceId?: string
}
