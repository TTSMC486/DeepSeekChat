# DeepSeek Chat —— Android 对话 App

一个用 **DeepSeek 大模型** 驱动的 AI 对话软件。代码分两层：

```
DeepSeekChat/
├── web/index.html                 ← 全部界面 + 逻辑（单文件，零依赖，可直接用浏览器打开）
├── android/                       ← Android Studio 工程（WebView 原生壳 + OkHttp 直连）
│   ├── settings.gradle.kts / build.gradle.kts / gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/index.html               ← 由 web/index.html 同步而来
│           ├── java/com/ttsmc/deepseekchat/MainActivity.kt
│           └── res/ (图标 / 主题 / 字符串 / 备份规则)
└── sync-web.sh                    ← 改完网页后跑一次，同步到 assets
```

---

## 一、两种用法

### A. 立刻体验（不用编译）
把 `web/index.html` 传到手机，用**浏览器**打开即可。
- 首次打开会弹出设置，填入 DeepSeek API Key（`sk-...`）保存。
- 在浏览器里可以「添加到主屏幕」，当成 PWA 用。
- ⚠️ 部分浏览器会因为 **CORS 跨域策略** 拦截对 `api.deepseek.com` 的请求。
  如果「测试连接」失败并提示 CORS，请改用下面的 APK 方式。

### B. 编译成 APK（推荐，无 CORS 问题）
APK 版把网络请求放在 **Kotlin/OkHttp 原生层**，网页只负责画界面，因此：
- 不受浏览器 CORS 限制；
- API Key 保存在原生层，不会被网页脚本外泄。

---

## 二、编译 APK 的步骤

### 1. 准备环境（一次性）
- 安装 **Android Studio**（Hedgehog 或更新，自带 JDK 17 + Android SDK）
  https://developer.android.com/studio
- 或者只装命令行工具：JDK 17 + Android SDK（`sdkmanager "platforms;android-34" "build-tools;34.0.0"`）

### 2. 打开工程
Android Studio → **Open** → 选择本目录下的 `DeepSeekChat/android`（注意是 android 子目录，不是外层）。
首次打开会自动下载 Gradle 与依赖，需要联网，约 3–10 分钟。

> 工程没有附带 `gradle/wrapper/gradle-wrapper.jar`（二进制文件）。
> Android Studio 打开时会提示缺少 wrapper，选择 **"Use Gradle from: gradle-wrapper.properties file"** 或让它自动生成即可；
> 若用命令行，先在 `android/` 下执行 `gradle wrapper --gradle-version 8.7`。

### 3. 同步网页（改过 web/index.html 才需要）
```bash
bash sync-web.sh
```

### 4. 出包
- 调试包：`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 发布包：Android Studio → Build → Generate Signed App Bundle / APK，自建 keystore 后选 `release`。
- 命令行出未签名 release：`./gradlew :app:assembleRelease`

### 5. 安装
把 APK 传到手机点击安装（需允许「安装未知来源应用」）。

---

## 三、功能清单

| 功能 | 说明 |
|---|---|
| 多轮对话 | 完整上下文，自动带上历史消息 |
| 流式输出 | SSE 打字机效果，可随时中断 |
| 双模型切换 | `deepseek-chat`（V3，快）/ `deepseek-reasoner`（R1，深度推理） |
| 思考过程 | reasoner 的 `reasoning_content` 折叠展示 |
| Markdown 渲染 | 标题 / 列表 / 引用 / 表格 / 链接 / 粗斜体，内置零依赖实现 |
| 代码块 | 语言标签 + 一键复制 |
| 会话管理 | 多会话列表、新建、删除、自动命名、localStorage 持久化 |
| 参数可调 | temperature、max_tokens、system prompt、流式开关 |
| 深浅主题 | 一键切换 |
| 导入导出 | 全量会话 + 设置导出为 JSON，可再导入 |
| 安全 | 请求走原生层；HTML 全量转义，`javascript:` 链接被拦截 |

---

## 四、技术要点

### 为什么不用网页直接请求？
`file:///android_asset/index.html` 是本地源，直接 `fetch("https://api.deepseek.com/...")`
属于跨源请求，浏览器/WebView 会先发 `OPTIONS` 预检，DeepSeek 不返回 CORS 头 → 被拦截。
所以原生壳里由 `MainActivity` 用 OkHttp 发请求。

### JS ↔ Kotlin 协议
```
JS   -> Kotlin : DeepSeekBridge.startStream(reqId, baseUrl, apiKey, payloadJson)
                 DeepSeekBridge.abort(reqId)
                 DeepSeekBridge.openUrl(url)
Kotlin -> JS   : window.__bridge.onOpen(reqId)
                 window.__bridge.onReason(reqId, text)     // 思考过程
                 window.__bridge.onDelta(reqId, text)      // 正文增量
                 window.__bridge.onDone(reqId)
                 window.__bridge.onError(reqId, message)
```
- `web/index.html` 里的 `Bridge.available()` 会自动探测是否在 APK 中运行，
  有原生桥走原生，没有则退回浏览器 `fetch`。所以**同一份 HTML 两种环境都能跑**。
- token 增量在 Kotlin 侧按 **45ms 合批**后再 `evaluateJavascript` 回推，避免每个 token 一次跨语言调用。

### 关键实现位置
- `web/index.html` → `renderMarkdown()`：零依赖 Markdown 渲染器（含未闭合围栏代码块的流式容错）
- `web/index.html` → `fetchStream()`：SSE 逐行解析，识别 `data:` / `[DONE]` / `finish_reason`
- `MainActivity.kt` → `Bridge.startStream()`：OkHttp 异步流式请求入口
- `MainActivity.kt` → `readSse()`：`data:` 行解析，取 `delta.content` 与 `delta.reasoning_content`
- `MainActivity.kt` → `Streamer`：45ms 合批回推
- `proguard-rules.pro`：必须保留 `@JavascriptInterface` 方法，否则 release 混淆后 JS 桥失效

### 关键参数
- `baseUrl`: `https://api.deepseek.com`（代码会自动补 `/chat/completions`）
- `deepseek-reasoner` **不接受** `temperature`，代码已自动省略该字段
- OkHttp `readTimeout = 0`（SSE 长连接不能设读超时）
- `minSdk 24` / `targetSdk 34` / `compileSdk 34` / JDK 17

---

## 五、常见问题

**Q: 提示「API 错误 401」**
Key 填错或未生效。到 platform.deepseek.com → API keys 重新生成，注意 `sk-` 前缀和空格。

**Q: 提示「API 错误 402 / Insufficient Balance」**
DeepSeek 账户余额不足，需要充值。

**Q: release 包安装后白屏 / 按钮没反应**
R8 把 JS 桥方法删了。确认 `proguard-rules.pro` 里的
`-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` 生效。

**Q: 键盘弹出遮挡输入框**
已在 manifest 设 `windowSoftInputMode="adjustResize"`；若厂商 ROM 异常，可在设置里改用悬浮输入。

**Q: 想换成本地/中转接口**
在 App 设置里把 Base URL 改成你的地址即可（需兼容 OpenAI 的 `/chat/completions` 协议）。
注意 HTTP 明文地址会被系统拦截，App 已设 `usesCleartextTraffic="false"`，请用 HTTPS。

---

## 六、可继续扩展的方向
- 语音输入（Android `SpeechRecognizer`，再加一个 `@JavascriptInterface`）
- 图片/文件上传（需换成 `deepseek-vl` 或多模态接口）
- 联网搜索（接第三方搜索 API，做 RAG）
- 会话云同步（后端 + 账号）
- 字号调节、消息长按菜单、Prompt 模板库
