<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import MessageBubble from '@/components/MessageBubble.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { SITE_NAME, SITE_TAGLINE } from '@/constants/brand'
import {
  acquireSessionLock,
  advisorStyleOptions,
  buffettToggleAdvisorStyle,
  chatModeOptions,
  createModel,
  createReportTask,
  defaultAdvisorStyle,
  deleteHistory,
  downloadReportFile,
  generateReport,
  getHistory,
  getHistorySessions,
  getModels,
  getReportTask,
  getRuntimeConfig,
  heartbeatSessionLock,
  releaseSessionLock,
  streamChat,
} from '@/services/fundApp'
import {
  createChatId,
  formatClockTime,
  isValidChatId,
} from '@/utils/chat'

const route = useRoute()
const router = useRouter()
const CHAT_MODEL_STORAGE_KEY = 'ai-agent-selected-model-id'
const CHAT_SESSION_CLIENT_ID_STORAGE_KEY = 'ai-agent-session-client-id'
const ASSISTANT_WAITING_STEP_INTERVAL = 2200
const SESSION_HEARTBEAT_INTERVAL = 10000
const SESSION_LEASE_REFRESH_BUFFER_MS = 3000
const SESSION_OCCUPIED_MESSAGE = '该会话正在被其他客户端使用，请稍后再试或新建对话。'
const FINANCE_ONLY_REJECTION_MESSAGE =
  '当前投资顾问仅支持财经与投资相关问题，例如股票、基金、指数、行情、公告、财报、估值和资产配置。请改问财经问题后再试；如果你想问个股，请尽量补充股票代码、市场或具体金融上下文。'
const SESSION_OCCUPIED_MESSAGE_DURATION_MS = 5000
const FINANCE_ONLY_REJECTION_DURATION_MS = 3000
const RECENT_SESSION_CACHE_LIMIT = 1000

const draft = ref('')
const messages = ref([])
const loadingHistory = ref(false)
const loadingModels = ref(false)
const pending = ref(false)
const pendingStatus = ref(null)
const savingModel = ref(false)
const keepMessagesOnRefresh = ref(false)
const errorMessage = ref('')
const modelErrorMessage = ref('')
const currentMode = ref('tools')
const activeWorkspace = ref('chat')
const currentAdvisorStyle = ref(defaultAdvisorStyle)
const chatId = ref('')
const recentSessions = ref([])
const availableModels = ref([])
const runtimeConfig = ref({
  toolsMode: null,
  valueAnalysis: null,
})
const selectedModelId = ref(readStoredModelId())
const sessionClientId = readOrCreateSessionClientId()
const activeLeaseChatId = ref('')
const activeLeaseToken = ref('')
const activeLeaseExpiresAt = ref('')
const occupiedSessionChatIds = ref([])
const messageViewport = ref(null)
const composerRef = ref(null)
const sessionMenuRef = ref(null)
const modeMenuOpen = ref(false)
const mobileSidebarOpen = ref(false)
const aboutDialogOpen = ref(false)
const modelDialogOpen = ref(false)
const sessionMenu = ref({
  open: false,
  chatId: '',
  preview: '',
  x: 0,
  y: 0,
})
const modelForm = reactive({
  name: '',
  baseUrl: '',
  modelId: '',
  apiKey: '',
})

const suggestionCardsPool = [
  {
    id: 'health-check',
    title: '单股财务体检',
    description: '先看近三年主业质量，再判断公司财务是否合格健康。',
    prompt: '请分析贵州茅台近三年扣非ROE、现金收入比率和毛利率，并判断财务是否健康',
    mode: 'value',
  },
  {
    id: 'gross-margin-peers',
    title: '毛利率与同行',
    description: '先列可比公司，再比较近三年毛利率和竞争位置。',
    prompt: '请分析海天味业，给出竞争对手明细，并比较近三年毛利率',
    mode: 'value',
  },
  {
    id: 'profit-quality',
    title: '净利润质量',
    description: '聚焦净利润率、现金含金量和扣非净利润占比。',
    prompt: '请分析宁德时代近三年的净利润率、净利润现金含金量和扣非净利润/净利润',
    mode: 'value',
  },
  {
    id: 'zhouxing',
    title: '周行五步法',
    description: '财务体检后，继续按周行五步法看估值、跟踪指标和情绪决策。',
    prompt: '请分析中国神华近三年现金及其等价物/带息债务、资产负债率和带息债务/总资产，并按周行五步法给出投资建议',
    mode: 'value',
  },
  {
    id: 'latest-announcement',
    title: '最新公告追踪',
    description: '结合本地知识库和实时搜索，先看最近公告、年报重点与风险提示。',
    prompt: '请结合本地知识库和工具，分析中国卫星最近公告、年报重点和潜在风险',
    mode: 'tools',
  },
  {
    id: 'capital-flow',
    title: '机构动向',
    description: '补查机构增减持、险资动作和市场关注点，看看资金态度是否转向。',
    prompt: '请结合本地知识库和工具，分析招商银行近期机构增减持、险资动向和市场情绪变化',
    mode: 'tools',
  },
  {
    id: 'treasury-yield',
    title: '利率与估值',
    description: '把 10 年期国债收益率、股息率和估值锚放在一起看。',
    prompt: '请结合本地知识库和工具，分析长江电力当前股息率、估值锚，并比较中国10年期国债收益率',
    mode: 'tools',
  },
  {
    id: 'asset-quality',
    title: '资产质量细拆',
    description: '重点拆存货周转率、经营性负债和资产负债结构是否异常。',
    prompt: '请分析比亚迪近三年存货周转率、存货周转天数、经营性负债和资产负债结构',
    mode: 'value',
  },
  {
    id: 'cash-debt',
    title: '现金与债务',
    description: '看现金储备、带息债务和偿债冗余度是否够厚。',
    prompt: '请分析中国神华近三年现金及其等价物、带息债务、资产负债率和带息债务/总资产',
    mode: 'value',
  },
  {
    id: 'business-contradiction',
    title: '主要矛盾抓手',
    description: '先抓一句话主要矛盾，再看知识库与工具证据是否支持这个判断。',
    prompt: '请结合本地知识库和工具，分析美的集团当前的主要矛盾、核心跟踪指标和最新经营信号',
    mode: 'tools',
  },
  {
    id: 'high-dividend',
    title: '高股息定价',
    description: '适合拆高股息资产的股息率、无风险收益率和估值贵便宜判断。',
    prompt: '请按周行五步法分析工商银行当前股息率、无风险收益率对比、估值贵便宜和市场情绪',
    mode: 'value',
  },
  {
    id: 'knowledge-first',
    title: '知识库深挖',
    description: '先吃透本地资料，再补查事实，把结论和引用证据一起给出来。',
    prompt: '请先结合本地知识库，再调用工具分析贵州茅台当前护城河、主要风险和跟踪指标',
    mode: 'tools',
  },
]
const SUGGESTION_CARD_COUNT = 6
const suggestionCards = ref(suggestionCardsPool.slice(0, SUGGESTION_CARD_COUNT))

const hasMessages = computed(() => messages.value.length > 0)
const hasRecentSessions = computed(() => recentSessions.value.length > 0)
const charCount = computed(() => draft.value.length)
const canSubmit = computed(() => draft.value.trim().length > 0 && !pending.value)
const canGenerateReport = computed(
  () =>
    !pending.value &&
    (draft.value.trim().length > 0 || Boolean(findLatestCompletedAssistantMessage()?.content?.trim())),
)
const canSaveModel = computed(
  () =>
    modelForm.name.trim() &&
    modelForm.baseUrl.trim() &&
    modelForm.modelId.trim() &&
    modelForm.apiKey.trim() &&
    !savingModel.value,
)
const currentSessionOwnedByThisClient = computed(
  () => Boolean(chatId.value) && activeLeaseChatId.value === chatId.value && Boolean(activeLeaseToken.value),
)
const currentSessionOccupiedByOtherClient = computed(
  () => occupiedSessionChatIds.value.includes(chatId.value),
)

const statusText = computed(() => {
  if (pending.value) {
    return pendingStatus.value?.label || '正在回答'
  }

  if (loadingHistory.value) {
    return '正在加载会话'
  }

  if (currentSessionOccupiedByOtherClient.value) {
    return '会话正在使用'
  }

  if (currentSessionOwnedByThisClient.value) {
    return '当前会话已占用'
  }

  return '可开始分析'
})
const statusDetailText = computed(() => {
  if (pending.value) {
    return pendingStatus.value?.detail || 'AI 正在处理你的问题，请稍候。'
  }

  if (loadingHistory.value) {
    return '正在从本地历史记录中恢复最近一次会话内容。'
  }

  if (currentSessionOccupiedByOtherClient.value) {
    return SESSION_OCCUPIED_MESSAGE
  }

  if (currentSessionOwnedByThisClient.value) {
    return '当前浏览器已占用该会话，其他客户端暂时不能同时进入，你可以继续提问或生成报告。'
  }

  return '当前会话空闲，可以开始提问；进入后会自动为你占用该会话。'
})

const currentModeOption = computed(
  () => chatModeOptions.find((item) => item.id === currentMode.value) || chatModeOptions[0],
)
const currentModeLabel = computed(() => currentModeOption.value?.label || '知识 + 工具')
const currentAdvisorStyleOption = computed(
  () => advisorStyleOptions.find((item) => item.id === currentAdvisorStyle.value) || advisorStyleOptions[0],
)
const advisorStyleAvailable = computed(() => currentMode.value === 'tools')
const advisorStyleEnabled = computed(() => currentAdvisorStyle.value === buffettToggleAdvisorStyle)
const advisorStyleHint = computed(() =>
  advisorStyleEnabled.value
    ? currentAdvisorStyleOption.value.description
    : '知识 + 工具会先结合本地知识库，再按需要调用搜索、行情和财报工具补充事实。',
)
const composerLeadText = computed(() =>
  currentMode.value === 'value'
    ? '当前模式会先做近三年财务体检'
    : '当前默认是知识 + 工具模式',
)
const composerNote = computed(
  () =>
    '本项目仅供学习、研究与功能测试使用，展示的财报拆解、知识检索、工具查询和 AI 生成内容仅作参考，不构成任何投资建议、收益承诺或具体买卖依据；涉及真实交易前，请务必结合公司公告、审计财报及自身风险承受能力独立判断。',
)
const currentSessionTitle = computed(() => {
  const titleFromMessages = findSessionTitle(messages.value)
  if (titleFromMessages) {
    return titleFromMessages
  }

  const currentSession = recentSessions.value.find((item) => item.chatId === chatId.value)
  return normalizeSessionTitle(currentSession?.preview) || '等待第一条问题'
})

