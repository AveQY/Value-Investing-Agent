import http from './http'
import { streamSseJson, streamText } from '@/utils/sse'

export const advisorStyleOptions = [
  {
    id: 'default',
    label: '默认视角',
    description: '保持 AI价投分析智能体的默认分析方式与表达风格。',
  },
  {
    id: 'buffett_framework',
    label: '巴菲特视角',
    description: '在当前模式上叠加护城河、安全边际、能力圈等价值投资框架。',
  },
  {
    id: 'buffett_persona',
    label: '巴菲特表达',
    description: '保留价值投资框架，并用更朴素、长期主义的表达方式回答。',
  },
]

export const defaultAdvisorStyle = advisorStyleOptions[0].id
export const buffettToggleAdvisorStyle = 'buffett_framework'

export const chatModeOptions = [
  {
    id: 'tools',
    label: '知识 + 工具',
    description: '先结合本地知识库，再调用搜索与行情工具补充事实',
    path: '/fund/chat/tools',
    ssePath: import.meta.env.VITE_FUND_TOOLS_SSE_PATH || '/fund/chat/tools/sse',
    modeCode: 'TOOLS',
  },
  {
    id: 'value',
    label: '价值分析',
    description: '单只股票近三年财务体检，随后按周行五步法继续分析',
    path: '/fund/chat/value',
    ssePath: import.meta.env.VITE_FUND_VALUE_SSE_PATH || '/fund/chat/value/sse',
    modeCode: 'VALUE',
  },
]

function resolveMode(modeId = 'tools') {
  return chatModeOptions.find((item) => item.id === modeId) || chatModeOptions[0]
}

function normalizeAdvisorStyle(advisorStyle = defaultAdvisorStyle) {
  return advisorStyleOptions.some((item) => item.id === advisorStyle)
    ? advisorStyle
    : defaultAdvisorStyle
}

function withAdvisorStyle(payload = {}, modeId = 'tools') {
  const normalizedAdvisorStyle = normalizeAdvisorStyle(payload.advisorStyle)

  return {
    ...payload,
    advisorStyle: modeId === 'tools' ? normalizedAdvisorStyle : defaultAdvisorStyle,
  }
}

function buildLeaseHeaders(lease) {
  if (!lease?.clientId || !lease?.leaseToken) {
    return {}
  }

  return {
    'X-Chat-Client-Id': lease.clientId,
    'X-Chat-Lease-Token': lease.leaseToken,
  }
}

function withLeaseHeaders(lease, config = {}) {
  return {
    ...config,
    headers: {
      ...(config.headers || {}),
      ...buildLeaseHeaders(lease),
    },
  }
}

export async function acquireSessionLock(chatId, clientId) {
  const { data } = await http.post(`/fund/session-lock/${chatId}/acquire`, { clientId })
  return data
}

export async function heartbeatSessionLock(chatId, lease) {
  const { data } = await http.post(`/fund/session-lock/${chatId}/heartbeat`, null, withLeaseHeaders(lease))
  return data
}

export async function releaseSessionLock(chatId, lease, options = {}) {
  const requestPath = `/fund/session-lock/${chatId}/release`

  if (options.keepalive && typeof fetch === 'function') {
    const response = await fetch(resolveApiUrl(requestPath), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(import.meta.env.VITE_API_KEY ? { 'X-API-KEY': import.meta.env.VITE_API_KEY } : {}),
        ...buildLeaseHeaders(lease),
      },
      keepalive: true,
    })

    if (!response.ok) {
      throw new Error(`释放会话占用失败（${response.status}）`)
    }

    return
  }

  await http.post(requestPath, null, withLeaseHeaders(lease))
}

export async function getHistory(chatId, lease, config = {}) {
  const { data } = await http.get(`/fund/history/${chatId}`, withLeaseHeaders(lease, config))
  return data
}

export async function getHistorySessions() {
  const { data } = await http.get('/fund/history/sessions')
  return data
}

