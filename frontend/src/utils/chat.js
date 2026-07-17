export const modeLabelMap = {
  CHAT: '',
  RAG: '',
  TOOLS: '知识 + 工具',
  VALUE: '价值分析',
  REPORT: '结构化报告',
}

export const advisorStyleLabelMap = {
  default: '默认视角',
  buffett_framework: '巴菲特视角',
  buffett_persona: '巴菲特表达',
}

export function createChatId(prefix = 'fund') {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

export function isValidChatId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value)
}

export function formatClockTime(value) {
  if (!value) {
    return ''
  }

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}
