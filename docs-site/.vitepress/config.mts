// VitePress 官方文档配置 (Coolify 架构风格)

export default {
  lang: 'zh-CN',
  title: "Anabranch Core 官方文档",
  description: "Anabranch Core 是一个面向个人主开发命名空间的**多代码托管平台基线对齐与增量重签镜像同步引擎**。",
  cleanUrls: true,
  themeConfig: {
    siteTitle: "Anabranch Core",
    nav: [
      { text: '官网首页', link: '/' },
      { text: '快速入门', link: '/docs/' },
      { text: '系统架构', link: '/docs/architecture' },
      { text: '配置指南', link: '/docs/configuration' },
      { text: '常见问题', link: '/docs/faq' },
      { text: 'GitHub', link: 'https://github.com/CyberNova2333/anabranch-core' },
    ],
    sidebar: [
      {
        text: '快速入门',
        items: [
          { text: '项目概览与上手', link: '/docs/' },
        ]
      },
      {
        text: '系统架构',
        items: [
          { text: '架构设计与全景图', link: '/docs/architecture' },
        ]
      },
      {
        text: '配置与实践',
        items: [
          { text: '配置与使用指南', link: '/docs/configuration' },
        ]
      },
      {
        text: '运维与支持',
        items: [
          { text: '常见问题与排错', link: '/docs/faq' },
        ]
      }
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一页', next: '下一页' },
  },
}
