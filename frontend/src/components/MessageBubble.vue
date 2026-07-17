<script setup>
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

import { SITE_NAME } from '@/constants/brand'
import { advisorStyleLabelMap, formatClockTime, modeLabelMap } from '@/utils/chat'

marked.setOptions({
  async: false,
  breaks: true,
  gfm: true,
  mangle: false,
})

const MARKDOWN_SANITIZE_OPTIONS = {
  USE_PROFILES: {
    html: true,
  },
  ADD_TAGS: ['input'],
  ADD_ATTR: ['class', 'target', 'rel', 'type', 'checked', 'disabled', 'start', 'align'],
}

const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
})
const emit = defineEmits(['download-report'])

const isUser = computed(() => props.message.role === 'USER')
const isSystem = computed(() => props.message.role === 'SYSTEM')
const isReport = computed(() => props.message.kind === 'report')
const isStreamingAssistant = computed(
  () => !isUser.value && !isSystem.value && !isReport.value && Boolean(props.message.streaming),
)
const hasMessageContent = computed(() => Boolean(String(props.message.content || '').trim()))

const heading = computed(() => {
  if (isUser.value) {
    return '你'
  }

  if (isReport.value) {
    return '报告引擎'
  }

  if (isSystem.value) {
    return '系统消息'
  }

  return SITE_NAME
})

const messageMode = computed(() => {
  if (!props.message.mode) {
    return ''
  }

  return Object.prototype.hasOwnProperty.call(modeLabelMap, props.message.mode)
    ? modeLabelMap[props.message.mode]
    : props.message.mode
})

const messageAdvisorStyle = computed(() => {
  const advisorStyle = props.message.advisorStyle
  if (!advisorStyle || advisorStyle === 'default') {
    return ''
  }

  return advisorStyleLabelMap[advisorStyle] || advisorStyle
})

const traceToolCalls = computed(() => normalizeTraceToolCalls(props.message.trace?.toolCalls))
const traceRagReferences = computed(() => normalizeTraceReferences(props.message.trace?.ragReferences))
const hasTrace = computed(
  () =>
    !isUser.value &&
    !isSystem.value &&
    !isReport.value &&
    (traceToolCalls.value.length > 0 || traceRagReferences.value.length > 0),
)
const traceSummary = computed(() => {
  const summaryParts = []

  if (traceToolCalls.value.length) {
    summaryParts.push(`${traceToolCalls.value.length} 次工具调用`)
  }

  if (traceRagReferences.value.length) {
    summaryParts.push(`${traceRagReferences.value.length} 条知识库引用`)
  }

  return summaryParts.join(' · ')
})
const waitingTitle = computed(() => normalizeTraceText(props.message.waitingTitle))
const waitingDetail = computed(() => normalizeTraceText(props.message.waitingDetail))
const waitingNote = computed(() => normalizeTraceText(props.message.waitingNote))
const waitingSteps = computed(() => normalizeWaitingSteps(props.message.waitingSteps))
const showWaitingPanel = computed(
  () =>
    isStreamingAssistant.value &&
    !hasMessageContent.value &&
    (waitingTitle.value || waitingDetail.value || waitingSteps.value.length),
)
const reportDisplayName = computed(
  () => normalizeTraceText(props.message.reportFileName) || normalizeTraceText(props.message.title) || '结构化投资报告.pdf',
)
const reportErrorText = computed(() => {
  if (!isReport.value) {
    return ''
  }

  const explicitError = normalizeTraceText(props.message.reportError)
  if (explicitError) {
    return explicitError
  }

  return props.message.error ? normalizeTraceText(props.message.content) : ''
})

const reportStatusText = computed(() => {
  if (!isReport.value || props.message.error) {
    return ''
  }

  if (props.message.reportStatus === 'PROCESSING') {
    return '报告生成中，请稍候…'
  }

  if (props.message.reportStatus === 'SUCCESS') {
    return '报告已生成，可直接下载。'
  }

  return ''
})

