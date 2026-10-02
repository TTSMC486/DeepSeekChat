# 我的露缇娜 · AI 虚拟人物聊天 App

一个纯离线的 Android 聊天客户端，调用 **DeepSeek API**，把自己设定成一个有性格、有记忆、
说话像真人的虚拟伙伴。App 内所有界面都在本地 `assets` 里，不发任何第三方请求（只直连 DeepSeek）。

> 名字、性格、头像、背景图、她怎么称呼你 —— 全部可以在 App 里改。

---

## 一、两种用法

| 方式 | 怎么做 | 限制 |
| --- | --- | --- |
| **A. 直接看界面** | 用 Chrome 打开 `web/index.html` | 浏览器是 `file://` 源，调用 DeepSeek 会被 **CORS 拦掉**，只能看界面 |
| **B. 编译成 APK**（推荐） | 见下面「三、编译」 | 网络请求走原生 OkHttp，不受 CORS 限制，API Key 不经过网页层 |

---

## 二、目录结构

```
DeepSeekChat/
├─ web/index.html                  ← 全部界面与逻辑（单文件，零依赖）
├─ android/                        ← Android Studio 工程
│  ├─ settings.gradle.kts / build.gradle.kts / gradle.properties
│  └─ app/
│     ├─ build.gradle.kts
│     ├─ proguard-rules.pro
│     └─ src/main/
│        ├─ AndroidManifest.xml
│        ├─ assets/index.html      ← 由 sync-web.sh 从 web/ 复制
│        ├─ java/com/ttsmc/deepseekchat/MainActivity.kt
│        └─ res/  (图标 / 主题 / 颜色 / 备份规则)
├─ .github/workflows/build-apk.yml ← 云端自动编译
└─ sync-web.sh                     ← 改完 web/index.html 后执行
```

---

## 三、编译

### 用 Android Studio（需要电脑）

1. 装 Android Studio（自带 JDK 17）。
2. **Open** 选 `DeepSeekChat/android` 这个**内层**目录（不是外层）。
3. 首次打开会提示下载 Gradle 8.7 与依赖，等它跑完。
4. 菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
5. 产物：`android/app/build/outputs/apk/debug/app-debug.apk`

> 仓库里**没有带 gradle wrapper 的 jar**（生成环境无网络）。Android Studio 会自动补齐；
> 命令行环境可以执行 `gradle wrapper --gradle-version 8.7` 自己生成。

### 用 GitHub Actions（不需要电脑）

推送到 `main` 分支即自动编译，成功后到仓库 **Actions → 最新一次运行 → 页面底部 Artifacts**
下载 `DeepSeekChat-apk`，解压得到 `app-debug.apk`。

工作流还会把编译产物和日志推到一个临时分支 `ci-log`，方便无浏览器环境下取回。

---

## 四、功能

| 需求 | 实现 |
| --- | --- |
| 虚拟人物聊天 | 人设驱动的 system prompt；界面按"她"来组织（头像、名称、空状态问候语） |
| 性格自定义 | 设置 → **角色**：她的名字 / 她怎么称呼你 / **性格设定**（自由文本）/ 6 套一键预设 / 补充设定 |
| 名称「我的露缇娜」 | App 名、顶栏标题、抽屉标题全部跟随设置里的名字 |
| 聊天背景图 | 设置 → **外观**：`从相册选`（SAF）或 `读 TTSMC/1`（扫描 `/sdcard/TTSMC/1/`）；可调暗化 / 模糊 / 气泡不透明度 |
| 记忆功能 | localStorage `dschat.memory.v1`；每轮对话后台自动整理出"关于你的事实"写进长期记忆，下一轮拼进 system prompt；可在设置 → **记忆**里增删改 |
| 说话像真人 | 11 条"说话方式"硬规则（禁止 Markdown、限制句数、禁止 AI 腔…）+ `humanize()` 二次清洗模型仍输出的 Markdown |

