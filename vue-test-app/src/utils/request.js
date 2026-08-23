/**
 * @description Axios 全局请求封装工具
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { STORAGE_KEYS, RES_CODE } from '../constants'

const service = axios.create({
    baseURL: '/api',
    timeout: 300000
})

const refreshService = axios.create({
  baseURL: '/api',
  timeout: 30000
})

let refreshPromise = null

const AI_PATH_PREFIX = '/ai/'
const AIOPS_PATH_PREFIX = '/ai/aiops/'

function getAccessToken() {
  return localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)
}

function clearSession() {
  localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  localStorage.removeItem('userId')
  localStorage.removeItem('userName')
}

function persistSession(loginData) {
  const accessToken = loginData?.accessToken
  const refreshToken = loginData?.refreshToken
  if (!accessToken || !refreshToken) {
    throw new Error('刷新令牌响应不完整')
  }
  localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
  localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
  const user = loginData.user || {}
  localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(user))
  if (user.id != null) localStorage.setItem('userId', String(user.id))
  if (user.username) localStorage.setItem('userName', user.username)
  return accessToken
}

function canRefreshRequest(config) {
  if (!config || config._retry) return false
  const url = config.url || ''
  return !['/users/login', '/users/register', '/users/refresh', '/users/logout']
    .some(path => url.startsWith(path))
}

async function refreshAccessToken() {
  const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
  if (!refreshToken) throw new Error('缺少刷新令牌')
  if (!refreshPromise) {
    refreshPromise = refreshService.post('/users/refresh', { refreshToken })
      .then(response => {
        const payload = response.data || {}
        if (payload.code !== undefined && payload.code !== 0 && payload.code !== 200) {
          throw new Error(payload.message || '刷新令牌失败')
        }
        return persistSession(payload.data || payload)
      })
      .finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

async function retryAfterRefresh(config) {
  if (!canRefreshRequest(config)) throw new Error('请求不可刷新')
  config._retry = true
  const accessToken = await refreshAccessToken()
  config.headers = config.headers || {}
  config.headers.Authorization = `Bearer ${accessToken}`
  return service(config)
}

function redirectToLogin() {
  clearSession()
  if (router.currentRoute.value.path !== '/login') router.push('/login')
}

function buildAuthHeaders() {
  const token = getAccessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function isAiRequest(url) {
  return url && url.startsWith(AI_PATH_PREFIX) && !url.startsWith(AIOPS_PATH_PREFIX)
}

function reportAiMetrics(config, hasError) {
  const url = config.url || ''
  if (!isAiRequest(url)) return

  const startTime = config._startTime
  const duration = startTime ? Date.now() - startTime : 0

  const baseURL = config.baseURL || '/api'

  axios.post(baseURL + '/ai/aiops/metrics/counter', null, {
    headers: buildAuthHeaders(),
    params: { name: 'ai.requests', delta: 1 }
  }).catch(() => {})

  if (duration > 0) {
    axios.post(baseURL + '/ai/aiops/metrics/timer', null, {
      headers: buildAuthHeaders(),
      params: { name: 'ai.request', duration }
    }).catch(() => {})
  }

  if (hasError) {
    axios.post(baseURL + '/ai/aiops/metrics/counter', null, {
      headers: buildAuthHeaders(),
      params: { name: 'ai.errors', delta: 1 }
    }).catch(() => {})
  }
}

service.interceptors.request.use(
    config => {
        config._startTime = Date.now()
        const token = getAccessToken()
        if (token) {
            config.headers['Authorization'] = 'Bearer ' + token
        }
        return config
    },
    error => Promise.reject(error)
)

service.interceptors.response.use(
    async response => {
        if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
            reportAiMetrics(response.config, false)
            return response.data
        }

        const res = response.data

        // 如果后端返回的是字符串（比如 HTML 源码），而不是 JSON 对象，直接放行！
        if (typeof res === 'string') {
            return res
        }

        // 兼容后端直接返回数组而没有 code 的情况（如 getFileList）
        if (res && res.code === undefined) {
            reportAiMetrics(response.config, false)
            return { data: res, code: 200, message: 'success' }
        }

        if (res.code === 200 || res.code === 0) {
            reportAiMetrics(response.config, false)
            return res
        } else if (res.code === RES_CODE.UNAUTHORIZED || res.code === 604 || res.code === 605) {
            try {
              return await retryAfterRefresh(response.config)
            } catch {
              reportAiMetrics(response.config, true)
              ElMessage.error('登录已过期，请重新登录')
              redirectToLogin()
              return Promise.reject(new Error(res.message || 'Unauthorized'))
            }
        } else {
            reportAiMetrics(response.config, true)
            ElMessage.error(res.message || '操作失败')
            const businessError = new Error(res.message || 'Error')
            businessError.code = res.code
            businessError.data = res
            return Promise.reject(businessError)
        }
    },
    async error => {
        if (error.config) {
            reportAiMetrics(error.config, true)
        }
        if (error.response && error.response.status === 401) {
            try {
              return await retryAfterRefresh(error.config)
            } catch {
              ElMessage.error('登录已过期，请重新登录')
              redirectToLogin()
            }
        } else {
            const payload = error.response?.data
            if (payload && typeof payload === 'object') {
              error.code = payload.code
              error.data = payload
            }
            ElMessage.error(payload?.message || '服务器开了小差，请稍后再试')
        }
        return Promise.reject(error)
    }
)

export default service