const lastUpdatedAt = computed(() => {
  const current = recentSessions.value.find((item) => item.chatId === chatId.value)
  return current?.lastUpdatedAt ? formatClockTime(current.lastUpdatedAt) : ''
})
const currentModelOption = computed(
  () => availableModels.value.find((item) => item.id === selectedModelId.value) || availableModels.value[0] || null,
)
const currentModelName = computed(() => {
  if (loadingModels.value) {
    return '加载模型中'
  }

  return currentModelOption.value?.name || '未配置模型'
})
const mainHeaderTitle = computed(() => currentSessionTitle.value)
let stopAssistantWaitingTicker = null
let sessionHeartbeatTimer = null
let sessionHeartbeatInFlight = false
let ensureChatSessionSequence = 0
let historyRequestSequence = 0
let historyAbortController = null
let transientErrorTimer = null
const recentSessionMessageCache = new Map()
const sessionViewCache = new Map() // chatId → { messages, draft, scrollTop, updatedAt }
const fallbackToolLoopLimit = Object.freeze({
  maxRounds: 20,
  maxCallsPerRound: 0,
  maxTotalCalls: 200,
})

function normalizeToolLoopLimit(rawConfig) {
  const maxRounds = Number(rawConfig?.maxRounds)
  const maxCallsPerRound = Number(rawConfig?.maxCallsPerRound)
  const maxTotalCalls = Number(rawConfig?.maxTotalCalls)

  if (
    !Number.isFinite(maxRounds) ||
    !Number.isFinite(maxCallsPerRound) ||
    !Number.isFinite(maxTotalCalls) ||
    maxRounds <= 0 ||
    maxCallsPerRound < 0 ||
    maxTotalCalls <= 0
  ) {
    return null
  }

  return {
    maxRounds,
    maxCallsPerRound,
    maxTotalCalls,
  }
}

function resolveToolLoopLimit(modeId) {
  if (modeId === 'value') {
    return runtimeConfig.value.valueAnalysis || fallbackToolLoopLimit
  }

  if (modeId === 'tools') {
    return runtimeConfig.value.toolsMode || fallbackToolLoopLimit
  }

  return null
}

function formatToolLoopNote(loopLimit, prefix = '已启用循环式补查') {
  if (!loopLimit) {
    return ''
  }

  const perRoundText = loopLimit.maxCallsPerRound > 0
    ? `每轮最多 ${loopLimit.maxCallsPerRound} 次工具调用`
    : '每轮不限制次数'

  return `${prefix}：最多 ${loopLimit.maxRounds} 轮，${perRoundText}，合计最多 ${loopLimit.maxTotalCalls} 次。`
}

function shuffleSuggestionCards(cards) {
  const nextCards = [...cards]

  for (let index = nextCards.length - 1; index > 0; index -= 1) {
    const swapIndex = Math.floor(Math.random() * (index + 1))
    ;[nextCards[index], nextCards[swapIndex]] = [nextCards[swapIndex], nextCards[index]]
  }

  return nextCards
}

function refreshSuggestionCards() {
  suggestionCards.value = shuffleSuggestionCards(suggestionCardsPool).slice(0, SUGGESTION_CARD_COUNT)
}

function buildAssistantWaitingPreset(modeId) {
  if (modeId === 'value') {
    const loopLimit = resolveToolLoopLimit(modeId)
    return {
      title: '正在执行价值分析',
      detail: '先识别分析标的，再检索本地知识库，并按财报缺口自动补查数据。',
      note: formatToolLoopNote(loopLimit),
      steps: [
        '识别标的与关键财务问题',
        '检索本地知识库与方法资料',
        '补查财报、债务、现金流与资产质量',
        '如仍缺关键数据，继续补查同行与公告',
        '汇总证据并开始生成结论',
      ],
    }
  }

  if (modeId === 'tools') {
    const loopLimit = resolveToolLoopLimit(modeId)
    return {
      title: '正在规划并调用工具',
      detail: '系统会先判断缺什么信息，再补查搜索、行情和财报数据。',
      note: formatToolLoopNote(loopLimit, '当前工具代理上限'),
      steps: [
        '读取本地知识库上下文',
        '规划本轮需要的工具',
        '执行搜索、行情与财报查询',
        '必要时继续补查剩余缺口',
        '整理事实并生成回答',
      ],
    }
  }

  return {
    title: '正在检索知识库',
    detail: '系统会先结合本地知识库命中的资料，再开始组织回答。',
    note: '知识库命中越多，首段正文出现前的等待通常会更长一些。',
    steps: [
      '检索本地知识库片段',
      '整理命中资料与问题上下文',
      '开始生成回答正文',
    ],
  }
}

async function loadRuntimeConfig() {
  try {
    const response = await getRuntimeConfig()
    runtimeConfig.value = {
      toolsMode: normalizeToolLoopLimit(response?.toolsMode),
      valueAnalysis: normalizeToolLoopLimit(response?.valueAnalysis),
    }
  } catch (error) {
    console.warn('加载运行时配置失败，已回退到前端默认上限。', error)
  }
}

function refreshPendingStatus(label, detail) {
  pendingStatus.value = {
    label,
    detail,
  }
}

function applyAssistantWaitingStep(message, preset, activeIndex) {
  const normalizedIndex = Math.max(0, Math.min(activeIndex, preset.steps.length - 1))
  const decoratedSteps = preset.steps.map((label, index) => ({
    id: `${message.id}-waiting-${index}`,
    label,
    state: index < normalizedIndex ? 'done' : index === normalizedIndex ? 'active' : 'pending',
  }))

  message.waitingTitle = preset.title
  message.waitingDetail = preset.detail
  message.waitingNote = preset.note
  message.waitingSteps = decoratedSteps
  message.waitingPhase = 'preparing'

  refreshPendingStatus(preset.steps[normalizedIndex] || preset.title, preset.detail)
  refreshMessageRender(message)
}

function startAssistantWaitingTicker(message, modeId) {
  stopAssistantWaitingTickerSafely()

  const preset = buildAssistantWaitingPreset(modeId)
  let activeIndex = 0

  applyAssistantWaitingStep(message, preset, activeIndex)

  if (typeof window === 'undefined' || preset.steps.length <= 1) {
    return () => {
      clearAssistantWaitingState(message)
    }
  }

  const timerId = window.setInterval(() => {
    if (!message.streaming || String(message.content || '').trim()) {
      window.clearInterval(timerId)
      return
    }

    activeIndex = Math.min(activeIndex + 1, preset.steps.length - 1)
    applyAssistantWaitingStep(message, preset, activeIndex)
  }, ASSISTANT_WAITING_STEP_INTERVAL)

  return () => {
    window.clearInterval(timerId)
    clearAssistantWaitingState(message)
  }
}

function stopAssistantWaitingTickerSafely() {
  if (typeof stopAssistantWaitingTicker === 'function') {
    stopAssistantWaitingTicker()
  }

  stopAssistantWaitingTicker = null
}

function promoteAssistantToAnswering(message) {
  if (!message || message.waitingPhase === 'answering') {
    return
  }

  message.waitingTitle = '正在生成回答'
  message.waitingDetail = '资料已整理完成，正在把结论、依据和表格写成正文。'
  message.waitingNote = ''
  message.waitingSteps = []
  message.waitingPhase = 'answering'

  refreshPendingStatus('正在生成回答', message.waitingDetail)
  refreshMessageRender(message)
}

function clearAssistantWaitingState(message) {
  if (message) {
    message.waitingTitle = ''
    message.waitingDetail = ''
    message.waitingNote = ''
    message.waitingSteps = []
    message.waitingPhase = ''
  }

  if (!pending.value) {
    pendingStatus.value = null
  }
}

function readStoredModelId() {
  if (typeof window === 'undefined') {
    return ''
  }

  try {
    return window.localStorage.getItem(CHAT_MODEL_STORAGE_KEY) || ''
  } catch {
    return ''
  }
}

function persistSelectedModelId(modelId) {
  if (typeof window === 'undefined') {
    return
  }

  try {
    if (modelId) {
      window.localStorage.setItem(CHAT_MODEL_STORAGE_KEY, modelId)
      return
    }

    window.localStorage.removeItem(CHAT_MODEL_STORAGE_KEY)
  } catch {
    // Ignore storage failures and keep the in-memory selection.
  }
}

function isSessionLeaseError(error) {
  return [409, 423].includes(Number(error?.status))
}

function getSessionLeaseReason(error) {
  return String(error?.payload?.details?.reason || '').trim()
}

function isSessionOccupiedError(error) {
  return Number(error?.status) === 423 || getSessionLeaseReason(error) === 'occupied'
}

function shouldShowSessionLeaseError(error) {
  return isSessionOccupiedError(error)
}

function hasManagedLeaseExpired(targetChatId = chatId.value) {
  if (
    !targetChatId ||
    activeLeaseChatId.value !== targetChatId ||
    !activeLeaseToken.value ||
    !activeLeaseExpiresAt.value
  ) {
    return false
  }

  const expiresAt = Date.parse(activeLeaseExpiresAt.value)
  if (!Number.isFinite(expiresAt)) {
    return false
  }

  return expiresAt <= Date.now() + SESSION_LEASE_REFRESH_BUFFER_MS
}

function markSessionOccupied(targetChatId) {
  if (!targetChatId || occupiedSessionChatIds.value.includes(targetChatId)) {
    return
  }

  occupiedSessionChatIds.value = [...occupiedSessionChatIds.value, targetChatId]
}

function clearSessionOccupied(targetChatId = '') {
  if (!targetChatId) {
    occupiedSessionChatIds.value = []
    return
  }

  occupiedSessionChatIds.value = occupiedSessionChatIds.value.filter((item) => item !== targetChatId)
}

function syncSessionOccupiedState(targetChatId, error) {
  if (isSessionOccupiedError(error)) {
    markSessionOccupied(targetChatId)
    return
  }

  clearSessionOccupied(targetChatId)
}

function resolveSessionLeaseMessage(error) {
  if (isSessionOccupiedError(error)) {
    return SESSION_OCCUPIED_MESSAGE
  }

  if (typeof error?.message === 'string' && error.message.trim()) {
    return error.message.trim()
  }

  return '当前会话访问失败，请重新打开会话后再试。'
}

function buildLeasePayload(targetChatId = chatId.value) {
  if (!targetChatId || activeLeaseChatId.value !== targetChatId || !activeLeaseToken.value) {
    return null
  }

  return {
    clientId: sessionClientId,
    leaseToken: activeLeaseToken.value,
  }
}

function stopSessionHeartbeat() {
  sessionHeartbeatInFlight = false

  if (typeof window === 'undefined' || !sessionHeartbeatTimer) {
    sessionHeartbeatTimer = null
    return
  }

  window.clearInterval(sessionHeartbeatTimer)
  sessionHeartbeatTimer = null
}

function applyActiveLease(lockResponse) {
  if (!lockResponse?.chatId || !lockResponse?.leaseToken) {
    return
  }

  clearSessionOccupied(lockResponse.chatId)
  activeLeaseChatId.value = lockResponse.chatId
  activeLeaseToken.value = lockResponse.leaseToken
  activeLeaseExpiresAt.value = lockResponse.expiresAt || ''
}

