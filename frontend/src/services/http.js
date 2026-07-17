import axios from 'axios'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const http = axios.create({
  baseURL,
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json',
  },
})

http.interceptors.request.use((config) => {
  const apiKey = import.meta.env.VITE_API_KEY

  if (apiKey) {
    config.headers['X-API-KEY'] = apiKey
  }

  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const payload = error.response.data
      const normalized = new Error(buildApiMessage(payload, error.response.status))
      normalized.status = error.response.status
      normalized.payload = payload
      return Promise.reject(normalized)
    }

    const networkError = new Error('无法连接后端服务，请确认接口服务已启动。')
    networkError.status = 0
    return Promise.reject(networkError)
  },
)

function buildApiMessage(payload, status) {
  if (shouldUseMessageOnly(payload, status)) {
    return payload.message
  }

  if (payload?.details && typeof payload.details === 'object') {
    const details = Object.values(payload.details).filter(Boolean).join('；')
    if (details) {
      return `${payload.message || '请求失败'}：${details}`
    }
  }

  if (payload?.message) {
    return payload.message
  }

  if (typeof payload === 'string' && payload.trim()) {
    return payload
  }

  if (status === 404) {
    return '未找到对应资源。'
  }

  return `请求失败（${status}）。`
}

function shouldUseMessageOnly(payload, status) {
  if (!payload?.message || !payload?.details || typeof payload.details !== 'object') {
    return false
  }

  const leaseReasons = ['occupied', 'lease_expired', 'lease_invalid', 'lease_required']
  return [409, 423].includes(status) && leaseReasons.includes(payload.details.reason)
}

export default http