function renderMarkdown(content) {
  const normalized = String(content || '').replace(/\r\n?/g, '\n')

  if (!normalized.trim()) {
    return ''
  }

  try {
    const parsed = marked.parse(normalized)
    const enhanced = String(parsed)
      .replace(/<pre>/g, '<div class="message__code-wrapper"><pre>')
      .replace(/<\/pre>/g, '</pre></div>')
      .replace(/<table>/g, '<div class="message__table-wrapper"><table>')
      .replace(/<\/table>/g, '</table></div>')
      .replace(/<a /g, '<a target="_blank" rel="noreferrer noopener" ')

    return DOMPurify.sanitize(enhanced, MARKDOWN_SANITIZE_OPTIONS)
  } catch {
    return DOMPurify.sanitize(normalized, MARKDOWN_SANITIZE_OPTIONS).replace(/\n/g, '<br>')
  }
}

function normalizeTraceToolCalls(items = []) {
  if (!Array.isArray(items)) {
    return []
  }

  return items
    .map((item, index) => {
      if (!item || typeof item !== 'object') {
        return null
      }

      const title = normalizeTraceText(item.title)
      const toolName = normalizeTraceText(item.toolName)
      const argumentsText = normalizeTraceText(item.arguments)
      const outputPreview = normalizeTraceText(item.outputPreview)

      if (!title && !toolName && !argumentsText && !outputPreview) {
        return null
      }

      return {
        id: `${toolName || title || 'tool'}-${index}`,
        title,
        toolName,
        arguments: argumentsText,
        outputPreview,
      }
    })
    .filter(Boolean)
}

function normalizeTraceReferences(items = []) {
  if (!Array.isArray(items)) {
    return []
  }

  return items
    .map((item, index) => {
      if (!item || typeof item !== 'object') {
        return null
      }

      const source = normalizeTraceText(item.source)
      const snippet = normalizeTraceText(item.snippet)

      if (!source && !snippet) {
        return null
      }

      return {
        id: `${source || 'reference'}-${index}`,
        source,
        snippet,
      }
    })
    .filter(Boolean)
}

function normalizeTraceText(value) {
  return String(value || '').replace(/\r\n?/g, '\n').trim()
}

function normalizeWaitingSteps(items = []) {
  if (!Array.isArray(items)) {
    return []
  }

  return items
    .map((item, index) => {
      if (!item || typeof item !== 'object') {
        return null
      }

      const label = normalizeTraceText(item.label)
      const state = ['pending', 'active', 'done'].includes(item.state) ? item.state : 'pending'

      if (!label) {
        return null
      }

      return {
        id: item.id || `waiting-step-${index}`,
        label,
        state,
      }
    })
    .filter(Boolean)
}

const userLines = computed(() => (props.message.content || '').split('\n'))
const assistantPlainLines = computed(() => (props.message.content || '').split('\n'))
const renderedMarkdown = computed(() =>
  isUser.value || isSystem.value ? '' : renderMarkdown(props.message.content || ''),
)
const timeLabel = computed(() => formatClockTime(props.message.timestamp))
</script>