其它：多会话、流式输出打字机效果、深度推理（reasoner）思考过程折叠、一键重新生成、
导出/导入全部数据、深浅主题、代码块高亮与复制、刘海屏安全区适配。

---

## 五、技术要点

- **为什么不在网页里直接 fetch**：`file://` 源调 `https://api.deepseek.com` 是跨源请求，
  预检必被 CORS 拦。因此**网络层放在 Kotlin/OkHttp**，JS 只通过 `@JavascriptInterface` 拿增量。
- **JS ↔ Kotlin 协议**

  JS → Kotlin：
  ```
  startStream(reqId, baseUrl, apiKey, payloadJson)   // SSE 流式对话
  abort(reqId)
  openUrl(url)
  requestImagePermission()                            // 申请相册/存储权限
  pickImage(reqId)                                    // 打开系统相册
  listImages(dir) -> JSON 数组字符串（同步返回）       // 扫描目录里的图片
  loadImage(reqId, absolutePath)                      // 解码+压缩后回推
  ```
  Kotlin → JS：
  ```
  window.__bridge.onOpen / onReason / onDelta / onDone / onError (reqId[, text])
  window.__bridge.onImage(reqId, dataUrlOrNull)
  ```
- **45 ms 合批**：网络线程把 token 追加进 `StringBuilder`，主线程每 45 ms 取一次增量，
  用游标只发新增部分，避免每个 token 都 `evaluateJavascript`。
- **图片管线**：`BitmapFactory` 两段式采样（先 `inJustDecodeBounds` 读尺寸，再 `inSampleSize`）
  → `ExifInterface` 摆正 → 最长边缩到 1440 → JPEG q82 → base64 dataURL 回给 JS 存 localStorage。
  手机上原图动辄 5–12 MB，压完通常几十 KB。
- **`deepseek-reasoner` 不接受 `temperature`**：`buildRequest()` 里判了模型名才带采样参数。
- **`readTimeout(0)`**：SSE 是长连接，设了读超时会在思考阶段被掐断。
- **proguard**：release 开了混淆，规则里 `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }`
  保证桥方法不被裁掉。
- 版本：`compileSdk 34 / minSdk 24 / targetSdk 34`，Java/Kotlin 17，AGP 8.5.2，Gradle 8.7。
  依赖：appcompat 1.7.0、core-ktx 1.13.1、okhttp 4.12.0、exifinterface 1.3.7。

---

## 六、常见问题

**Q：装好后打开是空白？**
release 版开了混淆，JavascriptInterface 被裁掉会白屏；用仓库里的 `proguard-rules.pro`。debug 版不受影响。

**Q：发送报 401 / 402？**
设置 → **接口** 里 API Key 输入框下方现在会实时显示 `当前：sk-****4878 · 35 字符`。

- **长度不是 35 左右** → 你没复制全，回 platform.deepseek.com 重新复制整串。
- **长度对但报 401** → 这把 Key 已被删除或停用（DeepSeek 控制台里删过、或账号风控）。
  重新创建一个新的即可。注意 Key 只在创建时显示一次，关掉弹窗就再也看不到完整串了。
- **402** → 账户余额不足，去 platform.deepseek.com 充值。

**Q：提示"读 TTSMC/1 里没有图片"？**
先确认 `/sdcard/TTSMC/1/` 里确实有图；再确认给了"照片和视频"权限。
Android 13+ 要 `READ_MEDIA_IMAGES`，12 及以下要 `READ_EXTERNAL_STORAGE`。

**Q：记忆会不会乱？**
自动整理只抽取"稳定事实"（名字、工作、宠物、喜好、约定…），单条超过 60 字或重复的会被丢弃，
总量上限 80 条。不满意可以直接在设置 → 记忆里改或删。

**Q：换成中转接口？**
改 Base URL 即可，但必须是 **HTTPS**（Manifest 里 `usesCleartextTraffic="false"`）。
