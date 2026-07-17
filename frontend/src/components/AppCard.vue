<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps({
  title: {
    type: String,
    required: true,
  },
  description: {
    type: String,
    required: true,
  },
  to: {
    type: String,
    default: '',
  },
  status: {
    type: String,
    default: '已就绪',
  },
  accent: {
    type: String,
    default: '#d6a246',
  },
  meta: {
    type: Array,
    default: () => [],
  },
})

const componentName = computed(() => (props.to ? RouterLink : 'div'))
</script>

<template>
  <component
    :is="componentName"
    :to="to || undefined"
    class="app-card panel"
    :class="{ 'app-card--disabled': !to }"
    :style="{ '--card-accent': accent }"
  >
    <div class="app-card__glow"></div>
    <div class="app-card__top">
      <span class="app-card__status">{{ status }}</span>
      <span class="app-card__arrow">{{ to ? 'OPEN' : 'SOON' }}</span>
    </div>
    <div class="app-card__body">
      <h3>{{ title }}</h3>
      <p>{{ description }}</p>
    </div>
    <div class="app-card__meta">
      <span v-for="item in meta" :key="item">{{ item }}</span>
    </div>
  </component>
</template>

<style scoped>
.app-card {
  position: relative;
  display: grid;
  gap: 22px;
  min-height: 220px;
  padding: 24px;
  border-radius: 26px;
  overflow: hidden;
}

.app-card::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  border: 1px solid var(--card-outline);
  pointer-events: none;
}

.app-card__glow {
  position: absolute;
  inset: auto -12% -20% auto;
  width: 180px;
  height: 180px;
  border-radius: 999px;
  background: radial-gradient(circle, color-mix(in srgb, var(--card-accent) 72%, white) 0%, transparent 68%);
  opacity: 0.24;
  filter: blur(12px);
}

.app-card__top,
.app-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.app-card__status,
.app-card__meta span,
.app-card__arrow {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--chip-border);
  background: var(--chip-bg);
  color: var(--text-secondary);
  font-size: 0.88rem;
}

.app-card__status {
  color: var(--card-accent);
  border-color: color-mix(in srgb, var(--card-accent) 30%, transparent);
  background: color-mix(in srgb, var(--card-accent) 12%, transparent);
}

.app-card__body {
  max-width: 30ch;
}

.app-card__body h3 {
  margin: 0 0 12px;
  font-size: clamp(1.4rem, 2vw, 1.9rem);
  letter-spacing: -0.04em;
}

.app-card__body p {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
}

.app-card:not(.app-card--disabled):hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--card-accent) 28%, transparent);
}

.app-card--disabled {
  opacity: 0.78;
}
</style>