<template>
  <article
    class="message"
    :class="{
      'message--user': isUser,
      'message--system': isSystem,
      'message--report': isReport,
      'message--error': message.error,
    }"
  >
    <template v-if="isSystem">
      <div class="message__system-note">
        <span>{{ props.message.content }}</span>
      </div>
    </template>

    <template v-else>
      <div v-if="!isUser" class="message__avatar">
        {{ isReport ? 'RP' : 'AI' }}
      </div>

      <div class="message__stack">
        <div class="message__meta" :class="{ 'message__meta--user': isUser }">
          <span>{{ heading }}</span>
          <time v-if="timeLabel">{{ timeLabel }}</time>
          <span v-if="messageMode" class="message__mode">{{ messageMode }}</span>
          <span v-if="messageAdvisorStyle" class="message__style">{{ messageAdvisorStyle }}</span>
        </div>

        <details v-if="hasTrace" class="message__trace">
          <summary class="message__trace-summary">
            <span class="message__trace-title">本次回答参考</span>
            <span v-if="traceSummary" class="message__trace-meta">{{ traceSummary }}</span>
          </summary>

          <div class="message__trace-body">
            <section v-if="traceToolCalls.length" class="message__trace-section">
              <p class="message__trace-label">工具调用</p>
              <div class="message__trace-list">
                <article
                  v-for="item in traceToolCalls"
                  :key="item.id"
                  class="message__trace-card"
                >
                  <div class="message__trace-card-head">
                    <strong>{{ item.title || item.toolName || '工具调用' }}</strong>
                    <span v-if="item.toolName">{{ item.toolName }}</span>
                  </div>
                  <pre v-if="item.arguments" class="message__trace-pre">{{ item.arguments }}</pre>
                  <pre
                    v-if="item.outputPreview"
                    class="message__trace-pre message__trace-pre--result"
                  >{{ item.outputPreview }}</pre>
                </article>
              </div>
            </section>

            <section v-if="traceRagReferences.length" class="message__trace-section">
              <p class="message__trace-label">RAG 知识库引用</p>
              <div class="message__trace-list">
                <article
                  v-for="item in traceRagReferences"
                  :key="item.id"
                  class="message__trace-card"
                >
                  <div class="message__trace-card-head">
                    <strong>{{ item.source || '本地知识库' }}</strong>
                    <span>本地资料</span>
                  </div>
                  <p v-if="item.snippet" class="message__trace-copy">{{ item.snippet }}</p>
                </article>
              </div>
            </section>
          </div>
        </details>

        <div
          class="message__bubble"
          :class="{
            'message__bubble--user': isUser,
            'message__bubble--assistant': !isUser && !isReport,
            'message__bubble--report': isReport,
          }"
        >
          <template v-if="isReport">
            <p class="message__report-label">报告文件</p>
            <h3>{{ reportDisplayName }}</h3>
            <p v-if="reportErrorText" class="message__report-status message__report-status--error">
              {{ reportErrorText }}
            </p>
            <p v-else-if="reportStatusText" class="message__report-status">
              {{ reportStatusText }}
            </p>
            <div v-if="message.streaming || message.reportStatus === 'PROCESSING'" class="typing message__report-typing" aria-label="报告生成中">
              <span></span>
              <span></span>
              <span></span>
            </div>
            <div v-if="message.reportDownloadPath" class="message__report-actions">
              <button
                type="button"
                class="message__report-download"
                @click.stop="
                  emit('download-report', {
                    downloadPath: message.reportDownloadPath,
                    fileName: message.reportFileName,
                    title: message.title,
                  })
                "
              >
                下载 PDF
              </button>
              <span v-if="message.reportFileName" class="message__report-file">
                {{ message.reportFileName }}
              </span>
            </div>
          </template>

          <template v-else-if="isUser">
            <p v-for="(line, index) in userLines" :key="`${message.id}-${index}`">
              {{ line || ' ' }}
            </p>
          </template>

          <template v-else>
            <div v-if="showWaitingPanel" class="message__waiting">
              <div class="message__waiting-head">
                <strong>{{ waitingTitle || '正在处理你的问题' }}</strong>
                <span v-if="messageMode">{{ messageMode }}</span>
              </div>

              <p v-if="waitingDetail" class="message__waiting-copy">
                {{ waitingDetail }}
              </p>

              <div v-if="waitingSteps.length" class="message__waiting-steps">
                <div
                  v-for="item in waitingSteps"
                  :key="item.id"
                  class="message__waiting-step"
                  :class="{
                    'message__waiting-step--active': item.state === 'active',
                    'message__waiting-step--done': item.state === 'done',
                  }"
                >
                  <span class="message__waiting-step-dot"></span>
                  <span>{{ item.label }}</span>
                </div>
              </div>

              <p v-if="waitingNote" class="message__waiting-note">
                {{ waitingNote }}
              </p>

              <div class="typing message__waiting-typing" aria-label="AI 正在处理中">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
            <template v-else-if="isStreamingAssistant && message.content">
              <p
                v-for="(line, index) in assistantPlainLines"
                :key="`${message.id}-stream-${index}`"
                class="message__stream-line"
              >
                {{ line || ' ' }}
              </p>
            </template>
            <div
              v-else-if="message.content"
              class="message__markdown"
              v-html="renderedMarkdown"
            ></div>
            <div v-if="message.streaming && !message.content && !showWaitingPanel" class="typing">
              <span></span>
              <span></span>
              <span></span>
            </div>
            <span v-if="message.streaming && message.content" class="message__caret"></span>
          </template>
        </div>
      </div>
    </template>
  </article>