function clearActiveLease(targetChatId = '') {
  if (!targetChatId || activeLeaseChatId.value === targetChatId) {
    stopSessionHeartbeat()
    activeLeaseChatId.value = ''
    activeLeaseToken.value = ''
    activeLeaseExpiresAt.value = ''
  }
}

function startSessionHeartbeat() {
  stopSessionHeartbeat()

  if (typeof window === 'undefined' || !activeLeaseChatId.value || !activeLeaseToken.value) {
    return
  }

  sessionHeartbeatTimer = window.setInterval(async () => {
    if (sessionHeartbeatInFlight) {
      return
    }

    const lockedChatId = activeLeaseChatId.value
    const lease = buildLeasePayload(lockedChatId)
    if (!lockedChatId || !lease) {
      return
    }

    sessionHeartbeatInFlight = true

    try {
      const response = await heartbeatSessionLock(lockedChatId, lease)
      if (activeLeaseChatId.value === lockedChatId && activeLeaseToken.value === response?.leaseToken) {
        activeLeaseExpiresAt.value = response?.expiresAt || activeLeaseExpiresAt.value
      }
    } catch (error) {
      if (isSessionLeaseError(error)) {
        syncSessionOccupiedState(lockedChatId, error)
        clearActiveLease(lockedChatId)

        if (chatId.value === lockedChatId && shouldShowSessionLeaseError(error)) {
          errorMessage.value = resolveSessionLeaseMessage(error)
        }
      } else {
        console.warn('会话续租失败，将在下个心跳周期继续尝试。', error)
      }
    } finally {
      sessionHeartbeatInFlight = false
    }
  }, SESSION_HEARTBEAT_INTERVAL)
}

async function acquireTemporaryLease(targetChatId, options = {}) {
  if (!targetChatId) {
    return null
  }

  const activeLease = buildLeasePayload(targetChatId)
  if (activeLease) {
    return activeLease
  }

  try {
    const response = await acquireSessionLock(targetChatId, sessionClientId)
    clearSessionOccupied(targetChatId)
    return {
      clientId: sessionClientId,
      leaseToken: response.leaseToken,
    }
  } catch (error) {
    syncSessionOccupiedState(targetChatId, error)
    if (!options.silent && shouldShowSessionLeaseError(error)) {
      errorMessage.value = resolveSessionLeaseMessage(error)
    }
    return null
  }
}

async function ensureActiveLease(targetChatId, options = {}) {
  if (!targetChatId) {
    return null
  }

  if (hasManagedLeaseExpired(targetChatId)) {
    clearActiveLease(targetChatId)
  }

  const existingLease = buildLeasePayload(targetChatId)
  if (existingLease) {
    return existingLease
  }

  const previousChatId = activeLeaseChatId.value
  const previousLease = buildLeasePayload(previousChatId)

  try {
    const response = await acquireSessionLock(targetChatId, sessionClientId)
    applyActiveLease(response)
    startSessionHeartbeat()

    if (previousLease && previousChatId && previousChatId !== targetChatId) {
      void releaseSessionLock(previousChatId, previousLease).catch(() => {})
    }

    return buildLeasePayload(targetChatId)
  } catch (error) {
    syncSessionOccupiedState(targetChatId, error)
    if (!options.silent && shouldShowSessionLeaseError(error)) {
      errorMessage.value = resolveSessionLeaseMessage(error)
    }
    return null
  }
}

async function executeWithManagedLease(targetChatId, requestTask) {
  const lease = await ensureActiveLease(targetChatId)
  if (!lease) {
    throw new Error(errorMessage.value || '当前会话暂时不可用，请稍后再试。')
  }

  try {
    return await requestTask(lease)
  } catch (error) {
    if (isSessionLeaseError(error) && !isSessionOccupiedError(error)) {
      clearActiveLease(targetChatId)
      const renewedLease = await ensureActiveLease(targetChatId, { silent: true })
      if (renewedLease) {
        return requestTask(renewedLease)
      }

      if (occupiedSessionChatIds.value.includes(targetChatId)) {
        throw new Error(SESSION_OCCUPIED_MESSAGE)
      }

      throw new Error('当前会话暂时不可用，请稍后再试。')
    }

    throw error
  }
}

async function releaseManagedLease(options = {}) {
  const targetChatId = activeLeaseChatId.value
  const lease = buildLeasePayload(targetChatId)

  clearActiveLease(targetChatId)

  if (!targetChatId || !lease) {
    return
  }

  try {
    await releaseSessionLock(targetChatId, lease, {
      keepalive: Boolean(options.keepalive),
    })
    clearSessionOccupied(targetChatId)
  } catch (error) {
    if (!options.silent) {
      errorMessage.value = error.message || '释放会话占用失败，请稍后重试。'
    }
  }
}

async function releaseTemporaryLease(targetChatId, lease) {
  if (!targetChatId || !lease) {
    return
  }

  if (activeLeaseChatId.value === targetChatId && activeLeaseToken.value === lease.leaseToken) {
    return
  }

  try {
    await releaseSessionLock(targetChatId, lease)
    clearSessionOccupied(targetChatId)
  } catch {
    // Best effort only. Expiration will eventually reclaim the lock.
  }
}

function stopTransientErrorTimer() {
  if (typeof window === 'undefined' || !transientErrorTimer) {
    transientErrorTimer = null
    return
  }

  window.clearTimeout(transientErrorTimer)
  transientErrorTimer = null
}

