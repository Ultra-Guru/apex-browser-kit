# apex-browser-kit

Embedded WebView automation engine with Compose chrome UI — 从
[Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 拆出的浏览器能力库。

[![CI](https://github.com/Ultra-Guru/apex-browser-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/Ultra-Guru/apex-browser-kit/actions/workflows/ci.yml)
![Consumer](https://img.shields.io/badge/consumer-Android--Guru--Agent-blue)

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:browser-core` | 纯 Kotlin JVM（零 Android 依赖） | DOM 模型（`DomElement`/`PageSnapshot`）、注入 JS（`BrowserScript`）、快照解析（`DomParser`）、重试/熔断（`RetryPolicy`/`CircuitBreaker`） |
| `:browser-engine` | Android Library（无 UI） | 无头 WebView 自动化引擎（`BrowserEngine`）：状态机、标签管理、物理触摸注入、加载等待、Cookie 持久化、崩溃恢复 |
| `:browser-chrome` | Android Library（Compose UI + 纯 View 实时窗） | 「给人用的浏览器界面」：地址胶囊即进度条、标签条/总览、页内查找、下载面板、JS 弹窗/权限浮层、全屏接管浮窗（`BrowserOverlay`）、透明实时页面悬浮窗（`LiveViewOverlay`，v1.4.0）、App 内嵌浏览器宿主（`InAppChromeHost` + 承载仲裁，v1.5.0） |

依赖方向：`chrome → engine → core`（`api` 传递，下游引 chrome 即得全套类型）。

## 安全：JS 注入边界（`JsLiteral`）

浏览器 Agent 天生暴露在**间接提示注入**下：网页正文可能被模型当作「元素 ref」原样回传。
因此本库把「外部字符串 → JS 字面量」的编码收敛到唯一入口
**`JsLiteral.string`**（`browser-core/.../core/JsLiteral.kt`），覆盖 `'` `\`、
换行/回车/制表等控制字符，以及 U+2028 / U+2029（JSON 合法但 JS 字面量非法）。

> **v1.0.1 修复（已随 rebase 并入 1.1.0 发布列车）**：v1.0.0 的注入点是 `private fun String.toJsonString() = "'$this'"`，
> 零转义。实测 `ref = "+alert(document.cookie)+"` 可生成
> `document.querySelector('[data-apex-hash='+alert(document.cookie)+']')`
> —— **由 ref 字符串驱动的页内任意 JS 执行**。现所有注入点统一走 `JsLiteral.string`。

两条必须遵守的约定：

1. **新增注入点时必须用 `JsLiteral.string`**，不得自行拼接或手写转义
   （v1.0.0 曾同时存在三套互不一致的转义，其中一套把 `ref` 按 `"` 转义却嵌在
   单引号选择器里）。
2. **ref 定位不经 CSS 解析**：`[data-apex-hash=<ref>]` 会让 CSS 解析器二次解析
   ref，含引号 / 空格 / `]` 的 ref 会抛 `SyntaxError`，并一路冒泡成
   `ElementNotFoundException` + 打开熔断器。故统一走
   「`querySelectorAll('[data-apex-hash]')` + JS `===` 比较属性值」。

## 纯逻辑下沉：页面类型分类（`PageClassifier`）

`pageType` 的信号采集（`BrowserScript.PAGE_TYPE_JS`）与分类逻辑（`PageClassifier`）
已分离。分类策略此前是 `BrowserEngine` 里的 `private` 方法、与 WebView 耦合、
**零测试覆盖**；现下沉到零 Android 依赖的 `:browser-core`，优先级表
（auth > video > form > article > search > list > portal > generic）
由 `PageClassifierTest` 穷举锁定，并对外开放给任何消费方。

> **v1.0.1 移除** `BrowserScript.waitForSelectorJs`：它返回 `Promise`，而
> `evaluateJavascript` 不等待 Promise，回调恒为 `null`，导致
> `browser_navigate(wait_for=…)` 的 `selectorFound` **永远为 false**。
> 已由同步的 `BrowserScript.selectorPresentJs` + Kotlin 侧轮询取代。

## 快照契约（v1.0.1）

| 约定 | 说明 |
|---|---|
| **ref 绝不编造** | `ref` 是 click / input / select 唯一的定位主键，全部经 `data-apex-hash` 反查。缺失时 `ref` 留空并在摘要里标注「不可操作」，而不是回退成 `r1`/`r2` 这类永远解析不到的伪 ref（旧行为会白耗 3 次重试并**打开熔断器**）。 |
| **截断如实上报** | 注入脚本按 `SNAPSHOT_MAX_ELEMENTS` 硬上限截断，现回传 `{v,total,truncated,elements}` 信封；摘要会写明「页面实际匹配 N 个，已达上限」。不告知的话模型会把这一批当成全部。`PageSnapshot.truncated` / `totalCandidateCount` 同步可供程序判断。`DomParser.parse` 仍兼容旧的裸数组形态。 |
| **摘要优先级** | 超 token 预算时按「可交互 > 浅层 > 有标签」决定谁进预算（依赖脚本计算的真实 `depth`，上限 20），但**输出顺序**仍按 `bid` 保持页面自然阅读顺序。 |
| **坐标系统一** | 两条快照路径（`snapshotJs` 与 A11y 降级源）均返回**文档**坐标（加 scroll 偏移），避免物理触摸兜底在 A11y 路径上指向错误位置。 |

## 兼容矩阵（F3：Compose Compiler 版本必须 ≤ 宿主）

| 依赖 | 版本 |
|---|---|
| Kotlin | 2.2.21 |
| AGP | 8.13.2 |
| Gradle | 8.14 |
| Compose BOM | 2025.11.01 |
| Kotlin Coroutines | 1.10.2 |
| kotlinx.serialization | 1.9.0 |
| compileSdk / minSdk | 35 / 26 |

> Requires **Kotlin 2.2.21 + Compose compiler plugin of the same version**（composite build 侧 AGP 必须同为 8.13.2）。

## 消费方式（按阶段递进）

### E1 · 迁移期：Composite Build

宿主 `settings.gradle.kts`：

```kotlin
// 兄弟目录存在时接入（本地开发 / CI 双仓检出）；不存在则走远端坐标
if (file("../apex-browser-kit").isDirectory) {
    includeBuild("../apex-browser-kit")
}
```

宿主 `build.gradle.kts` 依赖照常写远端坐标，Gradle 自动用本地 composite build 替换：

```kotlin
dependencies {
    implementation("com.apex.browser:browser-core:1.5.0")
    implementation("com.apex.browser:browser-engine:1.5.0")
    implementation("com.apex.browser:browser-chrome:1.5.0")
}
```

### E2 · 过渡期：publishToMavenLocal

```bash
./gradlew publishToMavenLocal   # 库侧
# 宿主 repositories 含 mavenLocal() 即可按版本消费
```

### E3 · 稳定期：GitHub Packages

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/AceGuru-mjh/apex-browser-kit")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```

## DI 接法（本库零 DI 依赖，F1）

库内**不用 Hilt**（避免强绑下游宿主），单例由工厂 object 提供，宿主在自己的
Module 里 provide：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BrowserKitModule {
    @Provides @Singleton
    fun provideBrowserEngine(@ApplicationContext ctx: Context): BrowserEngine =
        BrowserEngineFactory.get(ctx)

    @Provides @Singleton
    fun provideApexChromeWiring(@ApplicationContext ctx: Context, engine: BrowserEngine): ApexChromeWiring =
        ApexChromeWiringFactory.get(ctx, engine)

    @Provides @Singleton
    fun provideBrowserOverlay(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
        wiring: ApexChromeWiring,
    ): BrowserOverlay = BrowserOverlayFactory.get(ctx, engine, wiring)
}
```

宿主自己的视觉层（悬浮球等）经 [BrowserVisualHook] 注入，库不感知其存在：

```kotlin
class HostVisualHook(private val neonBall: CyberNeonBallManager) : BrowserVisualHook {
    override fun onAgentDrivingStarted() = neonBall.show()
    override fun onWaitingHuman() = neonBall.pulse()
    override fun onSessionHidden() = neonBall.hide()
}
```

## CI 防线（充分利用 GitHub Actions）

### `ci.yml` — 编译与下游兼容（需要 Android SDK）

1. **build-test**：三模块编译 + 单测（`:browser-core:test` 独立跑，证明纯 JVM）+
   `publishToMavenLocal` 可发布性冒烟（坐标/POM 完整性）。
2. **consumer-check**：每次 push/PR 把宿主 [Android-Guru-Agent] 检出为兄弟目录，
   经 composite build 替换后跑宿主 `:app:compileDebugKotlin` + 单测 ——
   **库一改动坏下游，立刻在本仓库 CI 看到**（F6 跨仓库漂移的解法）。

### `guard-rails.yml` — 结构门禁（**秒级，无需 Android SDK**）

编译能过 ≠ 该合。以下都是「便宜地写下来、贵得重新发现」的不变量：

| Job | 拦什么 |
|---|---|
| Library Invariants | `:browser-core` 混进 `android.*` 依赖；依赖箭头反向（core → engine）；注入点绕过 `JsLiteral.string`；零转义 helper 复活；注入脚本返回 `Promise`；**WebView 沙箱被放松**（见下） |
| Resource Integrity | 资源漏 `browser_` 前缀（AGP 只告警，撞的是**宿主** `R`）；`values` 与 `values-*` 键集/占位符不对齐 |
| Structural Quality | God-file 体积预算；`printStackTrace()`；反射派发；括号失衡（词法感知） |
| Supply Chain | 仓库里出现 credential 字面量（PAT/AK/PEM/`password=` 实值）；Gradle 分发包缺 `distributionSha256Sum`；wrapper JAR 被换成 stub；workflow 里 `curl \| sh`；三模块版本号分裂 / README 消费示例过期 / 兼容矩阵漂移；`api/` 基线外出现 public 删除或签名变更（纯新增放行） |
| **Gate Self-Tests** | 每个门禁都拿**真实注入的违规**去验自己会失败 —— 只跑通过的门禁证明不了任何事 |
| Inventory | 文件数 / 行数 / 模块清单 |

### 为什么 WebView 加固要自己写门禁

CodeQL 里所有 WebView 相关查询（`websettings-file-access`、
`webview-addjavascriptinterface`、`improper-webview-certificate-validation` …）
都属于 **`security-extended` 套件，不在默认套件**；开启该套件是**仓库设置**（PR 改不了），
且 Kotlin 分析需要完整构建。在此之前 `check_webview_hardening.py` 是唯一在检查这些
不变量的人 —— 它把 SSL 处理、file/content 访问、混合内容、远程调试、`addJavascriptInterface`
全部锁成门禁。详见 [SECURITY.md](SECURITY.md)。

**一条容易踩的坑**：`allowFileAccessFromFileURLs` 自 API 30 起被系统忽略，但本库
`minSdk 26`，在 API 26~29 上它的默认值仍是 `true`。因此那行 `= false` 是**真起作用的
安全控制，不能当废弃代码删掉** —— 门禁会拦住删除行为。

`dependency-submission.yml` 另把 Gradle 解析结果喂给 GitHub 依赖图，
使 Dependabot 能对**传递依赖**告警（消费方通过我们的坐标间接依赖它们）。

### 宿主自动同步链路（v1.1.0 起）

本仓库 main 的每次合入都会**自动流入宿主** [Android-Guru-Agent]，全程无人工：

```
本仓库 push main
  → notify-host.yml 等待本提交 CI + Guard Rails 全绿（口径见下）
  → repository_dispatch 宿主（event: browser-kit-updated，HOST_SYNC_TOKEN）
  → 宿主 sync-browser-kit.yml 校验上游门禁全绿后 bump gradle/browser-kit.lock
    （PAT 推送 —— GITHUB_TOKEN 推送不触发 workflow，防递归设计）
  → push main 自动点燃宿主 release 流水线 → 新 APK（含本仓库最新代码）发布
```

门禁口径（双侧一致）：只统计**构建健康**类 check-run（CI 的 Build & Test /
Consumer Check + Guard Rails 全套）；显式排除 Dependabot*（长跑依赖升级）、
Gradle dependency graph（数据洞察链路）、notify-host 自身（自引用）。上游有
失败项时宿主**拒绝同步并显性报错**（fail loud），绝不跟红。另有宿主侧 30 分钟
cron 轮询兜底（防丢事件），`gradle/browser-kit.lock` 保证宿主构建可复现。

### 本地复现

```bash
./test.sh          # 门禁 + 门禁自测 + Gradle 单测
./test.sh gates    # 只跑门禁：纯 stdlib Python，不需要 JDK / Android SDK
```

门禁一律写成**可注入违规的自测**，见 `scripts/tests/`。改门禁后请连自测一起跑 ——
正则腐化会让门禁静默变成永远绿的空壳，这是 CI 门禁最常见的失效方式。

## 留在宿主的部分

- `BrowserAgentTools`（browser_* 工具协议：依赖宿主 `AgentTool`/`StreamingAgentTool`）
- `CyberNeonBallManager` / `NeonRingView`（宿主视觉装饰）
- 宿主 DI 接线模块（Hilt）
- `plugin-web-automation` 插件 APK —— 宿主已将其移除，浏览器自动化改以内置
  `:platform:browser-agent` 模块形态集成（见宿主仓库 PR）

## v1.1.0 变更（Agent 可靠性与高级功能）

### 错误率削减（修复既有缺陷）

1. **点击坐标密度换算修复**：旧实现把 `getBoundingClientRect` 的 CSS 像素直接当
   视图物理像素派发——density>1 设备上点击点系统性偏向左上（误点主因）。现按
   「视图宽 / CSS 视口宽」实测缩放系数换算（同时正确处理页面缩放）；
2. **JS 字面量统一转义**：`toJsonString()` 此前零转义——selector 含 CSS 属性
   选择器引号（`[href='x']`）、文本含撇号/换行都会撕裂 JS（静默失败）。现统一
   转义反斜杠/引号/换行/行分隔符 + `</` 序列；
3. **点击前置 scrollIntoView + 遮挡检测**：折叠线以下元素不再坐标越界；
   `elementFromPoint` 命中测试发现粘性顶栏/模态遮罩拦截时下移重试，仍被遮则
   明确报「被遮挡」而非静默误点；
4. **React/Vue 安全输入**：`el.value = x` 直赋改为原型链 native setter +
   input/change 事件（受控组件不再弹回）；contenteditable 支持；多行文本不再
   撕裂 JS；新增 append / pressEnter 模式；
5. **模糊自愈定位**：ref 失配时用快照缓存的 tag+文本模糊重定位并重打原 ref
   ——SPA 局部刷新不再直接失败；新增 `locateElements()` 供 Agent 主动按文本锚定；
6. **动作后真实 diff**：`PostActionState.urlChanged` 旧实现恒 false、
   `newElementsCount` 是当前计数而非增量——现按动作前后探针计算真实 URL 变化
   与元素增量，并携带 `currentUrl`。

### 新增高级 API

| API | 说明 |
|---|---|
| `pressKey(key)` | 键盘事件注入（Enter/Tab/Escape/方向键…），Enter 携带表单隐式提交语义 |
| `hover(ref)` | mouseover/mouseenter/mousemove 悬停序列（下拉菜单、:hover 样式） |
| `drag(fromRef, toRef)` | 触摸拖拽（DOWN → 插值 MOVE → UP），滑块/排序/拖放 |
| `extractContent(mode)` | 结构化抽取：article 正文 / tables 表格 / links 链接 / meta 元信息 |
| `executeJavaScript(js)` | 原生 JS 逃生舱（带超时），长尾页面逻辑兜底 |
| `getCookies(url?)` | 读取 Cookie（登录态诊断） |
| `networkLog(limit, urlContains)` | 网络日志按 URL 子串过滤 |
| `locateElements(text, tag)` | 按文本模糊查找元素并返回可用 ref |

版本：三模块 1.0.0 → **1.1.0**（additive API + 默认参数扩展，宿主源码兼容）。

## v1.2.0 变更（逻辑层文案注入与发布工程）

### ChromeStrings 注入（已知取舍 #1 消解）

chrome 层有三处用户可见文案产生于**逻辑层**而非 Compose 上下文（`stringResource`
不可达）：`BrowserChromeController` 的 6 条 snackbar、`SuggestionsBuilder` 的
4 条联想副标题、`ApexChromeWiring` 的 3 条 JS 弹窗按钮审计标签 —— v1.0.0 起
硬编码中文。v1.2.0 把这 13 条收敛为 `ChromeStrings` 注入接口：

- `ZhChromeStrings`（默认，`ChromeStrings.DEFAULT`）：与旧硬编码**逐字一致**，
  不注入即行为零变化；
- `EnglishChromeStrings`：词汇与 `values-en` 镜像同一套语感
  （Paste and go / URL / Search / Confirm / Deny / Close …）；
- 任意自译实现（其他语言 / 品牌语气）。

接入是**带默认值的尾参**，现有调用点零改动（源码兼容）：

```kotlin
// 控制器直构（BrowserChrome 的 controller 参数同款注入点）
val controller = BrowserChromeController(gateway, scripts, config, EnglishChromeStrings)

// 接线器工厂（宿主 DI Module 里同款）
val wiring = ApexChromeWiringFactory.get(ctx, engine, EnglishChromeStrings)
```

> `ApexChromeWiringFactory` 是进程级单例：**首次**调用的 `strings` 生效，切换
> 实现请保证首次就传入（或测试里 `reset()`）。`SuggestionsBuilder.build` 的
> `strings` 尾参由控制器自动透传，纯函数调用方也可独立注入。

Compose UI 层文案继续走 `res/values` + `values-en`（`browser_` 前缀强制），
两套机制各管一层：UI 层跟系统语言，逻辑层跟注入实现。契约由
`ChromeStringsTest` 锁定（中文逐字一致 / 两实现 13 条非空 / `tabSwitchHint`
含 host / 接口成员计数防漂移）。

### 发布工程

| 项 | 说明 |
|---|---|
| `tag-release.yml` | push main 自动读三模块 `version =`（分裂即 fail，与 `check_versions.py` RULE 1 同口径），无对应 tag 则 `git tag vX.Y.Z` 并推送（幂等：已存在跳过不报错；版本回退只跳过不强推）。tag push 不会点燃 `notify-host.yml`（其 `on:` 仅 `push.branches=[main]`，且 GITHUB_TOKEN push 本就不触发 workflow，双保险） |
| 版本可追溯 | 此前版本只存在于 `build.gradle.kts`、从未留下 git tag；现在每个发布列车落成 `vTag`，坐标 ↔ 源码状态一一对应 |

版本：三模块 1.1.0 → **1.2.0**（additive API，全部为带默认值参数的尾参扩展，
宿主源码兼容）。

## v1.5.0 变更（App 内嵌浏览器宿主 / 三承载面互斥仲裁）

### App 内嵌浏览器宿主（`InAppChromeHost`，新增）

- **零悬浮窗权限的完整浏览器**：本库的可视层此前只有系统悬浮窗
  （`TYPE_APPLICATION_OVERLAY`，需 `SYSTEM_ALERT_WINDOW` 特殊权限，无权限时
  静默降级）—— 权限未授予的宿主用户永远看不见浏览器。`InAppChromeHost`
  把引擎活动 WebView 连同完整 `BrowserChrome` 嵌进宿主 **App 自己的窗口**
  （Activity 组合树内 `AndroidView` 容器），浏览器从此**无条件可用**；
- **四动作生命周期**（全主线程）：`attach`（页面进入组合：承载权 claim +
  wiring resync 对齐 chrome 数据源 + 换挂活动 WebView）/ `detach`（页面离开
  组合：WebView 归还引擎无头驱动）/ `pause`（Activity ON_PAUSE：让出承载权，
  悬浮窗可视层可接管）/ `resume`（ON_RESUME：重新换挂）；
- **换挂走引擎多播**：订阅 `BrowserEngine.ActiveTabListener`（而非 wiring 的
  单回调 var，不与 `BrowserOverlay` 抢占）—— 标签切换/新建/崩溃重建自动换页；
- 宿主接法：`InAppChromeHostFactory.get(engine, wiring)` 注册进宿主 DI
  （与另两个工厂同款单例纪律）。

### P1 渲染修复：接管浮窗「有 chrome 无页面」（`BrowserChrome` 页区透明）

- 旧实现根 Column 恒刷不透明 `palette.background`，而真实 WebView 宿主在
  **组合树之外**（同窗口兄弟 View 层）—— 不透明底色把 WebView 整个盖住：
  人工接管浮窗展开后只见地址栏 + 一块纯色页区，**页面内容从未显示过**
  （ChromeBar / TabStrip 自带不透明底，根底色对头部是冗余双重绘制）；
- 修复：`BrowserChrome` 新增 `transparentPageArea: Boolean = false`（默认
  保持旧视觉，页区由 pageSlot 自绘的消费者零影响）；`BrowserOverlay` 传
  true —— 页区透出真实 WebView，`InAppChromeHost` 消费方同款传法。

### 三承载面互斥仲裁（`InAppHostingArbiter`，新增）

- 同一 WebView 同一时刻只能有一个父容器：in-app 容器 / 全屏接管浮窗 /
  实时页面卡片三个承载面需要一个进程级裁决点；
- **in-app 承载期间**：`BrowserOverlay` 的 WAITING_HUMAN 不再自动展开（
  in-app chrome 就是人的 UI），已展开则收起；`LiveViewOverlay` 可见卡片转
  GHOST（页面在 App 内直接可见，换挂/自愈同步被守卫拦下，不抢 WebView）；
- **in-app 退场**（页面离开/Activity 暂停）：接管浮窗按 WAITING_HUMAN 补
  展开、实时卡片按引擎状态机重放 —— 用户离开 App 后悬浮窗体验不变；
- 纯 JVM 无 Android 依赖，契约由 `InAppHostingArbiterTest` 八项断言锁定
  （幂等迁移/通知一次/注销停止/自注销不 CME/异常不传播）。

版本：三模块 1.4.0 → **1.5.0**（additive API；`InAppChromeHost` /
`InAppChromeHostFactory` / `InAppHostingArbiter` 均为新增，两个既有浮窗仅
加守卫分支，无破坏性变更）。

## v1.4.0 变更（实时页面悬浮窗 / 悬浮窗 P0 崩溃修复 / 活动标签广播）

### 透明实时页面悬浮窗（`LiveViewOverlay`，新增）

- **Agent 一用浏览器就自动弹出**：订阅引擎状态机，`AGENT_DRIVING` 即呈现
  透明玻璃卡片浮窗（85 屏宽 × 60 屏高、居中可拖动、PixelFormat.TRANSLUCENT），
  页面区直接承载引擎 active WebView —— 用户全程**看见 Agent 正在开的真实页面**；
- **收起不是断开**：用户「✕」收起后窗口转 GHOST 无头宿主（视图 INVISIBLE +
  `FLAG_NOT_TOUCHABLE`，窗口保活）——WebView 留在视图树内持续被测量布局，
  自动化的 CSS→物理坐标换算与命中测试不因脱离窗口而失真；
- **三态生命周期**（`LiveViewPolicy`，纯 JVM 可测）：VISIBLE 显示 / GHOST
  隐形宿主 / GONE 整窗移除（会话结束自动复位「用户收起」标记，下次自动弹出恢复）；
- **纯 View 实现零 Compose**：刻意避开浮窗内 Compose 的
  ViewTreeLifecycleOwner 系列窗口层级坑（见下条 P0 的同类根源），控制条崩溃面最小；
- 宿主接法：`LiveViewOverlayFactory.get(context, engine)` 注册进宿主 DI
  （与 `BrowserOverlayFactory` 同款单例纪律），悬浮球点击 `toggle()`、
  浏览器功能页「查看实时页面」调 `show()`。

### P0 崩溃修复：`BrowserChrome` 浮窗内 `BackHandler` 主线程崩溃（宿主 #375）

- activity-compose 的 `BackHandler` 在 `enabled=false` 时也会先
  `checkNotNull(LocalOnBackPressedDispatcherOwner)` —— 悬浮窗 ComposeView 挂在
  WindowManager（无 Activity 返回栈），该 Local 恒为 null → 组合即抛
  `IllegalStateException` 杀主线程（crash-1792191618578：浮窗显示后约 20s
  崩溃，用户观感「点击悬浮球闪退 / agent 用浏览器后卡死重启」）；
- 修复：无 dispatcher 时整个 `BackHandler` 不进入组合树（条件组合，而非
  `enabled=false` 短路 —— 旧实现的 `hasBackDispatcher` 守卫防不住这层
  checkNotNull）。

### 引擎：活动标签广播（`ActiveTabListener`，additive API）

- 标签新建/切换/关闭回落/崩溃重建/长会话重建都会换新活动 WebView，但这类
  变化**不产生状态机迁移** —— 只订 `BrowserUiCallback` 的可视层拿不到新实例；
- 新增 `BrowserEngine.addActiveTabListener / removeActiveTabListener`：
  把「当前应展示哪个 WebView」的变化单独广播（主线程），实时页面悬浮窗据此
  换挂，Agent 驾驶中切标签实时画面跟随切换。

版本：三模块 1.3.0 → **1.4.0**（additive API + 一个 P0 修复；`LiveViewOverlay`
/ `LiveViewOverlayFactory` / `LiveViewPolicy` / `ActiveTabListener` 均为新增，
无破坏性变更）。

## v1.3.0 变更（错误语义 / 快照保真度 / 等待谓词 / 可观测性）

### 错误语义拆分与重试契约兑现（:browser-core + 引擎接线）

- 新增 `PermanentActionException`：操作本身不可能成功的语义错误（对非输入元素
  `inputText` / `selectOption` 选项不匹配 / 页面侧回传非预期形态）。`withRetry`
  对其立即上抛——**不烧退避、不计熔断**（旧实现一律抛 `ElementNotFoundException`
  白耗 3 次退避并推动全局熔断，一个坏元素锁死整段会话）；
- `CircuitBreaker` 的 `acquire/onSuccess/onFailure` 全部 `@Synchronized`：旧实现
  `consecutiveFailures++` 非原子，跨线程丢计数导致熔断迟开；
- 快照超时转译为 `java.util.concurrent.TimeoutException`（可重试集合成员）——
  旧实现直接把 `TimeoutCancellationException` 上抛，KDoc 承诺的「快照超时视为
  可重试，熔断保护」从未生效。转译前检查外层协程存活，不破坏结构化取消。

### 快照保真度（:browser-core + 引擎接线）

- **ref 同键去重**：同一 `semanticKey`（列表页同批商品卡/表格同列删除按钮）算出
  同一 hash，撞键后定位永远命中第一个。第 2+ 次出现追加 `#n` 后缀；
- **title 修复**：引擎 WebViewClient 此前从未覆写 `onReceivedTitle`，
  `PageSnapshot.title` 恒为空串；`onPageStarted` 同步复位 `pageFinished`
  （修 goBack/goForward 后的「假就绪」）；
- **快照融合**：`PageSnapshot` 追加 `timestampMs`（生成时刻）与 `dialogNotice`
  （JS 弹窗/SSL 拦截/权限/下载通知，**消费即清**——写入本次快照后随即清空，
  模型不重复收到同一条）；
- **a11y 降级源 total 口径修正**：旧 `total = querySelectorAll('*').length`
  （纯布局节点全计入，`truncated` 几乎恒真），改为循环内语义节点计数。

### 等待谓词扩展（waitForCondition 3 → 7 模式）

| 模式 | 语义 |
|---|---|
| `element_gone` / `text_gone` | 选择器/文本从页面**消失**（弹窗、加载态收起判定） |
| `network_idle` | 在途请求数归零（`NETWORK_MONITOR_JS` 新增 `__apexNetPending` 计数，**连续 2 次为 0** 才算 idle） |
| `selector_count` | 命中数满足谓词：`"<选择器>>=10"` / `"<=5"` / `"==3"` / `"<选择器> 10"`（纯数字缺省 `>=`，要求与选择器间有空白，避免与 `.item2` 歧义） |
| `url`（增强） | 改注入 `CURRENT_URL_JS` 求值：SPA pushState 即时反映到 `location.href`，native `webView.url` 只在导航提交时更新 |

### 新增引擎 API（Agent 直驱路径补齐）

| API | 说明 |
|---|---|
| `reload(timeoutMs)` | 重载 + 等待就绪（此前宿主只能裸调 `webView.reload()`） |
| `stopLoading()` | 停止当前页加载（Agent 逃生舱） |
| `findInPage(query)` | `findAllAsync` + FindListener 桥接，返回命中数 |
| `engine.tracer` | 引擎内建 `BrowserTracer`（见下） |

### 可观测性接线（`BrowserTracer` 从零到一）

- 引擎持有公开 `tracer` 属性；关键动作（navigate/click/input/select/scroll/
  snapshot）经 `tracedAction` 包装记录 成败/参数/耗时/会话状态/URL；
- 系统事件入 trace：渲染进程崩溃 / SSL 拦截 / confirm 自动确认 / 下载入队；
- `BrowserTracer.Entry` 追加 `error` 尾参（失败边界的错误摘要）；新增
  `events: SharedFlow<Entry>` 实时事件流（replay=0，DROP_OLDEST——慢消费者丢旧值
  不阻塞记录路径）；
- 下载管理：DownloadManager 请求转发会话 `Cookie`（登录态后的导出/附件下载
  不再 401）；网络监控随 `onPageFinished` 与 `STEALTH_JS` 合并注入（导航重置
  JS 上下文后不再有监控盲区）。

### 注入边界收敛（安全债清偿）

`locateElements` 的内联 JS（手写转义只覆盖引号，换行 / U+2028 / 控制字符裸拼）
迁移为 `BrowserScript.locateJs`——参数一律 `JsLiteral.string`，与全库唯一编码
入口统一。至此 `:browser-engine` 内不再有任何手拼注入 JS。

版本：三模块 1.2.0 → **1.3.0**（additive API；`PageSnapshot` / `BrowserTracer.Entry`
构造器为带默认值的尾参扩展，宿主源码兼容）。

## 已知取舍（v1.0.0）

- 逻辑层文案硬编码中文（`BrowserChromeController` 5 条 snackbar 与
  `SuggestionsBuilder` 联想副标题，非 Compose 上下文、`stringResource` 不可达）
  —— **v1.2.0 已消解**：连同 `ApexChromeWiring` 的 3 条 JS 弹窗审计标签共
  13 条收敛为 `ChromeStrings` 注入接口，默认 `ZhChromeStrings` 与旧硬编码
  逐字一致，可换 `EnglishChromeStrings` 或自译实现（见「v1.2.0 变更」）。
  chrome UI 层文案始终资源化（`res/values` + `values-en`，`browser_` 前缀强制）。
- 库 manifest 不声明 `SYSTEM_ALERT_WINDOW` —— 由宿主自行声明（并非所有消费者都
  需要浮窗，F5）。
- **v1.3.0**：引擎 `findInPage` 与 chrome 层页内查找共用 WebView 的**单槽**
  FindListener——Agent 侧调用会替换 chrome `ChainFindListener` 的注册，chrome
  的计数回填依赖其下次接线时重设监听器（Agent 侧计数优先的取舍）。
- **v1.3.0**：`locateElements` 迁移到 `BrowserScript.locateJs` 后，
  `check_js_injection.py` GATE 1 的扫描范围仍为 `BrowserScript.kt`（引擎侧注入
  JS 已全部收敛到该文件，规则本身无需扩面）。

## 来源追溯

- 抽取自 `Android-Guru-Agent@139ffee`（main, 2026-10）：
  - `core/tool-registry/.../builtin/browser/` → `:browser-core`
  - `app/.../browser/{BrowserEngine,BrowserTracer,OverlayLifecycleOwner,RetryPolicy}.kt` → `:browser-core` / `:browser-engine`
  - `app/.../browser/chrome/**` + `BrowserOverlay.kt` → `:browser-chrome`
- 两仓库 commit 互相引用宿主侧删除提交与库侧首个提交。

## License

MIT
