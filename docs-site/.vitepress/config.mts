import { defineConfig } from 'vitepress'
import { guideLinks } from './guide-links.mts'

const guidePages: Record<string, string> = {
  'README.md': 'docs/architecture',
  'walkthroughs/one-real-run.md': 'docs/architecture',
  'code-map.md': 'docs/code-map',
  'modules/scheduling-and-failover.md': 'docs/mechanisms',
  'modules/resume-and-integrity.md': 'docs/mechanisms',
  'modules/engine-and-aria2-boundary.md': 'docs/contracts',
  'glossary.md': 'docs/code-map',
  'references/source-evidence.md': 'docs/decisions',
  'references/quality-review.md': 'docs/decisions',
  'change-log.md': 'docs/decisions',
}

export default defineConfig({
  cleanUrls: true,
  markdown: {
    config(md) {
      md.use(guideLinks(guidePages))
    },
  },
  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      title: 'Anabranch Core 文档',
      description: '面向 Java 8 与 Android (minSdk 21) 的零依赖多源分块下载库，附可选 aria2 JNI 后端。',
      themeConfig: {
        siteTitle: 'Anabranch Core',
        nav: [
          { text: '首页', link: '/' },
          { text: '1. 起步与集成', link: '/docs/' },
          { text: '2. 契约与机制', link: '/docs/contracts' },
          { text: '3. 原理与导览', link: '/docs/architecture' },
          { text: 'GitHub', link: 'https://github.com/CyberNova2333/anabranch-core' },
        ],
        sidebar: [
          {
            text: '1. 起步与集成部署',
            items: [
              { text: '定位、双产物与快速开始', link: '/docs/' },
              { text: '集成配置、原生构建与排错', link: '/docs/deployment' },
            ],
          },
          {
            text: '2. 接口与扩展契约',
            items: [
              { text: '公开 API 与扩展契约', link: '/docs/contracts' },
              { text: '调度熔断、续传与校验机制', link: '/docs/mechanisms' },
            ],
          },
          {
            text: '3. 原理与源码导览',
            items: [
              { text: '架构全景与一次真实运行', link: '/docs/architecture' },
              { text: '源码地图与目录索引', link: '/docs/code-map' },
              { text: '设计决策、演进与发布迁移', link: '/docs/decisions' },
            ],
          },
        ],
        outline: { level: [2, 3], label: '本页目录' },
        docFooter: { prev: '上一页', next: '下一页' },
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      title: 'Anabranch Core Documentation',
      description: 'Zero-dependency multi-source chunked downloader for Java 8 and Android (minSdk 21), with an optional aria2 JNI backend.',
      themeConfig: {
        siteTitle: 'Anabranch Core',
        nav: [
          { text: 'Home', link: '/en/' },
          { text: '1. Getting Started', link: '/en/docs/' },
          { text: '2. Contracts & Mechanisms', link: '/en/docs/contracts' },
          { text: '3. Architecture & Source', link: '/en/docs/architecture' },
          { text: 'GitHub', link: 'https://github.com/CyberNova2333/anabranch-core' },
        ],
        sidebar: [
          {
            text: '1. Getting Started & Deployment',
            items: [
              { text: 'Overview & Quick Start', link: '/en/docs/' },
              { text: 'Integration, aria2 Build & FAQ', link: '/en/docs/deployment' },
            ],
          },
          {
            text: '2. API & Extension Contracts',
            items: [
              { text: 'Public API & Extension Points', link: '/en/docs/contracts' },
              { text: 'Scheduling, Resume & Integrity', link: '/en/docs/mechanisms' },
            ],
          },
          {
            text: '3. Architecture & Source Guide',
            items: [
              { text: 'Architecture & One Real Run', link: '/en/docs/architecture' },
              { text: 'Code Map & Directory Index', link: '/en/docs/code-map' },
              { text: 'Design Decisions & Releasing', link: '/en/docs/decisions' },
            ],
          },
        ],
        outline: { level: [2, 3], label: 'On this page' },
        docFooter: { prev: 'Previous page', next: 'Next page' },
      },
    },
  },
  themeConfig: {
    search: { provider: 'local' },
  },
})
