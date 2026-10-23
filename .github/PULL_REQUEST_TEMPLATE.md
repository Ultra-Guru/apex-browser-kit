# apex-kit-browser

> 嵌入式 WebView 自动化引擎 + Compose chrome UI 的能力库。宿主：[Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)。

## 这个仓库的边界

这是**库**，不是应用。请把宿主相关的东西留在宿主仓库：Agent 工具协议（`browser_*`）、
Hilt 接线模块、霓虹球等视觉装饰、浮窗宿主（WindowManager）。本库只提供
**引擎 / chrome UI / DOM 快照**三层能力，且：

- `:browser-core` 是**纯 Kotlin JVM 模块，零 Android 依赖**（CI 有门禁强制）；
- 依赖方向单向 `chrome → engine → core`（core 不得 import engine/chrome）；
- 库内**不引入 DI 框架**，单例由 `object` 工厂提供，宿主在自己的 Module 里 provide。

## 改动前先看这里

| 你要改什么 | 先读 |
|---|---|
| 任何注入 WebView 的 JS | README「安全：JS 注入边界」+ `JsLiteral` |
| 快照 / 元素定位 / 页面分类 | `browser-core/src/main/kotlin/com/apex/browser/core/` |
| 新增外部字符串进 JS | 用 `JsLiteral.string(...)`，**不要**自己拼 |

## CI 门禁（都在 `.github/workflows/`）

| Workflow | 门禁 | 需要 Android SDK |
|---|---|---|
| `ci.yml` / build-test | 三模块编译 + 单测 + `publishToMavenLocal` 可发布性 | 是 |
| `ci.yml` / consumer-check | 检出宿主 Android-Guru-Agent，跑 `:app:compileDebugKotlin` + 单测（**库改动坏下游立刻看到**） | 是 |
| `guard-rails.yml` | core 纯度、JS 注入边界、资源前缀与多语言镜像、文件体积与反模式、门禁自测 | **否** |

`guard-rails` 是秒级反馈，专门拦「编译能过但不该合」的东西：core 里混进
`android.*` 依赖、注入点绕过 `JsLiteral`、资源漏 `browser_` 前缀撞宿主 `R`、God-file。

## 门禁自测（`scripts/tests/`）

每个门禁都有**反向测试**：往仓库副本里注入一个真实违规，断言门禁会失败。
只跑通过的门禁证明不了任何事 —— 这几个脚本保证门禁不会因为正则腐化而变成永远绿的空壳。

```bash
python3 scripts/tests/test_gates_negative.py
```

改动门禁后请连自测一起跑。

## 提交前自检

```bash
python3 scripts/check_core_purity.py      # core 零 Android + 单向依赖
python3 scripts/check_js_injection.py     # 注入点必须走 JsLiteral.string
python3 scripts/check_resources.py        # browser_ 前缀 + values/values-en 镜像
python3 scripts/check_code_quality.py     # 体积预算 + 反模式
./gradlew :browser-core:test              # 纯 JVM，无需 Android SDK
```

## License

MIT