import { computed, onMounted, ref, watch } from 'vue'

const THEME_STORAGE_KEY = 'ai-agent-theme'
const THEME_COLOR_MAP = {
  light: '#f5efe2',
  dark: '#101418',
}

function normalizeTheme(value) {
  return value === 'dark' ? 'dark' : 'light'
}

function getInitialTheme() {
  if (typeof document !== 'undefined') {
    const documentTheme = document.documentElement.dataset.theme
    if (documentTheme) {
      return normalizeTheme(documentTheme)
    }
  }

  if (typeof window !== 'undefined') {
    return normalizeTheme(window.localStorage.getItem(THEME_STORAGE_KEY))
  }

  return 'light'
}

const theme = ref(getInitialTheme())
let isThemeWatcherReady = false

function applyTheme(nextTheme) {
  if (typeof document === 'undefined') {
    return
  }

  document.documentElement.dataset.theme = nextTheme
  document.documentElement.style.colorScheme = nextTheme

  const themeColorMeta = document.querySelector('meta[name="theme-color"]')
  if (themeColorMeta) {
    themeColorMeta.setAttribute('content', THEME_COLOR_MAP[nextTheme])
  }
}

function setTheme(nextTheme) {
  theme.value = normalizeTheme(nextTheme)
}

function toggleTheme() {
  setTheme(theme.value === 'light' ? 'dark' : 'light')
}

export function useTheme() {
  if (!isThemeWatcherReady) {
    watch(
      theme,
      (nextTheme) => {
        applyTheme(nextTheme)

        if (typeof window !== 'undefined') {
          window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme)
        }
      },
      { immediate: true },
    )

    isThemeWatcherReady = true
  }

  onMounted(() => {
    applyTheme(theme.value)
  })

  return {
    theme,
    isDarkTheme: computed(() => theme.value === 'dark'),
    setTheme,
    toggleTheme,
  }
}