</template>

<style scoped>
.message {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  width: 100%;
}

.message--user {
  justify-content: flex-end;
}

.message__system-note {
  margin: 0 auto;
  padding: 10px 14px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-muted);
  font-size: 0.86rem;
}

.message__avatar {
  flex: 0 0 34px;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-primary);
  font-size: 0.7rem;
  letter-spacing: 0.08em;
}

.message__stack {
  width: min(100%, 720px);
  display: grid;
  gap: 8px;
}

.message__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--text-muted);
  font-size: 0.84rem;
}

.message__meta--user {
  justify-content: flex-end;
}

.message__mode {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
}

.message__style {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-line);
  color: var(--accent-strong);
}

.message__bubble {
  color: var(--text-primary);
}

.message__bubble--assistant {
  padding: 0;
}

.message__waiting {
  display: grid;
  gap: 12px;
  padding: 16px 18px;
  border-radius: 20px;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent-soft) 52%, transparent), transparent 36%),
    var(--bg-surface);
  border: 1px solid var(--line);
}

.message__waiting-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.message__waiting-head strong {
  color: var(--text-primary);
  font-size: 0.98rem;
  font-weight: 600;
}

.message__waiting-head span {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--bg-primary);
  border: 1px solid var(--line);
  color: var(--text-muted);
  font-size: 0.76rem;
}

.message__waiting-copy,
.message__waiting-note {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
}

.message__waiting-steps {
  display: grid;
  gap: 8px;
}

.message__waiting-step {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--bg-primary);
  border: 1px solid var(--line);
  color: var(--text-muted);
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease,
    color 0.2s ease;
}

.message__waiting-step--active {
  border-color: var(--accent-line);
  background: color-mix(in srgb, var(--accent-soft) 54%, var(--bg-primary));
  color: var(--text-primary);
}

.message__waiting-step--done {
  color: var(--text-secondary);
}

.message__waiting-step-dot {
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.5;
}

.message__waiting-step--active .message__waiting-step-dot {
  opacity: 1;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent-line) 18%, transparent);
}

.message__waiting-step--done .message__waiting-step-dot {
  opacity: 0.75;
}

.message__waiting-typing {
  margin-top: 2px;
}

.message__trace {
  overflow: hidden;
  border-radius: 18px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
}

.message__trace-summary {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding: 0 16px 0 38px;
  list-style: none;
  color: var(--text-secondary);
  cursor: pointer;
}

.message__trace-summary::-webkit-details-marker {
  display: none;
}

.message__trace-summary::before {
  content: '▸';
  position: absolute;
  left: 16px;
  top: 50%;
  color: var(--text-muted);
  transform: translateY(-50%);
  transition: transform 0.2s ease;
}

.message__trace[open] .message__trace-summary::before {
  transform: translateY(-50%) rotate(90deg);
}

.message__trace-title {
  color: var(--text-primary);
  font-size: 0.92rem;
  font-weight: 600;
}

.message__trace-meta {
  color: var(--text-muted);
  font-size: 0.8rem;
}

.message__trace-body {
  display: grid;
  gap: 14px;
  padding: 0 14px 14px;
  border-top: 1px solid var(--line);
}

