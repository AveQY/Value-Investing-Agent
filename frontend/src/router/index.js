import { createRouter, createWebHistory } from 'vue-router'

import { SITE_NAME } from '@/constants/brand'
import HomePage from '@/pages/HomePage.vue'
import ValueAdvisorPage from '@/pages/ValueAdvisorPage.vue'

const routes = [
  {
    path: '/',
    name: 'value-investing-home',
    component: ValueAdvisorPage,
    meta: {
      title: SITE_NAME,
    },
  },
  {
    path: '/apps',
    name: 'home',
    component: HomePage,
    meta: {
      title: '应用总览',
    },
  },
  {
    path: '/apps/value-investing',
    name: 'value-investing',
    component: ValueAdvisorPage,
    meta: {
      title: SITE_NAME,
    },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  const pageTitle = to.meta?.title
  const title = !pageTitle || pageTitle === SITE_NAME ? SITE_NAME : `${pageTitle} | ${SITE_NAME}`
  document.title = title
})

export default router
