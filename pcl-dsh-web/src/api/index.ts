import { request } from './client'
import { normalizeInstances, normalizeSettings, normalizeState } from './normalize'
import type {
  AppSettings,
  AuthMe,
  InstanceSummary,
  JobAccepted,
  StateResponse,
  WebUrl,
} from './types'

export { ApiError } from './client'
export type { AppSettings, AuthMe, InstanceSummary, StateResponse } from './types'

function instancePath(id: string, suffix = ''): string {
  return '/api/instances/' + encodeURIComponent(id) + suffix
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
  launch: (id: string): Promise<JobAccepted> => request<JobAccepted>('POST', instancePath(id, '/launch')),
  stop: (id: string): Promise<unknown> => request<unknown>('POST', instancePath(id, '/stop')),
  select: (id: string): Promise<unknown> => request<unknown>('PUT', instancePath(id, '/selection')),
  webUrl: (id: string): Promise<WebUrl> => request<WebUrl>('GET', instancePath(id, '/web-url')),
}

export const settingsApi = {
  get: async (): Promise<AppSettings> => normalizeSettings(await request<unknown>('GET', '/api/settings')),
}