export async function deleteHistory(chatId, lease) {
  const { data } = await http.delete(`/fund/history/${chatId}`, withLeaseHeaders(lease))
  return data
}

export async function getModels() {
  const { data } = await http.get('/fund/models')
  return data
}

export async function getRuntimeConfig() {
  const { data } = await http.get('/fund/runtime-config')
  return data
}

export async function discoverModels(payload) {
  const { data } = await http.post('/fund/models/discover', payload)
  return data
}

export async function createModel(payload) {
  const { data } = await http.post('/fund/models', payload)
  return data
}

export async function requestChat(payload, modeId = 'tools', lease) {
  const mode = resolveMode(modeId)
  const { data } = await http.post(mode.path, withAdvisorStyle(payload, modeId), withLeaseHeaders(lease))
  return data
}

export async function generateReport(payload, lease) {
  const normalizedPayload = withAdvisorStyle(payload, payload?.mode || 'report')
  const { data } = await http.post('/fund/chat/report', normalizedPayload, withLeaseHeaders(lease))
  return data
}

export async function createReportTask(payload, lease) {
  const normalizedPayload = withAdvisorStyle(payload, payload?.mode || 'report')
  const { data } = await http.post('/fund/chat/report/tasks', normalizedPayload, withLeaseHeaders(lease))
  return data
}

export async function getReportTask(taskId, chatId, lease) {
  const { data } = await http.get(`/fund/chat/report/tasks/${taskId}`, withLeaseHeaders(lease, {
    params: {
      chatId,
    },
  }))
  return data
}

export async function downloadReportFile(downloadPath, fileName = 'investment-report.pdf') {
  const { data } = await http.get(downloadPath, {
    responseType: 'blob',
  })

  const blob = data instanceof Blob ? data : new Blob([data], { type: 'application/pdf' })
  const objectUrl = window.URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  window.URL.revokeObjectURL(objectUrl)
}

export async function streamChat(payload, modeId = 'tools', handlers = {}, lease) {
  const mode = resolveMode(modeId)
  const sseEnabled = import.meta.env.VITE_ENABLE_SSE_CHAT !== 'false'
  const normalizedPayload = withAdvisorStyle(payload, modeId)

  if (sseEnabled) {
    try {
      await streamSseJson({
        baseUrl: http.defaults.baseURL,
        path: mode.ssePath,
        payload: normalizedPayload,
        apiKey: import.meta.env.VITE_API_KEY,
        headers: buildLeaseHeaders(lease),
        onChunk: handlers.onChunk,
        signal: handlers.signal,
      })

      return {
        chatId: normalizedPayload.chatId,
        mode: mode.modeCode,
        modelId: normalizedPayload.modelId,
        advisorStyle: normalizedPayload.advisorStyle,
        timestamp: new Date().toISOString(),
      }
    } catch (error) {
      if (!shouldFallbackToRest(error)) {
        throw error
      }
    }
  }

  const response = await requestChat(normalizedPayload, modeId, lease)
  await streamText(response.content || '', {
    onChunk: handlers.onChunk,
    signal: handlers.signal,
  })
  return response
}

function shouldFallbackToRest(error) {
  return [0, 404, 405, 406, 415, 500].includes(error?.status)
}

function resolveApiUrl(path) {
  if (/^https?:\/\//i.test(path)) {
    return path
  }

  const normalizedPath = path.replace(/^\//, '')
  const baseUrl = http.defaults.baseURL || '/api'

  if (/^https?:\/\//i.test(baseUrl)) {
    return new URL(normalizedPath, ensureTrailingSlash(baseUrl)).toString()
  }

  const basePath = baseUrl.startsWith('/') ? baseUrl : `/${baseUrl}`
  return new URL(normalizedPath, `${window.location.origin}${ensureTrailingSlash(basePath)}`).toString()
}

function ensureTrailingSlash(value) {
  return value.endsWith('/') ? value : `${value}/`
}
