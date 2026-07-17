<script setup>
import AppCard from '@/components/AppCard.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { SITE_NAME } from '@/constants/brand'

const appCards = [
  {
    title: SITE_NAME,
    description: '面向单只股票的价值分析工作台，默认先做近三年财务体检，再按周行五步法给出投资建议。',
    to: '/apps/value-investing',
    status: '已就绪',
    accent: '#d6a246',
    meta: ['Vue 3', 'Axios', 'Fund API'],
  },
  {
    title: '投研报告中心',
    description: '基于结构化输出能力沉淀阶段性结论，适合未来扩展为报告汇总与导出中心。',
    status: '即将接入',
    accent: '#8fd9a9',
    meta: ['Report', 'Soon'],
  },
  {
    title: '策略实验室',
    description: '为后续的回测、策略对比和资产配置实验预留入口，主页已经具备多应用切换结构。',
    status: '预留入口',
    accent: '#7fa9ff',
    meta: ['Labs', 'Future'],
  },
]

const capabilities = [
  '结构化报告 `/api/fund/chat/report`',
  '工具增强 `/api/fund/chat/tools`',
  '价值分析 `/api/fund/chat/value`',
  '聊天历史 `/api/fund/history/{chatId}`',
]
</script>

<template>
  <main class="page-shell home-page">
    <div class="home-page__toolbar">
      <ThemeToggle />
    </div>

    <section class="home-hero">
      <div class="home-hero__copy">
        <p class="eyebrow">{{ SITE_NAME }}</p>
        <h1 class="section-title">
          为长期主义者准备的
          <span>投资应用中枢</span>
        </h1>
        <p class="soft-copy">
          主页承担应用切换入口，当前已接入 {{ SITE_NAME }}。界面刻意做成偏研究台与金融终端的混合风格，
          不是泛用聊天工具，而是让分析、记录和会话切换都更聚焦。
        </p>

        <div class="home-hero__actions">
          <RouterLink class="button-primary" to="/apps/value-investing">进入{{ SITE_NAME }}</RouterLink>
          <span class="pill">已根据 Spring Boot Fund API 对接</span>
        </div>
      </div>

      <aside class="home-hero__panel panel">
        <p class="eyebrow">Backend Snapshot</p>
        <h2>当前可用接口</h2>
        <ul>
          <li v-for="item in capabilities" :key="item">{{ item }}</li>
        </ul>
        <div class="home-hero__note">
          <strong>流式兼容说明</strong>
          <p>工具增强和价值分析都已接入 SSE，前端仍保留自动回落到 REST 的兼容逻辑。</p>
        </div>
      </aside>
    </section>

    <section class="home-grid">
      <AppCard
        v-for="card in appCards"
        :key="card.title"
        :title="card.title"
        :description="card.description"
        :to="card.to"
        :status="card.status"
        :accent="card.accent"
        :meta="card.meta"
      />
    </section>
  </main>
</template>

<style scoped>
.home-page {
  display: grid;
  gap: 32px;
  min-height: 100dvh;
  align-content: start;
}

.home-page__toolbar {
  display: flex;
  justify-content: flex-end;
}

.home-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.85fr);
  gap: 28px;
  align-items: stretch;
}

.home-hero__copy,
.home-hero__panel {
  border-radius: 32px;
  padding: clamp(24px, 3vw, 40px);
}

.home-hero__copy {
  position: relative;
  overflow: hidden;
  background: var(--hero-copy-bg);
  border: 1px solid var(--hero-copy-border);
  box-shadow: var(--shadow);
}

.home-hero__copy::after {
  content: '';
  position: absolute;
  inset: auto -20% -24% auto;
  width: 340px;
  height: 340px;
  border-radius: 999px;
  background: radial-gradient(circle, var(--accent-glow) 0%, transparent 68%);
  filter: blur(14px);
}

.home-hero__copy h1 {
  max-width: 10ch;
  margin: 18px 0 18px;
  font-size: clamp(3rem, 8vw, 5.8rem);
  line-height: 0.95;
  text-wrap: balance;
}

.home-hero__copy h1 span {
  display: block;
  color: var(--accent-strong);
}

.home-hero__copy p.soft-copy {
  max-width: 60ch;
  margin: 0;
  font-size: 1.04rem;
}

.home-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  margin-top: 28px;
}

.home-hero__panel {
  display: grid;
  align-content: start;
  gap: 18px;
}

.home-hero__panel h2 {
  margin: 0;
  font-size: 1.6rem;
  letter-spacing: -0.04em;
}

.home-hero__panel ul {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-left: 1.1rem;
  color: var(--text-secondary);
  line-height: 1.7;
}

.home-hero__note {
  display: grid;
  gap: 8px;
  padding: 18px;
  border-radius: 24px;
  background: var(--hero-note-bg);
  border: 1px solid var(--hero-note-border);
}

.home-hero__note strong {
  color: var(--text-primary);
}

.home-hero__note p {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.65;
}

.home-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

@media (max-width: 1080px) {
  .home-hero {
    grid-template-columns: 1fr;
  }

  .home-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .home-page {
    gap: 24px;
  }

  .home-grid {
    grid-template-columns: 1fr;
  }

  .home-hero__copy h1 {
    max-width: none;
    font-size: clamp(2.6rem, 18vw, 4.2rem);
  }
}
</style>
