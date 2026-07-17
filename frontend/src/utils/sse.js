export async function streamSseJson({
  baseUrl,
  path,
  payload,
  apiKey,
  headers,
  onChunk,
  signal,
}) {
  const response = await fetch(joinUrl(baseUrl, path), {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      ...(apiKey ? { 'X-API-KEY': apiKey } : {}),
      ...(headers || {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  if (!response.ok) {
    throw await buildFetchError(response)
  }

  if (!response.body) {
    throw new Error('后端未返回可读取的数据流。')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()

    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split(/\r?\n\r?\n/)
    buffer = chunks.pop() || ''

    for (const chunk of chunks) {
      const event = parseSseBlock(chunk)

      if (!event) {
        continue
      }

      if (event.type === 'error') {
        throw buildEventError(event.payload)
      }

      if (event.type === 'connected' || event.type === 'ping') {
        continue
      }

      if (event.done) {
        return
      }

      if (event.text) {
        onChunk?.(event.text)
      }
    }
  }

  buffer += decoder.decode()

  if (!buffer.trim()) {
    return
  }

  const trailingEvent = parseSseBlock(buffer)
  if (!trailingEvent) {
    return
  }

  if (trailingEvent.type === 'error') {
    throw buildEventError(trailingEvent.payload)
  }

  if (trailingEvent.text) {
    onChunk?.(trailingEvent.text)
  }
}

export async function streamText(
  content,
  {
    onChunk,
    signal,
    minSize = 2,
    maxSize = 12,
    delay = 18,
  } = {},
) {
  if (!content) {
    return
  }

  let offset = 0

  while (offset < content.length) {
    if (signal?.aborted) {
      throw new DOMException('请求已取消', 'AbortError')
    }

    const step = Math.min(
      content.length - offset,
      Math.max(minSize, Math.floor(Math.random() * (maxSize - minSize + 1)) + minSize),
    )
    const chunk = content.slice(offset, offset + step)
    offset += step

    onChunk?.(chunk)
    await wait(delay)
  }
}

function parseSseBlock(block) {
  const lines = block.split(/\r?\n/)
  const dataLines = []
  let eventType = 'message'

  for (const line of lines) {
    if (!line || line.startsWith(':')) {
      continue
    }

    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
      continue
    }

    if (line.startsWith('data:')) {
      const value = line.slice(5)
      dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
    }
  }

  if (!dataLines.length) {
    return null
  }

  const raw = dataLines.join('\n')

  if (raw === '[DONE]') {
    return {
      type: eventType,
      done: true,
      text: '',
      payload: null,
    }
  }

  let payload = raw

  try {
    payload = JSON.parse(raw)
  } catch {
    payload = raw
  }

  return {
    type: eventType,
    done:
      payload?.done === true ||
      payload?.finished === true ||
      payload?.end === true,
    text: extractEventText(payload),
    payload,
  }
}

function extractEventText(payload) {
  if (typeof payload === 'string') {
    return payload
  }

  const candidates = [
    payload?.content,
    payload?.delta,
    payload?.text,
    payload?.message,
    payload?.answer,
    payload?.token,
    payload?.data,
    payload?.data?.content,
    payload?.data?.delta,
    payload?.data?.text,
  ]

  return candidates.find((item) => typeof item === 'string') || ''
}

function buildEventError(payload) {
  const error = new Error(payload?.message || 'SSE 事件流返回错误。')
  error.payload = payload
  return error
}

async function buildFetchError(response) {
  let payload = null

  try {
    payload = await response.clone().json()
  } catch {
    try {
      payload = await response.text()
    } catch {
      payload = null
    }
  }

  const error = new Error(
    typeof payload === 'object' && payload?.message
      ? payload.message
      : `SSE 请求失败（${response.status}）`,
  )

  error.status = response.status
  error.payload = payload
  return error
}

function joinUrl(baseUrl = '/api', path = '') {
  if (/^https?:\/\//i.test(path)) {
    return path
  }

  const normalizedPath = path.replace(/^\//, '')

  if (/^https?:\/\//i.test(baseUrl)) {
    return new URL(normalizedPath, ensureTrailingSlash(baseUrl)).toString()
  }

  const basePath = baseUrl.startsWith('/') ? baseUrl : `/${baseUrl}`
  return new URL(normalizedPath, `${window.location.origin}${ensureTrailingSlash(basePath)}`).toString()
}

function ensureTrailingSlash(value) {
  return value.endsWith('/') ? value : `${value}/`
}

function wait(duration) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, duration)
  })
}