.message__trace-section {
  display: grid;
  gap: 10px;
}

.message__trace-label {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.message__trace-list {
  display: grid;
  gap: 10px;
}

.message__trace-card {
  display: grid;
  gap: 8px;
  padding: 12px;
  border-radius: 14px;
  background: var(--bg-primary);
  border: 1px solid var(--line);
}

.message__trace-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.message__trace-card-head strong {
  color: var(--text-primary);
  font-size: 0.9rem;
  font-weight: 600;
}

.message__trace-card-head span {
  color: var(--text-muted);
  font-size: 0.78rem;
}

.message__trace-pre,
.message__trace-copy {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.84rem;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.message__trace-pre {
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--code-surface);
  border: 1px solid var(--line);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.message__trace-pre--result {
  background: var(--table-surface);
}

.message__bubble--user {
  max-width: min(100%, 560px);
  margin-left: auto;
  padding: 14px 18px;
  border-radius: 22px;
  background: var(--bg-user);
  border: 1px solid var(--line);
}

.message__bubble--report {
  max-width: min(100%, 680px);
  padding: 18px 20px;
  border-radius: 20px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
}

.message--error .message__bubble--assistant,
.message--error .message__bubble--report,
.message--error .message__bubble--user {
  border-color: rgba(255, 122, 122, 0.24);
}

.message__report-label {
  color: var(--text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.message__bubble h3 {
  margin: 8px 0 10px;
  font-size: 1.08rem;
  font-weight: 600;
}

.message__report-status {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
}

.message__report-status--error {
  color: var(--danger-text-strong);
}

.message__report-typing {
  margin-top: 2px;
}

.message__bubble--user p,
.message__bubble--user li {
  margin: 0;
  word-break: break-word;
  line-height: 1.78;
}

.message__bubble--user p {
  white-space: pre-wrap;
}

.message__bubble--user p + p {
  margin-top: 0.82em;
}

.message__report-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 16px;
}

.message__report-download {
  min-height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  background: var(--bg-surface);
  color: var(--text-primary);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.message__report-download:hover {
  background: var(--bg-surface-hover);
  border-color: var(--accent-line);
  color: var(--accent-strong);
}

.message__report-file {
  color: var(--text-muted);
  font-size: 0.82rem;
  line-height: 1.5;
  word-break: break-word;
}

.message__stream-line {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.78;
}

.message__stream-line + .message__stream-line {
  margin-top: 0.82em;
}

.message__markdown {
  display: grid;
  gap: 14px;
  color: var(--text-primary);
  font-size: 0.97rem;
  line-height: 1.78;
}

.message__markdown :deep(*) {
  word-break: break-word;
}

.message__markdown :deep(h1),
.message__markdown :deep(h2),
.message__markdown :deep(h3),
.message__markdown :deep(h4),
.message__markdown :deep(h5),
.message__markdown :deep(h6) {
  margin: 0;
  color: var(--text-primary);
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.message__markdown :deep(h1) {
  font-size: 1.5rem;
}

.message__markdown :deep(h2) {
  font-size: 1.28rem;
}

.message__markdown :deep(h3) {
  font-size: 1.12rem;
}

.message__markdown :deep(p),
.message__markdown :deep(ul),
.message__markdown :deep(ol),
.message__markdown :deep(pre),
.message__markdown :deep(blockquote),
.message__markdown :deep(figure),
.message__markdown :deep(table),
.message__markdown :deep(hr) {
  margin: 0;
}

.message__markdown :deep(strong) {
  color: var(--text-primary);
  font-weight: 700;
}

.message__markdown :deep(em) {
  font-style: italic;
}

.message__markdown :deep(del) {
  text-decoration: line-through;
  text-decoration-color: var(--text-muted);
}

.message__markdown :deep(p),
.message__markdown :deep(li) {
  line-height: 1.78;
}

.message__markdown :deep(ul),
.message__markdown :deep(ol) {
  padding-left: 1.6rem;
}

.message__markdown :deep(ul) {
  list-style: disc;
}

.message__markdown :deep(ol) {
  list-style: none;
  padding-left: 0;
  counter-reset: markdown-ordered-list;
}

.message__markdown :deep(ul > li) {
  display: list-item;
}

.message__markdown :deep(ul > li::marker) {
  color: var(--text-muted);
}

.message__markdown :deep(ol > li) {
  position: relative;
  display: block;
  padding-left: 2rem;
  counter-increment: markdown-ordered-list;
}

.message__markdown :deep(ol > li::before) {
  content: counter(markdown-ordered-list) '.';
  position: absolute;
  left: 0;
  top: 0;
  width: 1.5rem;
  color: var(--text-muted);
  text-align: right;
  font-variant-numeric: lining-nums tabular-nums;
}

.message__markdown :deep(li + li) {
  margin-top: 0.5rem;
}

.message__markdown :deep(li > ul),
.message__markdown :deep(li > ol) {
  margin-top: 0.5rem;
}

.message__markdown :deep(li > input[type='checkbox']) {
  margin-right: 0.55rem;
  transform: translateY(1px);
  accent-color: var(--text-primary);
  pointer-events: none;
}

.message__markdown :deep(blockquote) {
  padding-left: 14px;
  border-left: 3px solid var(--line-strong);
  color: var(--text-secondary);
  background: var(--blockquote-bg);
}

.message__markdown :deep(a) {
  color: var(--text-primary);
  text-decoration: underline;
  text-decoration-color: var(--link-underline);
  text-underline-offset: 0.18em;
}

.message__markdown :deep(a:hover) {
  text-decoration-color: var(--link-underline-hover);
}

.message__markdown :deep(img) {
  display: block;
  max-width: min(100%, 560px);
  border-radius: 16px;
  border: 1px solid var(--line);
}

.message__markdown :deep(hr) {
  height: 1px;
  border: 0;
  background: var(--line);
}

.message__markdown :deep(.message__code-wrapper) {
  overflow: auto;
  border-radius: 16px;
  background: var(--code-surface);
  border: 1px solid var(--line);
}

.message__markdown :deep(pre) {
  overflow: auto;
  padding: 14px 16px;
  background: transparent;
}

.message__markdown :deep(code) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 0.9em;
}

.message__markdown :deep(:not(pre) > code) {
  padding: 0.2em 0.42em;
  border-radius: 8px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
}

.message__markdown :deep(pre > code) {
  display: block;
  white-space: pre-wrap;
  word-break: normal;
}

.message__markdown :deep(.message__table-wrapper) {
  overflow-x: auto;
  border-radius: 16px;
  border: 1px solid var(--line);
}

.message__markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  border-spacing: 0;
  background: var(--table-surface);
}

.message__markdown :deep(th),
.message__markdown :deep(td) {
  padding: 10px 12px;
  border: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}

.message__markdown :deep(th) {
  color: var(--text-primary);
  background: var(--table-head-surface);
}

.typing {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 20px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--text-muted);
  opacity: 0.55;
  animation: pulse 1.1s ease-in-out infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.15s;
}

.typing span:nth-child(3) {
  animation-delay: 0.3s;
}

.message__caret {
  display: inline-block;
  width: 7px;
  height: 1rem;
  margin-left: 4px;
  vertical-align: middle;
  background: var(--text-secondary);
  border-radius: 999px;
  animation: blink 0.9s steps(1) infinite;
}

@keyframes pulse {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }
  50% {
    transform: translateY(-2px);
    opacity: 1;
  }
}

@keyframes blink {
  0%,
  49% {
    opacity: 1;
  }
  50%,
  100% {
    opacity: 0;
  }
}

@media (max-width: 720px) {
  .message__avatar {
    display: none;
  }

  .message__bubble--user {
    max-width: min(100%, 100%);
  }
}
</style>
