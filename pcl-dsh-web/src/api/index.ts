import { request } from './client'
import {
  normalizeAvailableVersions,
  normalizeDeleteResult,
  normalizeInstance,
  normalizeInstances,
  normalizeJobAccepted,
  normalizeJobList,
  normalizeLogTail,
  normalizeRuntimes,
  normalizeSettings,
  normalizeState,
  normalizeWebUrl,
} from './normalize'
import type {
  AppSettings,
  AuthMe,
  AvailableRuntimes,
  DeleteInstanceResult,
  InstanceInput,
  InstanceSummary,
  JobView,
  LogTail,
  RuntimeSummary,
  StateResponse,
  WebUrl,
} from './types'

export { ApiError } from './client'
export type {
  AccountSummary,
  AppSettings,
  AuthMe,
  AvailableRuntimes,
  DeleteInstanceResult,
  InstanceInput,
  InstanceSummary,
  JobState,
  JobView,
  LogTail,
  RuntimeSummary,
  StateResponse,
  WebUrl,
} from './types'

function instancePath(id: string, suffix = ''): string {
  return '/api/instances/' + encodeURIComponent(id) + suffix
}

/** Thrown by helpers when the backend answers 202 without a usable job id. */
export class MissingJobError extends Error {
  constructor() {
    super('后端已接受任务，但没有返回任务 ID。请稍后在任务页面查看。')
    this.name = 'MissingJobError'
  }
}

export const authApi = {
  me: () => request<AuthMe>('GET', '/api/auth/me'),
  login: (password: string) => request<void>('POST', '/api/auth/login', { password }),
  logout: () => request<void>('POST', '/api/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string) =>
    request<void>('PUT', '/api/auth/password', { oldPassword, newPassword }),
}

export const stateApi = {
  get: async (): Promise<StateResponse> => normalizeState(await request<unknown>('GET', '/api/state')),
}

export const instancesApi = {
  list: async (): Promise<InstanceSummary[]> =>
    normalizeInstances(await request<unknown>('GET', '/api/instances')),
  get: async (id: string): Promise<InstanceSummary | null> =>
    normalizeInstance(await request<unknown>('GET', instancePath(id))),
  create: async (input: InstanceInput): Promise<InstanceSummary | null> =>
    normalizeInstance(await request<unknown>('POST', '/api/instances', input)),
  update: async (id: string, input: InstanceInput): Promise<InstanceSummary | null> =>
    normalizeInstance(await request<unknown>('PUT', instancePath(id), input)),
  remove: async (id: string): Promise<DeleteInstanceResult> =>
    normalizeDeleteResult(await request<unknown>('DELETE', instancePath(id))),
  select: (id: string): Promise<unknown> => request<unknown>('PUT', instancePath(id, '/selection')),
  bindAccount: (id: string, accountId: string): Promise<unknown> =>
    request<unknown>('PUT', instancePath(id, '/account'), { accountId }),
  launch: async (id: string): Promise<JobView> => {
    const job = normalizeJobAccepted(await request<unknown>('POST', instancePath(id, '/launch')))
    if (!job) throw new MissingJobError()
    return job
  },
  stop: (id: string): Promise<unknown> => request<unknown>('POST', instancePath(id, '/stop')),
  webUrl: async (id: string): Promise<WebUrl> =>
    normalizeWebUrl(await request<unknown>('GET', instancePath(id, '/web-url'))),
}

export const runtimesApi = {
  installed: async (): Promise<RuntimeSummary[]> =>
    normalizeRuntimes(await request<unknown>('GET', '/api/runtimes')),
  available: async (): Promise<AvailableRuntimes> =>
    normalizeAvailableVersions(await request<unknown>('GET', '/api/runtimes/available')),
  install: async (version: string): Promise<JobView> => {
    const job = normalizeJobAccepted(await request<unknown>('POST', '/api/runtimes', { version }))
    if (!job) throw new MissingJobError()
    return job
  },
  probe: async (version: string): Promise<JobView> => {
    const job = normalizeJobAccepted(
      await request<unknown>('POST', '/api/runtimes/' + encodeURIComponent(version) + '/probe'),
    )
    if (!job) throw new MissingJobError()
    return job
  },
  remove: async (version: string): Promise<DeleteInstanceResult> =>
    normalizeDeleteResult(await request<unknown>('DELETE', '/api/runtimes/' + encodeURIComponent(version))),
}

export const jobsApi = {
  list: async (): Promise<JobView[]> => normalizeJobList(await request<unknown>('GET', '/api/jobs')),
  get: async (id: string): Promise<JobView> => {
    const job = normalizeJobAccepted(await request<unknown>('GET', '/api/jobs/' + encodeURIComponent(id)))
    if (!job) throw new MissingJobError()
    return job
  },
  cancel: (id: string): Promise<unknown> =>
    request<unknown>('POST', '/api/jobs/' + encodeURIComponent(id) + '/cancel'),
  eventsPath: (id: string): string => '/api/jobs/' + encodeURIComponent(id) + '/events',
}

export const logsApi = {
  tail: async (lines = 800): Promise<LogTail> =>
    normalizeLogTail(await request<unknown>('GET', '/api/logs/tail?lines=' + String(lines))),
}

export const settingsApi = {
  get: async (): Promise<AppSettings> => normalizeSettings(await request<unknown>('GET', '/api/settings')),
  update: async (payload: AppSettings): Promise<AppSettings> =>
    normalizeSettings(await request<unknown>('PUT', '/api/settings', payload)),
  /**
   * POST /api/settings/background is planned in 4.7 but may not exist in this increment.
   * Callers must treat 404/405/501 as "not implemented yet" and say so in the UI.
   */
  uploadBackground: async (file: File): Promise<AppSettings> => {
    const formData = new FormData()
    formData.append('file', file)
    return normalizeSettings(
      await request<unknown>('POST', '/api/settings/background', undefined, { formData }),
    )
  },
}
