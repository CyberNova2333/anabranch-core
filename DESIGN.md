---
version: 2.0.0
name: Anabranch Core Primer Tactile Design System
description: 面向多源分块下载与并发选路框架（anabranch-core）的 Primer 质感设计系统规范。将 GitHub Primer 功能原语与水系拓扑分流地图工程美学深度融合，定义分块调度流向图、多线路实时 EMA 竞速仪表、逐块校验状态徽章与架构文档交互契约。
colors:
  primary: '#0969da'
  accent-fg: '#0969da'
  accent-emphasis: '#0969da'
  accent-subtle: '#ddf4ff'
  accent-dark-fg: '#2f81f7'
  canvas-default: '#ffffff'
  canvas-overlay: '#ffffff'
  canvas-inset: '#f6f8fa'
  canvas-subtle: '#f6f8fa'
  canvas-dark-default: '#0d1117'
  canvas-dark-overlay: '#161b22'
  canvas-dark-inset: '#010409'
  canvas-dark-subtle: '#161b22'
  fg-default: '#1f2328'
  fg-muted: '#57606a'
  fg-on-emphasis: '#ffffff'
  fg-dark-default: '#f0f6fc'
  fg-dark-muted: '#8b949e'
  border-default: '#d0d7de'
  border-muted: '#d8dee4'
  border-dark-default: '#30363d'
  border-dark-muted: '#21262d'
  success-fg: '#1a7f37'
  success-emphasis: '#1f883d'
  success-subtle: '#dafbe1'
  success-dark-fg: '#3fb950'
  danger-fg: '#cf222e'
  danger-emphasis: '#cf222e'
  danger-subtle: '#ffebe9'
  danger-dark-fg: '#f85149'
  attention-fg: '#9a6700'
  attention-subtle: '#fff8c5'
  attention-dark-fg: '#d29922'
typography:
  body-md:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0px
  body-sm:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0px
  ui-label:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
    fontSize: 14px
    fontWeight: 600
    lineHeight: 1.4285
    letterSpacing: 0px
  heading-section:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: 0px
  code-inline:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"
    fontSize: 12px
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: 0px
rounded:
  none: 0px
  sm: 3px
  md: 6px
  lg: 8px
  pill: 9999px
spacing:
  none: 0px
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
components:
  topology-node:
    backgroundColor: '{colors.canvas-default}'
    textColor: '{colors.fg-default}'
    typography: '{typography.body-md}'
    rounded: '{rounded.md}'
    padding: '{spacing.md}'
  chunk-track:
    backgroundColor: '{colors.canvas-inset}'
    textColor: '{colors.fg-default}'
    typography: '{typography.code-inline}'
    rounded: '{rounded.sm}'
    padding: '{spacing.xxs}'
  source-pill:
    backgroundColor: '{colors.accent-subtle}'
    textColor: '{colors.accent-fg}'
    typography: '{typography.code-inline}'
    rounded: '{rounded.pill}'
    padding: '{spacing.xxs}'
---

# Anabranch Core 质感设计系统规范 (`DESIGN.md`)

本文档是 [`anabranch-core`](file:///D:/Projects/CyberNova2333/git-repos/anabranch-core) 的设计系统规范。项目基于 GitHub Primer 功能主义设计体系，融入**水系分支分流与拓扑网络工程美学（Cartographic Topology & DAG Materiality）**，指导多源分块下载、线路状态观测、架构图渲染与文档站的可视化视觉交互。

---

## 1. 核心设计哲学与物理质感

1. **水系拓扑与分流矢量（Cartographic Flow & Vector Lines）**：
   - 展现多线路（`Source`）并发分片、按块切源与竞速合并的流动感，矢量线条平滑且具备流向脉冲光。
   - 拓扑节点具备沉稳的 Primer `Box` 物理外框（`1px solid var(--border-default)`）与顶边微高光（`inset 0 1px 0 rgba(255, 255, 255, 0.25)`）。
2. **多源分块凹槽导轨（Etched Chunk Tracks）**：
   - 分块进度条采用物理蚀刻凹槽表现：底层为内凹槽（`background: var(--canvas-inset); box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2)`），每个分片（Chunk）根据不同源线路填充不同的语义微光色块，清晰呈现多源混合拼接过程。
3. **触觉回弹与磁吸反馈（Tactile Snapping）**：
   - 交互操作具备微动物理反馈（`:hover` `translateY(-0.5px)`，`:active` `translateY(0.5px) scale(0.99)` + `inset 0 2px 4px rgba(0, 0, 0, 0.2)`）。

---

## 2. 调色板与语义 Token

### 2.1 W3C `$value/$type` Token 注册表

| Primer 功能 Token | Light `$value` | Dark `$value` | 物理拓扑映射 |
| :--- | :--- | :--- | :--- |
| `accent.fg` | `#0969da` | `#2f81f7` | 主分流节点连线、当前最优 EMA 线路指示 |
| `accent.emphasis` | `#0969da` | `#1f6feb` | 首块并发抢跑胜者指示灯、主操作按键 |
| `accent.subtle` | `#ddf4ff` | `rgba(56,139,253,0.15)` | 线路标识胶囊底色、选路策略卡片底色 |
| `canvas.default` | `#ffffff` | `#0d1117` | 拓扑图画板底色、架构卡片主体 |
| `canvas.inset` | `#f6f8fa` | `#010409` | 分块凹槽导轨底色、代码块内凹槽 |
| `canvas.subtle` | `#f6f8fa` | `#161b22` | 线路统计表头、次要按钮底板 |
| `fg.default` | `#1f2328` | `#f0f6fc` | 接口类型名、吞吐数值、核心正文 |
| `fg.muted` | `#57606a` | `#8b949e` | EMA 延迟说明、连续失败计数、辅助标签 |
| `border.default` | `#d0d7de` | `#30363d` | 节点物理外框、导轨边框 |
| `success.fg` | `#1a7f37` | `#3fb950` | 逐块哈希校验通过（Integrity Verified）、线路健康 |
| `attention.fg` | `#9a6700` | `#d29922` | 单块失败触发重试切源（SWITCH_SOURCE） |
| `danger.fg` | `#cf222e` | `#f85149` | 线路熔断停用（DISABLE_SOURCE）、全源终止（ABORT） |

---

## 3. 字体栈与工程排版

- **工程字体栈（`--font-sans`）**：`-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif`
- **等宽数据字体（`--font-mono`）**：`ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace`
- **排版密度**：基准字号 `14px`，代码与吞吐量 `12px` 等宽且强制对齐 `tabular-nums`。

---

## 4. 架构与组件约束

1. **零内置 URL 契约**：API 规范严格要求不内置硬编码 URL，界面示例与文档代码展示一律通过 `Source.Resolver` 进行解耦示意。
2. **状态纯只读观测**：`Stats` 模块仅供策略读取，仪表盘组件不得包含写入逻辑。
3. **逐块校验立体徽章**：校验通过的分片带有立体微拟物勾选标，展示确定性工程品质。