function createSessionClientId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `web_${crypto.randomUUID().replace(/-/g, '').slice(0, 24)}`
  }

  return `web_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

function readOrCreateSessionClientId() {
  const fallbackClientId = createSessionClientId()

  if (typeof window === 'undefined') {
    return fallbackClientId
  }

  try {
    const storedClientId = window.sessionStorage.getItem(CHAT_SESSION_CLIENT_ID_STORAGE_KEY)
    if (storedClientId) {
      return storedClientId
    }

    window.sessionStorage.setItem(CHAT_SESSION_CLIENT_ID_STORAGE_KEY, fallbackClientId)
    return fallbackClientId
  } catch {
    return fallbackClientId
  }
}

function resetModelForm() {
  modelForm.name = ''
  modelForm.baseUrl = ''
  modelForm.modelId = ''
  modelForm.apiKey = ''
}

function buildMessage(payload) {
  return {
    id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    renderVersion: 0,
    timestamp: new Date().toISOString(),
    ...payload,
  }
}

function createReactiveMessage(payload) {
  return reactive(buildMessage(payload))
}

function cloneMessagePayload(message) {
  return {
    role: message.role,
    content: message.content,
    trace: message.trace || null,
    kind: message.kind || undefined,
    title: message.title || undefined,
    reportFileName: message.reportFileName || '',
    reportDownloadPath: message.reportDownloadPath || '',
    reportStatus: message.reportStatus || '',
    reportTaskId: message.reportTaskId || '',
    reportError: message.reportError || '',
    mode: message.mode || undefined,
    advisorStyle: message.advisorStyle || undefined,
    timestamp: message.timestamp,
  }
}

function hydrateCachedMessages(cachedMessages = []) {
  return cachedMessages.map((item) => createReactiveMessage(cloneMessagePayload(item)))
}

function setRecentSessionMessageCache(targetChatId, sessionMessages = []) {
  if (!targetChatId) {
    return
  }

  if (recentSessionMessageCache.has(targetChatId)) {
    recentSessionMessageCache.delete(targetChatId)
  }

  recentSessionMessageCache.set(targetChatId, {
    messages: sessionMessages.map(cloneMessagePayload),
    updatedAt: Date.now(),
  })

  if (recentSessionMessageCache.size > RECENT_SESSION_CACHE_LIMIT) {
    const oldestChatId = recentSessionMessageCache.keys().next().value
    recentSessionMessageCache.delete(oldestChatId)
  }
}

function renderCachedSessionMessages(targetChatId) {
  const cached = recentSessionMessageCache.get(targetChatId)
  if (!cached) {
    return false
  }

  messages.value = hydrateCachedMessages(cached.messages)
  scrollToBottom()
  return true
}

function clearHistoryRequest() {
  if (historyAbortController) {
    historyAbortController.abort()
  }

  historyAbortController = null
}

function isAbortError(error) {
  return error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED'
}

async function pollReportTask(taskId, maxAttempts = 120, delayMs = 1000) {
  let lastResponse = null

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    lastResponse = await executeWithManagedLease(chatId.value, (lease) =>
      getReportTask(taskId, chatId.value, lease),
    )

    if (lastResponse?.status === 'SUCCESS') {
      return lastResponse
    }

    if (lastResponse?.status === 'FAILED') {
      throw new Error(lastResponse.error || '报告生成失败，请稍后重试。')
    }

    await new Promise((resolve) => window.setTimeout(resolve, delayMs))
  }

  throw new Error(lastResponse?.error || '报告生成超时，请稍后刷新会话查看结果。')
}

function normalizeSessionTitle(value = '') {
  return String(value).replace(/\s+/g, ' ').trim()
}

function normalizeRecentSession(item) {
  if (!item || !isValidChatId(item.chatId)) {
    return null
  }

  return {
    chatId: item.chatId,
    preview: normalizeSessionTitle(item.preview) || '等待第一条问题',
    lastUpdatedAt: item.lastUpdatedAt || '',
    messageCount: Number.isFinite(item.messageCount) ? item.messageCount : 0,
  }
}

function extractDisplayUserContent(content = '') {
  const normalized = String(content || '').replace(/\r\n?/g, '\n').trim()

  if (!normalized) {
    return ''
  }

  const extractedFromSections = extractContentBetweenMarkers(normalized, [
    {
      start: '用户原始问题：',
      endPatterns: ['本轮分析标的：', '分析范围提醒：', '当前仍缺的重要数据：'],
    },
    {
      start: '用户问题：',
      endPatterns: ['回答要求：', '本地知识库（偏方法论，不代表实时事实）：', '实时工具证据：', '工具结果如下：'],
    },
  ])

  if (extractedFromSections) {
    return extractedFromSections
  }

  if (/read the question again:/i.test(normalized)) {
    return normalized.split(/\n+\s*Read the question again:\s*/i)[0].trim()
  }

  return normalized
}

function extractContentBetweenMarkers(content, markers = []) {
  for (const marker of markers) {
    const startIndex = content.indexOf(marker.start)

    if (startIndex === -1) {
      continue
    }

    const fromStart = content.slice(startIndex + marker.start.length).trimStart()
    const matchedEndIndex = marker.endPatterns
      .map((pattern) => fromStart.indexOf(pattern))
      .filter((index) => index >= 0)
      .sort((left, right) => left - right)[0]

    const extracted = (matchedEndIndex === undefined ? fromStart : fromStart.slice(0, matchedEndIndex)).trim()
    if (extracted) {
      return extracted
    }
  }

  return ''
}

function normalizeTraceText(value) {
  return String(value || '').replace(/\r\n?/g, '\n').trim()
}

function isFinanceOnlyRejectionMessage(value) {
  return normalizeTraceText(value) === FINANCE_ONLY_REJECTION_MESSAGE
}

function normalizeTrace(trace) {
  if (!trace || typeof trace !== 'object') {
    return null
  }

  const toolCalls = Array.isArray(trace.toolCalls)
    ? trace.toolCalls
      .map((item) => {
        if (!item || typeof item !== 'object') {
          return null
        }

        const normalized = {
          title: normalizeTraceText(item.title),
          toolName: normalizeTraceText(item.toolName),
          arguments: normalizeTraceText(item.arguments),
          outputPreview: normalizeTraceText(item.outputPreview),
        }

        return Object.values(normalized).some(Boolean) ? normalized : null
      })
      .filter(Boolean)
    : []

  const ragReferences = Array.isArray(trace.ragReferences)
    ? trace.ragReferences
      .map((item) => {
        if (!item || typeof item !== 'object') {
          return null
        }

        const normalized = {
          source: normalizeTraceText(item.source),
          snippet: normalizeTraceText(item.snippet),
        }

        return Object.values(normalized).some(Boolean) ? normalized : null
      })
      .filter(Boolean)
    : []

  if (!toolCalls.length && !ragReferences.length) {
    return null
  }

  return {
    toolCalls,
    ragReferences,
  }
}

function normalizeHistoryMessage(item) {
  if (!item || !['USER', 'ASSISTANT', 'SYSTEM'].includes(item.role)) {
    return null
  }

  const normalizedKind = String(item.kind || '').trim()
  const normalizedTitle = String(item.title || '').trim()
  const normalizedReportFileName = String(item.reportFileName || '').trim()
  const normalizedReportDownloadPath = String(item.reportDownloadPath || '').trim()
  const normalizedReportStatus = String(item.reportStatus || '').trim()
  const normalizedReportTaskId = String(item.reportTaskId || '').trim()
  const normalizedReportError = String(item.reportError || '').trim()
  const normalizedContent = item.role === 'USER'
    ? extractDisplayUserContent(item.content)
    : String(item.content || '').trim()

  if (!normalizedContent && normalizedKind !== 'report') {
    return null
  }

  return {
    role: item.role,
    content: normalizedContent,
    trace: item.role === 'ASSISTANT' ? normalizeTrace(item.trace) : null,
    kind: normalizedKind,
    title: normalizedTitle,
    reportFileName: normalizedReportFileName,
    reportDownloadPath: normalizedReportDownloadPath,
    reportStatus: normalizedReportStatus,
    reportTaskId: normalizedReportTaskId,
    reportError: normalizedReportError,
  }
}

function findLatestCompletedAssistantMessage() {
  return [...messages.value]
    .reverse()
    .find(
      (item) =>
        item?.role === 'ASSISTANT' &&
        item?.kind !== 'report' &&
        !item?.streaming &&
        typeof item?.content === 'string' &&
        item.content.trim(),
    ) || null
}

function findLatestUserPrompt() {
  const latestUserMessage = [...messages.value]
    .reverse()
    .find((item) => item?.role === 'USER' && typeof item?.content === 'string' && item.content.trim())

  return latestUserMessage?.content?.trim() || ''
}

function findSessionTitle(sessionMessages = []) {
  const firstUserMessage = sessionMessages.find(
    (item) => item.role === 'USER' && typeof item.content === 'string' && item.content.trim(),
  )

  if (firstUserMessage) {
    return normalizeSessionTitle(firstUserMessage.content)
  }

  const firstTextMessage = sessionMessages.find(
    (item) => typeof item.content === 'string' && item.content.trim(),
  )

  return normalizeSessionTitle(firstTextMessage?.content)
}

function refreshMessageRender(targetMessage) {
  if (!targetMessage?.id) {
    return
  }

  targetMessage.renderVersion = (targetMessage.renderVersion || 0) + 1
  messages.value = [...messages.value]
}

async function triggerReportDownload(downloadPath, fileName, options = {}) {
  if (!downloadPath) {
    return false
  }

  try {
    await downloadReportFile(downloadPath, fileName || '结构化投资报告.pdf')
    return true
  } catch (error) {
    if (!options.silent) {
      errorMessage.value = error.message || 'PDF 下载失败，请稍后重试。'
    }
    throw error
  }
}

async function handleReportDownload(payload) {
  if (!payload?.downloadPath) {
    return
  }

  try {
    await triggerReportDownload(payload.downloadPath, payload.fileName)
  } catch {
    // The page-level error message is already set in triggerReportDownload.
  }
}

async function syncAssistantMessageFromHistory(chatSessionId, targetMessage) {
  if (!chatSessionId || !targetMessage) {
    return false
  }

  const lease = await ensureActiveLease(chatSessionId, { silent: true })
  if (!lease) {
    return false
  }

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await getHistory(chatSessionId, lease)
      const latestAssistant = [...(response.messages || [])]
        .reverse()
        .find((item) => item.role === 'ASSISTANT' && typeof item.content === 'string')

      if (!latestAssistant?.content?.trim()) {
        await wait(120)
        continue
      }

      targetMessage.content = latestAssistant.content
      targetMessage.trace = latestAssistant.trace || null
      targetMessage.timestamp = response.timestamp || targetMessage.timestamp
      refreshMessageRender(targetMessage)
      return true
    } catch (error) {
      if (isSessionLeaseError(error)) {
        return false
      }

      await wait(120)
    }
  }

  return false
}

function scrollToBottom() {
  nextTick(() => {
    if (!messageViewport.value) {
      return
    }

    messageViewport.value.scrollTop = messageViewport.value.scrollHeight
  })
}

async function loadHistory(chatSessionId, options = {}) {
  const requestId = ++historyRequestSequence
  const hasCache = options.preferCache !== false && renderCachedSessionMessages(chatSessionId)

  if (!keepMessagesOnRefresh.value) {
    loadingHistory.value = true
  }
  errorMessage.value = ''

  if (!hasCache) {
    messages.value = []
  }

  clearHistoryRequest()
  historyAbortController = typeof AbortController === 'function' ? new AbortController() : null

  try {
    const lease = await ensureActiveLease(chatSessionId, { silent: true })
    if (requestId !== historyRequestSequence || chatSessionId !== chatId.value) {
      return
    }

    if (!lease) {
      return
    }

    const response = await getHistory(chatSessionId, lease, {
      signal: historyAbortController?.signal,
    })

    if (requestId !== historyRequestSequence || chatSessionId !== chatId.value) {
      return
    }

    const normalizedMessages = (response.messages || [])
      .map(normalizeHistoryMessage)
      .filter(Boolean)

    const nextMessages = normalizedMessages.map((item) =>
      createReactiveMessage({
        role: item.role,
        content: item.content,
        trace: item.trace,
        kind: item.kind || undefined,
        title: item.title || undefined,
        reportFileName: item.reportFileName || '',
        reportDownloadPath: item.reportDownloadPath || '',
        reportStatus: item.reportStatus || '',
        reportTaskId: item.reportTaskId || '',
        reportError: item.reportError || '',
        timestamp: response.timestamp,
      }),
    )

    messages.value = nextMessages
    setRecentSessionMessageCache(chatSessionId, nextMessages)
  } catch (error) {
    if (isAbortError(error) || requestId !== historyRequestSequence || chatSessionId !== chatId.value) {
      return
    }

    if (error.status === 404) {
      await refreshRecentSessions({ silent: true })
    }
    if (error.status !== 404) {
      errorMessage.value = error.message || '历史记录加载失败'
    }
  } finally {
    keepMessagesOnRefresh.value = false
    if (requestId === historyRequestSequence) {
      loadingHistory.value = false
      historyAbortController = null
      scrollToBottom()
    }
  }
}

async function ensureChatSession(routeChatId) {
  const sequenceId = ++ensureChatSessionSequence
  const nextChatId = isValidChatId(routeChatId) ? routeChatId : createChatId('fund')
  const previousChatId = chatId.value

  if (route.query.chatId !== nextChatId) {
    await router.replace({
      path: route.path,
      query: {
        ...route.query,
        chatId: nextChatId,
      },
    })

    if (sequenceId !== ensureChatSessionSequence) {
      return
    }
  }

  const lease = await ensureActiveLease(nextChatId)
  if (sequenceId !== ensureChatSessionSequence) {
    return
  }

  if (!lease) {
    if (previousChatId && previousChatId !== nextChatId) {
      if (route.query.chatId !== previousChatId) {
        await router.replace({
          path: route.path,
          query: {
            ...route.query,
            chatId: previousChatId,
          },
        })
      }

      return
    }

    const fallbackChatId = createChatId('fund')
    if (route.query.chatId !== fallbackChatId) {
      await router.replace({
        path: route.path,
        query: {
          ...route.query,
          chatId: fallbackChatId,
        },
      })
    }
    return
  }

  chatId.value = nextChatId
  await loadHistory(nextChatId)
}

async function refreshRecentSessions(options = {}) {
  try {
    const response = await getHistorySessions()
    recentSessions.value = Array.isArray(response?.sessions)
      ? response.sessions.map(normalizeRecentSession).filter(Boolean)
      : []
  } catch (error) {
    if (!options.silent) {
      errorMessage.value = error.message || '会话列表加载失败'
    }
  }
}

function focusComposer() {
  composerRef.value?.focus()
}

function closeModeMenu() {
  modeMenuOpen.value = false
}

function openAboutDialog() {
  closeSessionMenu()
}

function closeAboutDialog() {
  aboutDialogOpen.value = false
}

function openModelDialog() {
  modelDialogOpen.value = true
  aboutDialogOpen.value = false
  closeModeMenu()
  closeSessionMenu()
}

function closeModelDialog() {
  modelDialogOpen.value = false
  modelErrorMessage.value = ''
  resetModelForm()
}

function closeSessionMenu() {
  sessionMenu.value = {
    open: false,
    chatId: '',
    preview: '',
    x: 0,
    y: 0,
  }
}

function closeMobileSidebar() {
  mobileSidebarOpen.value = false
}

function openChatWorkspace() {
  activeWorkspace.value = 'chat'
}

function toggleMobileSidebar() {
  mobileSidebarOpen.value = !mobileSidebarOpen.value
}

function toggleModeMenu() {
  modeMenuOpen.value = !modeMenuOpen.value
}

function selectMode(modeId) {
  currentMode.value = modeId
  closeModeMenu()
  focusComposer()
}

async function loadModels(preferredModelId = '') {
  loadingModels.value = true
  modelErrorMessage.value = ''

  try {
    const response = await getModels()
    const nextModels = Array.isArray(response.models) ? response.models : []
    availableModels.value = nextModels

    const candidateIds = [
      preferredModelId,
      selectedModelId.value,
      response.defaultModelId,
      nextModels[0]?.id,
    ].filter(Boolean)
    const nextSelectedModelId = candidateIds.find((candidate) =>
      nextModels.some((item) => item.id === candidate),
    )

    selectedModelId.value = nextSelectedModelId || ''
  } catch (error) {
    availableModels.value = []
    selectedModelId.value = ''
    modelErrorMessage.value = error.message || '模型列表加载失败，请稍后重试。'
  } finally {
    loadingModels.value = false
  }
}

async function submitModelForm() {
  if (!canSaveModel.value) {
    return
  }

  savingModel.value = true
  modelErrorMessage.value = ''

  try {
    const createdModel = await createModel({
      name: modelForm.name.trim(),
      baseUrl: modelForm.baseUrl.trim(),
      apiKey: modelForm.apiKey.trim(),
      modelId: modelForm.modelId.trim(),
    })

    await loadModels(createdModel?.id)
    closeModelDialog()
    focusComposer()
  } catch (error) {
    modelErrorMessage.value = error.message || '新增模型失败，请稍后重试。'
  } finally {
    savingModel.value = false
  }
}

function toggleAdvisorStyle() {
  if (!advisorStyleAvailable.value) {
    return
  }

  currentAdvisorStyle.value = advisorStyleEnabled.value
    ? defaultAdvisorStyle
    : buffettToggleAdvisorStyle
  focusComposer()
}

function handleModePickerFocusOut(event) {
  if (event.currentTarget?.contains(event.relatedTarget)) {
    return
  }

  closeModeMenu()
}

function openSessionMenu(event, session) {
  if (!session?.chatId) {
    return
  }

  const padding = 12

  sessionMenu.value = {
    open: true,
    chatId: session.chatId,
    preview: session.preview,
    x: Math.max(padding, event.clientX),
    y: Math.max(padding, event.clientY),
  }

  nextTick(() => {
    const menuRect = sessionMenuRef.value?.getBoundingClientRect()

    if (!menuRect || sessionMenu.value.chatId !== session.chatId) {
      return
    }

    sessionMenu.value = {
      ...sessionMenu.value,
      x: Math.max(padding, Math.min(event.clientX, window.innerWidth - menuRect.width - padding)),
      y: Math.max(padding, Math.min(event.clientY, window.innerHeight - menuRect.height - padding)),
    }
  })
}

async function openSession(targetChatId) {
  openChatWorkspace()
  if (!targetChatId || pending.value) {
    return
  }

  if (targetChatId === chatId.value && buildLeasePayload(targetChatId)) {
    return
  }

  closeSessionMenu()
  closeMobileSidebar()

  if (targetChatId === chatId.value) {
    await ensureChatSession(targetChatId)
    return
  }

  // 方案四 Lite：切换前缓存当前会话状态
  const currentChatId = chatId.value
  if (currentChatId && messages.value.length > 0) {
    sessionViewCache.set(currentChatId, {
      messages: [...messages.value],
      draft: draft.value,
      scrollTop: messageViewport.value?.scrollTop ?? 0,
      updatedAt: Date.now(),
    })
    if (sessionViewCache.size > 1000) {
      const oldest = [...sessionViewCache.entries()].sort((a, b) => a[1].updatedAt - b[1].updatedAt)[0]
      if (oldest) sessionViewCache.delete(oldest[0])
    }
  }

  clearHistoryRequest()
  errorMessage.value = ''

  loadingHistory.value = true

    // 尝试从视图缓存恢复（0ms 切换，保留滚动位置）
  const viewCached = sessionViewCache.get(targetChatId)
  if (viewCached) {
    chatId.value = targetChatId
    messages.value = viewCached.messages
    draft.value = viewCached.draft
    loadingHistory.value = false
    keepMessagesOnRefresh.value = true
    await router.push({
      path: route.path,
      query: { ...route.query, chatId: targetChatId },
    })
    await nextTick()
    if (messageViewport.value) {
      messageViewport.value.scrollTop = viewCached.scrollTop
    }
    await ensureChatSession(targetChatId)
    return
  }

  // 尝试从消息缓存恢复
  chatId.value = targetChatId
  if (renderCachedSessionMessages(targetChatId)) {
    loadingHistory.value = false
  } else {
    messages.value = []
    loadingHistory.value = true
  }

  await router.push({
    path: route.path,
    query: { ...route.query, chatId: targetChatId },
  })

  await ensureChatSession(targetChatId)
}

async function createFreshSession() {
  openChatWorkspace()
  if (pending.value) {
    return
  }

  currentMode.value = 'tools'
  closeModeMenu()
  closeSessionMenu()
  closeMobileSidebar()
  draft.value = ''
  errorMessage.value = ''
  refreshSuggestionCards()

  await router.push({
    path: route.path,
    query: {
      ...route.query,
      chatId: createChatId('fund'),
    },
  })

  focusComposer()
}

async function deleteSession(targetChatId = sessionMenu.value.chatId) {
  if (!targetChatId || pending.value) {
    return
  }

  closeModeMenu()
  closeSessionMenu()
  closeMobileSidebar()
  errorMessage.value = ''
  const deletingCurrentSession = targetChatId === chatId.value
  const lease = deletingCurrentSession
    ? await ensureActiveLease(targetChatId)
    : await acquireTemporaryLease(targetChatId)

  if (!lease) {
    return
  }

  try {
    await deleteHistory(targetChatId, lease)
  } catch (error) {
    await releaseTemporaryLease(targetChatId, lease)

    if (error.status !== 404) {
      errorMessage.value = isSessionLeaseError(error)
        ? resolveSessionLeaseMessage(error)
        : (error.message || '删除会话失败，请稍后重试。')
      return
    }
  }

  if (!deletingCurrentSession) {
    await releaseTemporaryLease(targetChatId, lease)
  }

  recentSessionMessageCache.delete(targetChatId)
  await refreshRecentSessions({ silent: true })
  const nextSessions = recentSessions.value

  if (!deletingCurrentSession) {
    return
  }

  await releaseManagedLease({ silent: true })
  draft.value = ''
  messages.value = []
  const fallbackChatId = nextSessions[0]?.chatId || createChatId('fund')

  await router.push({
    path: route.path,
    query: {
      ...route.query,
      chatId: fallbackChatId,
    },
  })

  focusComposer()
}

function applyPrompt(item) {
  const prompt = typeof item === 'string' ? item : item?.prompt

  if (!prompt) {
    return
  }

  if (item?.mode) {
    currentMode.value = item.mode
  }

  draft.value = prompt
  closeSessionMenu()
  closeMobileSidebar()
  focusComposer()
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submitChat()
  }
}

async function submitChat() {
  const content = draft.value.trim()
  const advisorStyle = advisorStyleAvailable.value ? currentAdvisorStyle.value : defaultAdvisorStyle

  if (!content || pending.value) {
    return
  }

  closeModeMenu()
  closeSessionMenu()
  closeMobileSidebar()
  pending.value = true
  pendingStatus.value = null
  errorMessage.value = ''
  draft.value = ''

  try {
    await runAssistantReply(content, {
      modeId: currentMode.value,
      advisorStyle,
    })
  } finally {
    pending.value = false
    pendingStatus.value = null
    scrollToBottom()
  }
}

async function submitReport() {
  const content = draft.value.trim()
  let sourceContent = content ? '' : (findLatestCompletedAssistantMessage()?.content?.trim() || '')
  const reportQuery = content || findLatestUserPrompt() || currentSessionTitle.value
  const advisorStyle = advisorStyleAvailable.value ? currentAdvisorStyle.value : defaultAdvisorStyle

  if ((!reportQuery && !sourceContent) || pending.value) {
    return
  }

  closeModeMenu()
  closeSessionMenu()
  closeMobileSidebar()
  pending.value = true
  stopAssistantWaitingTickerSafely()
  errorMessage.value = ''
  draft.value = ''
  let reportMessage = null

  try {
    if (content) {
      refreshPendingStatus('正在回答问题', '系统会先完成正常分析回答，再把完整回答整理成正式 PDF 报告。')
      const assistantMessage = await runAssistantReply(content, {
        modeId: currentMode.value,
        advisorStyle,
      })
      if (!assistantMessage) {
        return
      }
      sourceContent = assistantMessage?.content?.trim() || ''

      if (!sourceContent) {
        throw new Error('未获取到可用于生成报告的完整回答，请稍后重试。')
      }
    }

    refreshPendingStatus(
      '正在生成报告',
      sourceContent
        ? '系统正在把本次完整回答整理为正式 PDF 报告。'
        : '系统正在把最近一条 AI 回答整理为正式 PDF 报告。',
    )

    reportMessage = createReactiveMessage({
      role: 'ASSISTANT',
      kind: 'report',
      title: '正在生成结构化投资报告',
      content: '',
      suggestions: [],
      reportFileName: '',
      reportDownloadPath: '',
      reportStatus: 'PROCESSING',
      reportTaskId: '',
      reportError: '',
      mode: 'REPORT',
      advisorStyle: defaultAdvisorStyle,
      streaming: true,
    })
    messages.value.push(reportMessage)
    scrollToBottom()

    const task = await executeWithManagedLease(chatId.value, (lease) =>
      createReportTask({
        chatId: chatId.value,
        message: reportQuery,
        userName: '用户',
        modelId: selectedModelId.value || undefined,
        advisorStyle,
        mode: currentMode.value,
        reportSourceContent: sourceContent || undefined,
      }, lease),
    )

    reportMessage.reportTaskId = task.taskId
    reportMessage.reportStatus = task.status || 'PROCESSING'
    reportMessage.timestamp = task.timestamp
    refreshMessageRender(reportMessage)

    const response = await pollReportTask(task.taskId)

    reportMessage.streaming = false
    reportMessage.reportStatus = response.status || 'SUCCESS'
    reportMessage.reportTaskId = response.taskId || task.taskId
    reportMessage.title = response.title || '结构化投资报告'
    reportMessage.suggestions = response.suggestions || []
    reportMessage.reportFileName = response.reportFileName || ''
    reportMessage.reportDownloadPath = response.reportDownloadPath || ''
    reportMessage.reportError = response.error || ''
    reportMessage.content = ''
    reportMessage.timestamp = response.timestamp
    refreshMessageRender(reportMessage)

    setRecentSessionMessageCache(chatId.value, messages.value)
    await refreshRecentSessions({ silent: true })
  } catch (error) {
    if (reportMessage) {
      reportMessage.streaming = false
      reportMessage.error = true
      reportMessage.reportStatus = 'FAILED'
      reportMessage.reportError = error.message || '请检查后端服务后重试。'
      reportMessage.title = '报告生成失败'
      reportMessage.content = error.message || '请检查后端服务后重试。'
      refreshMessageRender(reportMessage)
      errorMessage.value = reportMessage.content
    } else {
      errorMessage.value = error.message || '消息发送失败，请稍后重试。'
    }
  } finally {
    pending.value = false
    pendingStatus.value = null
    scrollToBottom()
  }
}

async function runAssistantReply(content, options = {}) {
  const modeId = options.modeId || currentMode.value
  const advisorStyle = options.advisorStyle || defaultAdvisorStyle

  const userMessage = createReactiveMessage({
    role: 'USER',
    content,
  })
  const assistantMessage = createReactiveMessage({
    role: 'ASSISTANT',
    content: '',
    trace: null,
    mode: chatModeOptions.find((item) => item.id === modeId)?.modeCode,
    advisorStyle,
    streaming: true,
  })

  messages.value.push(
    userMessage,
    assistantMessage,
  )
  stopAssistantWaitingTicker = startAssistantWaitingTicker(assistantMessage, modeId)
  scrollToBottom()

  try {
    const response = await executeWithManagedLease(
      chatId.value,
      (lease) => streamChat(
        {
          chatId: chatId.value,
          message: content,
          modelId: selectedModelId.value || undefined,
          advisorStyle,
        },
        modeId,
        {
          onChunk(chunk) {
            if (!assistantMessage.content) {
              promoteAssistantToAnswering(assistantMessage)
            }
            assistantMessage.content += chunk
            scrollToBottom()
          },
        },
        lease,
      ),
    )

    stopAssistantWaitingTickerSafely()
    assistantMessage.streaming = false
    clearAssistantWaitingState(assistantMessage)
    assistantMessage.mode = response?.mode || assistantMessage.mode
    assistantMessage.advisorStyle = response?.advisorStyle || assistantMessage.advisorStyle
    assistantMessage.trace = response?.trace || assistantMessage.trace || null
    assistantMessage.timestamp = response?.timestamp || new Date().toISOString()

    if (isFinanceOnlyRejectionMessage(assistantMessage.content)) {
      messages.value = messages.value.filter(
        (item) => item.id !== userMessage.id && item.id !== assistantMessage.id,
      )
      errorMessage.value = FINANCE_ONLY_REJECTION_MESSAGE
      return null
    }

    const synced = await syncAssistantMessageFromHistory(chatId.value, assistantMessage)
    if (!synced) {
      refreshMessageRender(assistantMessage)
    }

    if (!assistantMessage.content.trim()) {
      assistantMessage.content = '本次返回为空，请稍后重试。'
      refreshMessageRender(assistantMessage)
    }

    setRecentSessionMessageCache(chatId.value, messages.value)
    await refreshRecentSessions({ silent: true })
    return assistantMessage
  } catch (error) {
    stopAssistantWaitingTickerSafely()
    assistantMessage.streaming = false
    clearAssistantWaitingState(assistantMessage)
    assistantMessage.error = true
    assistantMessage.content = error.message || '消息发送失败，请稍后重试。'
    refreshMessageRender(assistantMessage)
    errorMessage.value = assistantMessage.content
    throw error
  } finally {
    scrollToBottom()
  }
}

watch(
  () => currentMode.value,
  (nextMode) => {
    if (nextMode !== 'tools' && currentAdvisorStyle.value !== defaultAdvisorStyle) {
      currentAdvisorStyle.value = defaultAdvisorStyle
    }
  },
)

watch(
  () => selectedModelId.value,
  (nextModelId) => {
    persistSelectedModelId(nextModelId)
  },
)

watch(
  () => errorMessage.value,
  (nextMessage) => {
    stopTransientErrorTimer()

    if (typeof window === 'undefined' || !nextMessage) {
      return
    }

    let durationMs = 0

    if (nextMessage === SESSION_OCCUPIED_MESSAGE) {
      durationMs = SESSION_OCCUPIED_MESSAGE_DURATION_MS
    } else if (nextMessage === FINANCE_ONLY_REJECTION_MESSAGE) {
      durationMs = FINANCE_ONLY_REJECTION_DURATION_MS
    }

    if (!durationMs) {
      return
    }

    transientErrorTimer = window.setTimeout(() => {
      if (errorMessage.value === nextMessage) {
        errorMessage.value = ''
      }
      transientErrorTimer = null
    }, durationMs)
  },
)

watch(
  () => route.query.chatId,
  (nextChatId) => {
    closeSessionMenu()

    if (typeof nextChatId !== 'string' || nextChatId === chatId.value) {
      return
    }

    ensureChatSession(nextChatId)
  },
)

function handleDocumentClick(event) {
  if (!(event.target instanceof Element)) {
    closeSessionMenu()
    return
  }

  if (event.target.closest('.session-context-menu')) {
    return
  }

  closeSessionMenu()
}

function handleDocumentKeydown(event) {
  if (event.key === 'Escape') {
    if (modelDialogOpen.value) {
      closeModelDialog()
      return
    }

    if (aboutDialogOpen.value) {
      closeAboutDialog()
      return
    }

    closeSessionMenu()
  }
}

function handleBeforeUnload() {
  void releaseManagedLease({
    keepalive: true,
    silent: true,
  })
}

onMounted(() => {
  ensureChatSession(typeof route.query.chatId === 'string' ? route.query.chatId : '')
  refreshRecentSessions({ silent: true })
  refreshSuggestionCards()
  loadRuntimeConfig()
  loadModels()
  focusComposer()
  document.addEventListener('click', handleDocumentClick)
  document.addEventListener('keydown', handleDocumentKeydown)
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('resize', closeSessionMenu)
})

onBeforeUnmount(() => {
  stopAssistantWaitingTickerSafely()
  stopTransientErrorTimer()
  clearHistoryRequest()
  void releaseManagedLease({ silent: true })
  document.removeEventListener('click', handleDocumentClick)
  document.removeEventListener('keydown', handleDocumentKeydown)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('resize', closeSessionMenu)
})

function wait(duration) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, duration)
  })
}
</script>

<template>
  <main class="assistant-shell">
    <aside
      class="assistant-sidebar"
      :class="{ 'assistant-sidebar--mobile-open': mobileSidebarOpen }"
    >
      <div class="sidebar-top">
        <button type="button" class="sidebar-new-chat" @click="createFreshSession">
          新建对话
        </button>

        <button type="button" class="sidebar-brand" @click="openAboutDialog">
          <div class="sidebar-brand__icon">AI</div>
          <div class="sidebar-brand__copy">
            <strong>{{ SITE_NAME }}</strong>
            <p>{{ SITE_TAGLINE }}</p>
          </div>
        </button>

        <button
          type="button"
          class="sidebar-mobile-toggle"
          :aria-expanded="mobileSidebarOpen ? 'true' : 'false'"
          @click="toggleMobileSidebar"
        >
          <span>{{ mobileSidebarOpen ? '收起面板' : '最近会话与状态' }}</span>
          <span class="sidebar-mobile-toggle__meta">
            {{ hasRecentSessions ? `${recentSessions.length} 条` : '空' }}
          </span>
        </button>
      </div>

      <div class="sidebar-body">
        <section class="sidebar-section sidebar-section--sessions">
          <div class="sidebar-section__head">
            <span>最近会话</span>
            <span>{{ recentSessions.length }}</span>
          </div>

          <div v-if="recentSessions.length" class="sidebar-session-list" @scroll="closeSessionMenu">
            <button
              v-for="session in recentSessions"
              :key="session.chatId"
              type="button"
              class="sidebar-session-item"
              :class="{ 'sidebar-session-item--active': session.chatId === chatId }"
              @contextmenu.prevent="openSessionMenu($event, session)"
              @click="openSession(session.chatId)"
            >
              <strong>{{ session.preview }}</strong>
              <span class="mono">{{ session.chatId }}</span>
            </button>
          </div>

          <p v-else class="sidebar-empty">开始一轮真实对话后，这里才会留下会话记录。</p>
        </section>

      </div>
    </aside>

    <div
      v-if="sessionMenu.open"
      ref="sessionMenuRef"
      class="session-context-menu"
      :style="{
        left: `${sessionMenu.x}px`,
        top: `${sessionMenu.y}px`,
      }"
      @click.stop
    >
      <p class="session-context-menu__title">会话操作</p>
      <p class="session-context-menu__preview">{{ sessionMenu.preview }}</p>
      <button
        type="button"
        class="session-context-menu__action session-context-menu__action--danger"
        @click="deleteSession()"
      >
        删除这个会话
      </button>
    </div>

    <div
      v-if="modelDialogOpen"
      class="model-dialog-backdrop"
      role="presentation"
      @click.self="closeModelDialog"
    >
      <section
        class="model-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="model-dialog-title"
      >
        <div class="model-dialog__hero">
          <div>
            <p class="model-dialog__eyebrow">模型管理</p>
            <h2 id="model-dialog-title">新增 OpenAI 兼容模型</h2>
          </div>
          <button type="button" class="model-dialog__close" aria-label="关闭模型弹窗" @click="closeModelDialog">
            关闭
          </button>
        </div>

        <p class="model-dialog__hint">
          新增后的 API Key 只会提交给后端保存，前端不会持久化存储。若你填写了带 <code>/v1</code> 的地址，后端会自动兼容。
        </p>

        <div class="model-dialog__form">
          <label class="model-dialog__field">
            <span>模型名称</span>
            <input v-model="modelForm.name" type="text" maxlength="80" placeholder="例如：NVIDIA Qwen 3.5 397B A17B" />
          </label>

          <label class="model-dialog__field">
            <span>Base URL</span>
            <input
              v-model="modelForm.baseUrl"
              type="url"
              maxlength="255"
              placeholder="https://integrate.api.nvidia.com/v1"
            />
          </label>

          <label class="model-dialog__field">
            <span>模型 ID</span>
            <input v-model="modelForm.modelId" type="text" maxlength="120" placeholder="qwen/qwen3.5-397b-a17b" />
          </label>

          <label class="model-dialog__field">
            <span>API Key</span>
            <input v-model="modelForm.apiKey" type="password" maxlength="512" placeholder="输入模型密钥" />
          </label>
        </div>

        <p v-if="modelErrorMessage" class="model-dialog__error">{{ modelErrorMessage }}</p>

        <div class="model-dialog__actions">
          <button type="button" class="composer-button composer-button--muted" @click="closeModelDialog">
            取消
          </button>
          <button
            type="button"
            class="composer-button composer-button--primary"
            :disabled="!canSaveModel"
            @click="submitModelForm"
          >
            {{ savingModel ? '保存中...' : '保存模型' }}
          </button>
        </div>
      </section>
    </div>

    <section class="assistant-main">
      <header class="main-header">
        <div class="main-header__title-wrap">
          <h1 class="main-header__title" :title="mainHeaderTitle">{{ mainHeaderTitle }}</h1>
        </div>

        <div class="main-header__meta">
          <span
            class="status-badge"
            :class="{ 'status-badge--pending': pending }"
            :title="statusDetailText"
          >
            {{ statusText }}
          </span>
          <label class="model-switcher" for="chat-model-select">
            <span class="model-switcher__label">模型</span>
            <select
              id="chat-model-select"
              v-model="selectedModelId"
              class="model-switcher__select"
              :disabled="loadingModels || pending || !availableModels.length"
            >
              <option v-if="!availableModels.length" value="">
                {{ loadingModels ? '加载模型中...' : '暂无可用模型' }}
              </option>
              <option v-for="model in availableModels" :key="model.id" :value="model.id">
                {{ model.name }}
              </option>
            </select>
          </label>
          <button type="button" class="header-action" @click="openModelDialog">
            添加模型
          </button>
          <span v-if="lastUpdatedAt" class="session-time">最近更新 {{ lastUpdatedAt }}</span>
          <ThemeToggle />
        </div>
      </header>

      <div v-if="activeWorkspace === 'chat'" class="thread-shell">
        <div ref="messageViewport" class="thread-stage" :class="{ 'thread-stage--empty': !hasMessages, 'thread-stage--loading': loadingHistory }">
          <template v-if="loadingHistory">
            <div class="thread-stage-loader-full">
              <p class="thread-stage-loader__label">正在加载会话</p>
              <div class="thread-stage-loader__dots">
                <span class="thread-stage-loader__dot"></span>
                <span class="thread-stage-loader__dot"></span>
                <span class="thread-stage-loader__dot"></span>
              </div>
            </div>
          </template>
          <template v-else-if="hasMessages">
            <div class="thread-list">
              <MessageBubble
                v-for="message in messages"
                :key="`${message.id}-${message.renderVersion || 0}`"
                :message="message"
                @download-report="handleReportDownload"
              />
            </div>
          </template>
          <template v-else>
            <div class="empty-state">
              <div class="empty-state__copy">
                <p class="empty-state__eyebrow">Single-Stock Financial Review</p>
                <h2>今天具体分析哪只股票？</h2>
                <p>每次只分析一个上市公司，你也可以从下面的快捷主题开始。</p>
                <div class="empty-state__actions">
                  <button type="button" class="header-action" @click="refreshSuggestionCards">
                    换一组主题
                  </button>
                </div>
              </div>
              <div class="empty-state__grid">
                <button
                  v-for="item in suggestionCards"
                  :key="item.id"
                  type="button"
                  class="empty-card"
                  @click="applyPrompt(item)"
                >
                  <strong>{{ item.title }}</strong>
                  <span>{{ item.description }}</span>
                </button>
              </div>
            </div>
          </template>
</div>
      </div>

      <div v-else class="thread-shell"></div>

      <footer v-if="activeWorkspace === 'chat'" class="composer-shell">
        <p v-if="errorMessage" class="composer-error">{{ errorMessage }}</p>

        <div class="composer-panel">
          <textarea
            id="advisor-input"
            ref="composerRef"
            v-model="draft"
            class="composer-input"
            maxlength="4000"
            rows="2"
            placeholder="请输入单只股票名称或代码，例如：分析贵州茅台近三年扣非ROE和毛利率，并按周行五步法给投资建议"
            @keydown="handleKeydown"
          ></textarea>

          <div class="composer-toolbar">
            <div class="composer-toolbar__left">
              <div
                class="composer-mode-picker"
                tabindex="-1"
                @focusout="handleModePickerFocusOut"
                @keydown.esc.stop.prevent="closeModeMenu"
              >
                <button
                  type="button"
                  class="composer-mode composer-mode--button"
                  :class="{ 'composer-mode--open': modeMenuOpen }"
                  :aria-expanded="modeMenuOpen ? 'true' : 'false'"
                  @click="toggleModeMenu"
                >
                  <span>模式 · {{ currentModeLabel }}</span>
                  <span class="composer-mode__caret">▾</span>
                </button>

                <div v-if="modeMenuOpen" class="composer-mode-menu">
                  <button
                    v-for="mode in chatModeOptions"
                    :key="mode.id"
                    type="button"
                    class="composer-mode-option"
                    :class="{ 'composer-mode-option--active': currentMode === mode.id }"
                    @click="selectMode(mode.id)"
                  >
                    <strong>{{ mode.label }}</strong>
                    <span>{{ mode.description }}</span>
                  </button>
                </div>
              </div>
              <button
                v-if="advisorStyleAvailable"
                type="button"
                class="composer-mode composer-style-toggle"
                :class="{
                  'composer-style-toggle--active': advisorStyleEnabled && advisorStyleAvailable,
                }"
                role="switch"
                :aria-checked="advisorStyleEnabled ? 'true' : 'false'"
                title="切换巴菲特视角"
                @click="toggleAdvisorStyle"
              >
                <span>巴菲特视角</span>
                <span class="composer-style-toggle__state">
                  {{ advisorStyleEnabled ? '开' : '关' }}
                </span>
              </button>
              <span class="composer-counter">{{ charCount }} / 4000</span>
            </div>

            <div class="composer-toolbar__right">
              <button
                type="button"
                class="composer-button composer-button--muted"
                :disabled="!canGenerateReport"
                @click="submitReport"
              >
                生成报告
              </button>
              <button
                type="button"
                class="composer-button composer-button--primary"
                :disabled="!canSubmit"
                @click="submitChat"
              >
                发送
              </button>
            </div>
          </div>
        </div>

        <p class="composer-note">{{ composerNote }}</p>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.assistant-shell {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  height: 100dvh;
  min-height: 100dvh;
  overflow: hidden;
  background: var(--bg-primary);
}

.assistant-sidebar {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  gap: 20px;
  padding: 20px 16px 16px;
  overflow: hidden;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--line);
}

.sidebar-top {
  display: grid;
  gap: 14px;
}

.sidebar-new-chat,
.sidebar-nav-item {
  min-height: 44px;
  border-radius: 14px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-primary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;
}

.sidebar-new-chat:hover,
.sidebar-nav-item:hover {
  background: var(--bg-surface-hover);
}

.sidebar-nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0 14px;
  text-align: left;
}

.sidebar-nav-item--active {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.sidebar-nav-item__meta {
  color: var(--text-muted);
  font-size: 0.82rem;
}

.sidebar-brand {
  display: flex;
  width: 100%;
  gap: 12px;
  align-items: flex-start;
  padding: 10px 12px;
  text-align: left;
  border-radius: 18px;
  background: transparent;
  border: 1px solid transparent;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    transform 0.2s ease;
}

.sidebar-brand:hover {
  background: var(--bg-surface);
  border-color: var(--line);
  transform: translateY(-1px);
}

.sidebar-brand:focus-visible {
  outline: none;
  background: var(--bg-surface);
  border-color: var(--accent-line);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-line) 30%, transparent);
}

.sidebar-brand__copy {
  min-width: 0;
}

.sidebar-brand__icon {
  flex: 0 0 36px;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-primary);
  font-size: 0.78rem;
  letter-spacing: 0.12em;
}

.sidebar-brand strong {
  display: block;
  font-size: 0.96rem;
  font-weight: 600;
}

.sidebar-brand p {
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 0.9rem;
  line-height: 1.5;
}

.sidebar-mobile-toggle {
  display: none;
  align-items: center;
  justify-content: space-between;
  min-height: 38px;
  padding: 0 14px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-secondary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.sidebar-mobile-toggle:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.sidebar-mobile-toggle__meta {
  color: var(--text-muted);
  font-size: 0.82rem;
}

.sidebar-section {
  display: grid;
  gap: 12px;
}

.sidebar-body {
  min-height: 0;
  display: grid;
  overflow: hidden;
}

.sidebar-section--sessions {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.sidebar-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-muted);
  font-size: 0.82rem;
  letter-spacing: 0.04em;
}

.sidebar-session-list {
  display: grid;
  gap: 8px;
  flex: 0 1 auto;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.sidebar-session-item {
  display: grid;
  gap: 4px;
  height: 72px;
  align-content: center;
  overflow: hidden;
  padding: 12px;
  text-align: left;
  border-radius: 14px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
}

.sidebar-session-item strong {
  color: var(--text-primary);
  font-size: 0.92rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-session-item span {
  color: var(--text-muted);
  font-size: 0.82rem;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-session-item:hover {
  background: var(--bg-surface);
}

.sidebar-session-item--active {
  background: var(--bg-surface);
  border-color: var(--line-strong);
}

.sidebar-empty {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.9rem;
  line-height: 1.6;
}

.sidebar-session-list::-webkit-scrollbar {
  width: 8px;
}

.sidebar-session-list::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 999px;
}

.sidebar-session-list::-webkit-scrollbar-track {
  background: transparent;
}

.session-context-menu {
  position: fixed;
  z-index: 140;
  width: 196px;
  display: grid;
  gap: 6px;
  padding: 10px;
  border-radius: 18px;
  background: var(--menu-bg);
  border: 1px solid var(--line-strong);
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(12px);
}

.session-context-menu__title {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.session-context-menu__preview {
  margin: 0 0 4px;
  color: var(--text-primary);
  font-size: 0.9rem;
  line-height: 1.45;
  word-break: break-word;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 5;
  line-clamp: 5;
}

.session-context-menu__action {
  min-height: 38px;
  padding: 0 12px;
  text-align: left;
  border-radius: 12px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.session-context-menu__action:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.session-context-menu__action--danger {
  color: var(--danger-text);
}

.session-context-menu__action--danger:hover {
  background: var(--danger-surface);
  border-color: var(--danger-line);
  color: var(--danger-text-strong);
}

.about-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 220;
  display: grid;
  place-items: center;
  padding: 24px;
  background: color-mix(in srgb, var(--bg-primary) 58%, transparent);
  backdrop-filter: blur(14px);
}

.about-dialog {
  width: min(100%, 560px);
  display: grid;
  gap: 20px;
  padding: 22px;
  border-radius: 28px;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent-soft) 64%, transparent), transparent 28%),
    var(--bg-surface);
  border: 1px solid var(--line-strong);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.24);
}

.about-dialog__hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.about-dialog__badge,
.about-dialog__close {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--bg-primary);
  color: var(--text-secondary);
  font-size: 0.84rem;
}

.about-dialog__close {
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.about-dialog__close:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.about-dialog__body {
  display: grid;
  gap: 18px;
}

.about-dialog__lead {
  display: grid;
  gap: 10px;
}

.about-dialog__lead h2 {
  margin: 0;
  font-size: clamp(1.6rem, 3vw, 2.2rem);
  line-height: 1.05;
  letter-spacing: -0.04em;
}

.about-dialog__lead p,
.about-dialog__section p {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
}

.about-dialog__section {
  display: grid;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}

.about-dialog__label {
  color: var(--text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.about-dialog__ideas {
  margin: 0;
  padding-left: 1.1rem;
  color: var(--text-secondary);
  line-height: 1.7;
}

.model-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 230;
  display: grid;
  place-items: center;
  padding: 24px;
  background: color-mix(in srgb, var(--bg-primary) 56%, transparent);
  backdrop-filter: blur(14px);
}

.model-dialog {
  width: min(100%, 560px);
  display: grid;
  gap: 18px;
  padding: 22px;
  border-radius: 28px;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent-soft) 58%, transparent), transparent 30%),
    var(--bg-surface);
  border: 1px solid var(--line-strong);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.24);
}

.model-dialog__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.model-dialog__eyebrow {
  margin: 0 0 6px;
  color: var(--text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.model-dialog h2 {
  margin: 0;
  font-size: clamp(1.4rem, 3vw, 1.9rem);
  line-height: 1.1;
}

.model-dialog__close {
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--bg-primary);
  color: var(--text-secondary);
}

.model-dialog__hint {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
}

.model-dialog__form {
  display: grid;
  gap: 12px;
}

.model-dialog__field {
  display: grid;
  gap: 8px;
}

.model-dialog__field span {
  color: var(--text-secondary);
  font-size: 0.88rem;
}

.model-dialog__field input {
  width: 100%;
  min-height: 44px;
  padding: 0 14px;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: var(--bg-primary);
  color: var(--text-primary);
}

.model-dialog__field input:focus-visible {
  outline: none;
  border-color: var(--accent-line);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-line) 26%, transparent);
}

.model-dialog__error {
  margin: 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--danger-surface);
  border: 1px solid var(--danger-line);
  color: var(--danger-text-strong);
  font-size: 0.9rem;
}

.model-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.assistant-main {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
  height: 100dvh;
  overflow: hidden;
}

.main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 28px 10px;
}

.main-header__title-wrap {
  min-width: 0;
  flex: 1 1 auto;
}

.main-header__title {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1rem;
  font-weight: 600;
}

.main-header__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.model-switcher {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 10px 0 12px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
}

.model-switcher__label {
  color: var(--text-muted);
  font-size: 0.82rem;
  white-space: nowrap;
}

.model-switcher__select {
  min-width: 132px;
  max-width: 200px;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text-primary);
  font-size: 0.84rem;
  appearance: none;
  cursor: pointer;
}

.model-switcher__select:disabled {
  cursor: not-allowed;
  color: var(--text-muted);
}

.header-action {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  color: var(--text-secondary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.header-action:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.status-badge,
.advisor-style-badge,
.session-badge,
.session-time,
.composer-mode,
.composer-counter {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.status-badge--pending {
  background: var(--accent-soft);
  border-color: var(--accent-line);
  color: var(--accent-strong);
}

.advisor-style-badge--active {
  background: var(--accent-soft);
  border-color: var(--accent-line);
  color: var(--accent-strong);
}

.thread-shell {
  min-height: 0;
  overflow: hidden;
}

.thread-stage {
  height: 100%;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 0 28px 8px;
  transition: opacity 0.25s ease;
}

.thread-stage--loading {
  opacity: 1;
  pointer-events: none;
}

.thread-stage-loader-full {
  display: none;
}

.thread-stage--loading .thread-stage-loader-full {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  min-height: 60dvh;
  padding: 80px 20px;
}

.thread-stage-loader__label {
  color: var(--text-muted, #888);
  font-size: 0.85rem;
  letter-spacing: 0.06em;
}

.thread-stage-loader__dots {
  display: flex;
  align-items: center;
  gap: 8px;
}

.thread-stage-loader__dot {
  display: block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent, #2563eb);
  animation: dot-bounce 1.2s ease-in-out infinite;
}

.thread-stage-loader__dot:nth-child(1) {
  animation-delay: 0s;
}

.thread-stage-loader__dot:nth-child(2) {
  animation-delay: 0.2s;
}

.thread-stage-loader__dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dot-bounce {
  0%, 80%, 100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  40% {
    transform: translateY(-12px);
    opacity: 1;
  }
}

.thread-stage--empty {
  display: grid;
  place-items: center;
}

.thread-list {
  width: min(100%, 820px);
  margin: 0 auto;
  display: grid;
  gap: 28px;
  padding: 12px 0 32px;
}

.empty-state {
  width: min(100%, 920px);
  margin: auto;
  display: grid;
  gap: 28px;
  align-content: center;
  padding: 48px 0 96px;
}

.empty-state__copy {
  text-align: center;
}

.empty-state__eyebrow {
  margin: 0 0 12px;
  color: var(--text-muted);
  font-size: 0.82rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.empty-state__copy h2 {
  margin: 0;
  font-size: clamp(2.2rem, 4vw, 3.6rem);
  line-height: 1.05;
  letter-spacing: -0.04em;
  font-weight: 600;
}

.empty-state__copy p:last-child {
  max-width: 36rem;
  margin: 14px auto 0;
  color: var(--text-muted);
  font-size: 1rem;
  line-height: 1.65;
}

.empty-state__actions {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}

.empty-state__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.empty-card {
  display: grid;
  gap: 8px;
  min-height: 132px;
  padding: 18px;
  text-align: left;
  border-radius: 18px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-secondary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    transform 0.2s ease;
}

.empty-card strong {
  color: var(--text-primary);
  font-size: 1rem;
  font-weight: 600;
}

.empty-card span {
  line-height: 1.55;
}

.empty-card:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  transform: translateY(-1px);
}

.composer-shell {
  padding: 10px 28px 12px;
}

.composer-error {
  width: min(100%, 820px);
  margin: 0 auto 10px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--danger-surface);
  border: 1px solid var(--danger-line);
  color: var(--danger-text-strong);
  font-size: 0.92rem;
}

.composer-panel {
  width: min(100%, 820px);
  margin: 0 auto;
  padding: 10px 14px 8px;
  border-radius: 28px;
  background: var(--bg-composer);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-soft);
}

.composer-input {
  width: 100%;
  min-height: 52px;
  resize: none;
  border: 0;
  outline: none;
  padding: 0;
  color: var(--text-primary);
  background: transparent;
  line-height: 1.5;
}

.composer-input::placeholder {
  color: var(--text-muted);
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 8px;
}

.composer-toolbar__left,
.composer-toolbar__right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.composer-mode-picker {
  position: relative;
}

.composer-mode--button {
  gap: 8px;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.composer-mode--button:hover,
.composer-mode--open {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.composer-style-toggle {
  gap: 8px;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.composer-style-toggle:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
}

.composer-style-toggle--disabled,
.composer-style-toggle:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.composer-style-toggle--disabled:hover,
.composer-style-toggle:disabled:hover {
  background: var(--bg-surface);
  border-color: var(--line);
  color: var(--text-secondary);
}

.composer-style-toggle--active {
  background: var(--accent-soft);
  border-color: var(--accent-line);
  color: var(--accent-strong);
}

.composer-style-toggle__state {
  color: inherit;
  font-size: 0.8rem;
}

.composer-mode__caret {
  font-size: 0.72rem;
  transition: transform 0.2s ease;
}

.composer-mode--open .composer-mode__caret {
  transform: rotate(180deg);
}

.composer-mode-menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 10px);
  z-index: 12;
  width: min(320px, calc(100vw - 64px));
  display: grid;
  gap: 6px;
  padding: 8px;
  border-radius: 18px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-soft);
}

.composer-mode-option {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 12px 14px;
  text-align: left;
  border-radius: 14px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;
}

.composer-mode-option strong {
  color: var(--text-primary);
  font-size: 0.92rem;
  font-weight: 500;
}

.composer-mode-option span {
  color: var(--text-muted);
  font-size: 0.82rem;
  line-height: 1.45;
}

.composer-mode-option:hover,
.composer-mode-option--active {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
}

.composer-button {
  min-height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--line);
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    opacity 0.2s ease;
}

.composer-button--muted {
  background: var(--bg-surface);
  color: var(--text-secondary);
}

.composer-button--primary {
  background: var(--text-primary);
  color: var(--bg-primary);
  border-color: transparent;
}

.composer-button:disabled {
  opacity: 0.5;
}

.composer-note {
  width: min(100%, 820px);
  margin: 6px auto 0;
  color: var(--text-muted);
  font-size: 0.82rem;
  text-align: center;
}

@media (max-width: 980px) {
  .assistant-shell {
    grid-template-columns: 1fr;
    height: auto;
    overflow: visible;
  }

  .assistant-sidebar {
    grid-template-rows: auto;
    gap: 14px;
    padding: 14px 16px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
    overflow: visible;
  }

  .sidebar-top {
    gap: 12px;
  }

  .sidebar-new-chat {
    min-height: 40px;
  }

  .sidebar-brand {
    align-items: center;
  }

  .sidebar-brand p {
    margin-top: 2px;
    font-size: 0.84rem;
  }

  .sidebar-mobile-toggle {
    display: inline-flex;
  }

  .sidebar-body {
    overflow: visible;
  }

  .assistant-sidebar:not(.assistant-sidebar--mobile-open) .sidebar-body {
    display: none;
  }

  .sidebar-section--sessions {
    max-height: min(46dvh, 320px);
  }

  .assistant-main {
    height: auto;
    min-height: 100dvh;
  }

  .main-header {
    padding: 12px 20px 10px;
  }

  .thread-stage {
    padding-left: 20px;
    padding-right: 20px;
  }

  .composer-shell {
    padding-left: 20px;
    padding-right: 20px;
    padding-bottom: 12px;
  }

  .empty-state__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .main-header,
  .thread-stage,
  .composer-shell {
    padding-left: 16px;
    padding-right: 16px;
  }

  .main-header {
    display: grid;
    gap: 10px;
  }

  .main-header__meta {
    justify-content: flex-start;
  }

  .model-switcher {
    width: 100%;
    justify-content: space-between;
  }

  .model-switcher__select {
    min-width: 0;
    width: 100%;
  }

  .session-badge {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .empty-state {
    padding-top: 20px;
  }

  .empty-state__grid {
    grid-template-columns: 1fr;
  }

  .composer-toolbar {
    align-items: flex-start;
  }

  .composer-toolbar__left,
  .composer-toolbar__right {
    width: 100%;
    justify-content: space-between;
  }

  .composer-toolbar__right {
    gap: 8px;
  }

  .composer-button {
    flex: 1 1 0;
    justify-content: center;
  }

  .composer-mode-menu {
    width: min(100vw - 32px, 320px);
  }

  .session-context-menu {
    width: min(196px, calc(100vw - 24px));
  }

  .about-dialog-backdrop {
    padding: 16px;
  }

  .about-dialog {
    padding: 18px;
    border-radius: 24px;
  }

  .model-dialog-backdrop {
    padding: 16px;
  }

  .model-dialog {
    padding: 18px;
    border-radius: 24px;
  }

  .model-dialog__actions {
    flex-direction: column-reverse;
  }

  .model-dialog__actions .composer-button {
    width: 100%;
    justify-content: center;
  }

  .about-dialog__hero {
    align-items: flex-start;
  }
}
</style>
