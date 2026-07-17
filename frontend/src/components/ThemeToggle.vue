<script setup>
import { computed } from 'vue'

import { useTheme } from '@/composables/useTheme'

const { isDarkTheme, toggleTheme } = useTheme()

const toggleLabel = computed(() => (isDarkTheme.value ? '切换到白天模式' : '切换到黑夜模式'))
const themeLabel = computed(() => (isDarkTheme.value ? '黑夜' : '白天'))
</script>

<template>
  <button
    type="button"
    class="theme-toggle"
    :aria-label="toggleLabel"
    :aria-checked="isDarkTheme ? 'true' : 'false'"
    :title="toggleLabel"
    role="switch"
    @click="toggleTheme"
  >
    <span class="theme-toggle__track" :class="{ 'theme-toggle__track--dark': isDarkTheme }">
      <span class="theme-toggle__thumb">
        <svg v-if="!isDarkTheme" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M12 4.75V3m0 18v-1.75M19.25 12H21m-18 0h1.75M17.13 6.87l1.24-1.24M5.63 18.37l1.24-1.24m10.26 0 1.24 1.24M5.63 5.63l1.24 1.24M12 16.75A4.75 4.75 0 1 0 12 7.25a4.75 4.75 0 0 0 0 9.5Z"
          />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M18.5 14.5A6.5 6.5 0 0 1 9.5 5.5a7 7 0 1 0 9 9Z"
          />
        </svg>
      </span>
    </span>
    <span class="theme-toggle__label">{{ themeLabel }}</span>
  </button>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 4px 6px 4px 4px;
  border-radius: 999px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--text-secondary);
  box-shadow: var(--theme-toggle-shadow);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease,
    transform 0.2s ease;
}

.theme-toggle:hover {
  background: var(--bg-surface-hover);
  border-color: var(--line-strong);
  color: var(--text-primary);
  transform: translateY(-1px);
}

.theme-toggle__track {
  position: relative;
  display: inline-flex;
  align-items: center;
  width: 50px;
  height: 28px;
  padding: 2px;
  border-radius: 999px;
  background: var(--theme-toggle-track);
}

.theme-toggle__track--dark .theme-toggle__thumb {
  transform: translateX(22px);
}

.theme-toggle__thumb {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  background: var(--theme-toggle-thumb);
  color: var(--theme-toggle-icon);
  box-shadow: var(--shadow-soft);
  transition:
    transform 0.22s ease,
    background-color 0.22s ease,
    color 0.22s ease;
}

.theme-toggle__thumb svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.theme-toggle__label {
  min-width: 2.2rem;
  font-size: 0.84rem;
  font-weight: 600;
}
</style>
