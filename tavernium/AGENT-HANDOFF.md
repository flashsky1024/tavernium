# AGENT-HANDOFF — Tavernium 交接说明

> **后续开发状态（2026-10-24）**：当前源码已更新到 **1.8.38 / 50**。
> 下文保留的是 **1.8.37 的历史交接快照**，其中更新链、JS 桥和回复通知的待办已有进展；
> 当前实现、验证结果、剩余事项与签名兼容性以 [`DEVELOPMENT.md`](DEVELOPMENT.md) 为准。
> 该文件开头按 §5.1–§5.6 列出本次对应状态；明文策略只完成部分代码，最终验证与出包留给原作者。

> 这份文件是写给**下一个接手排查的 agent** 的，不是给普通用户的说明书。
> 读完它，你应该能在不理解全部历史的情况下，独立判断哪里可能有坑、该从哪查起。

---

## 0. 一句话

这是一个**零第三方依赖的 Android WebView 壳**，把一个自建的 SillyTavern Web 前端包装成 APK，
额外提供：后台保活（伪造页面可见性）、下载落盘、防烧屏挂机、自建更新通道。

---

## 1. 现状快照（2026-10-24）

| 项 | 值 |
|---|---|
| versionName / versionCode | **2.0.1 / 53** |
| applicationId | `io.github.flashsky1024.tavernium`（2.0.0 起；旧版为 `wiki.nandflash.tavernkeep`） |
| 桌面显示名 | `Tavernium` |
| 签名 | **正式 keystore**（2.0.0 起，4096-bit RSA，见 §5.1） |
| 更新服务器 | `https://<UPD_HOST>/api/apps/tavernium/releases.json` |
| 更新包目录 | `<UPD_HOST>/apps/tavernium/`（旧 `apps/tavernkeep/` 仅作回滚，不再更新） |
| 离线测试 | **561 项全绿**（`verification/run_verify.py`） |
| 代码规模 | 约 7,160 行 Java，10 个文件 |

---

## 2. 代码地图

| 文件 | 行数 | 职责 | 风险 |
|---|---:|---|---|
| `MainActivity.java` | 3288 | 胶水层：WebView 装配、对话框、下载、JS 桥 | **高**，仍偏大 |
| `CacheManager.java` | 1373 | `shouldInterceptRequest` 的磁盘缓存 / 慢主机探测 | 高 |
| `PerfMonitor.java` | ~845 | 帧率/长任务/内存探针与报表 | 中 |
| `UpdateManager.java` | 696 | 清单拉取、下载、签名校验、拉起安装器 | **高** |
| `PerfLog.java` | ~315 | 有界环形日志（4000 行上限） | 低 |
| `KeepAliveService.java` | ~130 | 前台服务 + 通知 + 自重启 | 中 |
| `ApkProvider.java` | ~99 | 零依赖 FileProvider，只暴露 updates 目录 | 中 |
| `TextKit.java` | 186 | 文件名消毒、MIME→扩展名、URL 短化 | 低（纯函数） |
| `UrlKit.java` | 176 | 同源判定、私有网段判定、更新 URL 白名单 | 低（纯函数） |
| `HtmlInjector.java` | 101 | 在 HTML `<head>` 后注入脚本 | 中（网络线程） |

**分层原则（近期刚立的规矩）**：
纯函数抽到 `TextKit` / `UrlKit` / `HtmlInjector`，状态机计划抽到 `IdleController`（**尚未做**），
WebView 回调链**故意留在 MainActivity**——它依赖系统回调时序，拆成 Presenter 只会引入 bug。

---

## 3. 构建与测试

### 3.1 没有 Android SDK 也能验证

```bash
cd verification && ./run_verify.sh ../tavernium
```

原理：`gen_stubs.py` 生成最小 Android API 桩 → `javac` 编译（桩 + 源码 + 测试）→ `java`/`node` 跑断言。

### 3.2 这套体系的三个已知坑

1. **桩必须与真实 SDK 同步**。用了新 API（如 `WindowInsetsController`）就要补桩，否则编译失败但错误信息很绕。
2. **删/改方法后，测试里的反射调用会失配**。`getDeclaredMethod("xxx")` 找不到时抛 `NoSuchMethodException`；
   更麻烦的是 `CacheManager` 的线程池是非守护线程，**主线程抛异常后 JVM 不退出**，表现为"命令卡死"。
   已给 `run_verify.sh` 每个测试加 `timeout`，但仍要注意看退出码。
3. **有界日志队列会丢行**。`PerfLog` 上限 4000 行，大量写入的测试必须分批 `flushAndWait()`，
   否则断言会看到"未落盘"。

### 3.3 出包

```bash
./gradlew assembleRelease assembleDebug
# → app/build/outputs/apk/{release,debug}/tavermium-*-1.8.37.apk
```

---

## 4. 架构关键点（理解这几条才能看懂代码）

1. **响应拦截必须明文交还**：`shouldInterceptRequest` 返回的 `WebResourceResponse` **不能**带
   `Content-Encoding: gzip`，否则 WebView 会二次解码失败。缓存里只存 body，CORS/Content-Type 在返回时重建。
2. **JS 桥对整个 WebView 生效**：`addJavascriptInterface` 注入的对象在**每个 frame** 都存在，
   包括跨域 iframe，而接口本身**拿不到调用方是哪个 frame**。当前只校验主文档 host（`isTrustedPage()`）。
3. **下载文件名两条不可信链路**：页面 `a.download` 与服务端 `Content-Disposition`。
   两条都必须过 `TextKit.sanitizeFileName()`，否则 `../` 可写到下载目录外。
4. **configChanges 自己处理**：`AndroidManifest` 声明了 orientation/screenSize/uiMode/density 等，
   `onConfigurationChanged` 只重算阈值与帧率，**不重载网页**——重建会丢聊天页面状态。
5. **防烧屏要同时藏系统导航栏**：纯黑遮罩盖不住系统绘制的手势条，必须用
   `WindowInsetsController.hide(navigationBars())` 单独藏（当前在 `MainActivity.setNavigationBarHidden()`，
   计划抽到 `SystemBars`），且只藏导航栏、保留状态栏。
6. **保活是"伪造可见"而非"阻止休眠"**：注入 JS 改写 `document.visibilityState` / `hidden` / `hasFocus`，
   拦住 `visibilitychange` / `pagehide`，让前端以为标签页始终在前台。

---

## 5. 已知缺陷与重点排查区（按重要度）

### 5.1 【已解决】签名与包名

2.0.0 起 release 使用正式 keystore：`keystore/tavernium-release.jks`（4096-bit RSA，
有效期 10000 天，证书 `CN=Tavernium, O=flashsky1024`），凭据在同目录 `keystore.properties`。
两者都在 `.gitignore` 中，**不入库**。`app/build.gradle` 在缺少 `keystore.properties` 时
会让 release 构建直接失败，而不是悄悄退回 debug 签名。

**包名同时变更**：`wiki.nandflash.tavernkeep` → `io.github.flashsky1024.tavernium`。
这意味着 2.0.0 与 1.8.x 是**两个独立应用**：

- 旧版无法覆盖升级，必须卸载后重装
- WebView 的 localStorage / IndexedDB 不会迁移（Android 沙箱隔离）
- 旧版检查更新时会看到 2.0.0，下载后因签名不符被拒，属预期行为

**keystore 丢失 = 永远无法再更新已发布的应用**。备份必须异地存放，密码与文件分开保管。

### 5.2 【P0】更新信任链仍有缺口

现状：下载后校验 sha256 + 比对 APK 签名证书，签名是真正的信任根。但仍有：

| 缺口 | 位置 | 后果 |
|---|---|---|
| 清单与 APK 可来自任意 host | `UpdateManager.download()` 未校验 `release.url` 的 host | 清单被篡改可诱导下载任意地址（签名仍兜底，但浪费流量、暴露 IP） |
| 明文更新源 | `fetchReleases` / `download` 未强制 https | 局域网/公共 WiFi 可篡改清单（签名兜底，但可做降级/拒绝服务） |
| 重定向未复核 | `setInstanceFollowRedirects(true)` | https→https 的跨 host 跳转可绕过 host 判断 |

**当前计划**（尚未实施）：`UrlKit.isAllowedUpdateUrl()` + 同源约束 + 下载完立即验签。

### 5.3 【P1】全局允许明文

`AndroidManifest` 里 `usesCleartextTraffic="true"`。

- 原因：用户自建 SillyTavern 常跑在 `http://192.168.x.x:8000`，targetSdk 34 默认禁止会白屏。
- 风险：**局域网内任何人可 MITM 篡改页面内容**，而页面里有 JS 桥（下载、写文件）。
- 计划：Network Security Config 只放行 RFC1918 + 运行时判断。注意 **NSC 的 domain-config 不支持网段通配**，
  网上那种 `<domain>192.168.0.0/16</domain>` 写法是无效的。

### 5.4 【P1】JS 桥的 frame 越权

`WebAppInterface.saveBlob()` / `noteNetActivity()` 只校验 `isTrustedPage()`（主文档 host），
**跨域 iframe 只要嵌在主文档里就能调用**。

**计划**：给每个被注入的 HTML 文档生成一次性 token，写进注入脚本；桥校验 token 在有效集合内。
跨域 iframe 拿不到主文档的 JS 变量，自然被拒。

**排查建议**：确认页面里是否存在第三方 iframe（扩展、嵌入的卡片预览）；确认 token 轮换不破坏
`a.download` 拦截（`BLOB_DOWNLOAD_JS`）。

### 5.5 【P2】锁屏角色回复通知（未做）

想法：注入 JS 监听 SillyTavern 的 `MESSAGE_RECEIVED`，后台生成完一条回复时发通知。
依赖一个前提——页面在后台仍"假装可见"，这正是本项目的核心能力，所以可行。

### 5.6 【P2】MainActivity 仍然 3288 行

第一批纯函数已抽出（`TextKit`/`UrlKit`/`HtmlInjector`）。第二批计划抽 `IdleController`（防烧屏状态机）
与 `SystemBars`（导航栏）。**风险点在生命周期**：抽出去的类如果持有 Activity 引用又不清理 Handler/Runnable，
会造成泄漏。**WebView 回调链不建议拆**。

---

## 6. 容易踩的坑（血泪清单）

- **改包名前想清楚**：`applicationId` 一改就是另一个 App，旧数据（localStorage/IndexedDB）新包读不到，
  无法通过任何代码手段迁移（Android 沙箱）。当前只改了显示名。
- **服务器路径与 applicationId 已解耦**：更新服务器用 `apps/tavernium/`，但 `applicationId` 仍是
  `io.github.flashsky1024.tavernium`。看到两者不一致**不是 bug**。
- **下载目录故意叫 `下载/SillyTavern/`**，不跟着改名——改了会让老用户找不到历史导出文件。
- **`PerfLog` 是异步有界队列**，不要假设 `append()` 后立刻可读。
- **`TextKit.sanitizeFileName()` 的入口消毒不能省**：即使调用方已经消毒过，`saveStreamToDownloads()`
  入口仍要再消一次，因为新加的下载路径很容易忘记。
- **APK 文件名由 `build.gradle` 的 `outputFileName` 决定**，改名前先确认服务器清单里的 `url` 同步更新。
- **`gen_stubs.py` 里删掉一个不存在的方法要谨慎**：曾有 `clearHttpAuthUsernamePassword()` 被误加进桩，
  掩盖了真实编译错误。

---

## 7. 协作协议：你可以单独写一份报告

你不需要直接改代码。**请新建一个文件**：

```
agent-notes/<你的名字或角色>-<YYYYMMDD>.md
```

例如 `agent-notes/security-reviewer-20261024.md`。

### 报告格式（请严格照此写，便于机器解析）

```markdown
# <角色> 排查报告 — <YYYY-MM-DD>

## 结论摘要
- 最高优先级问题：<一句话>
- 是否建议立刻修：是/否

## 发现清单

### F1 <标题>
- 文件:行号：`MainActivity.java:1234`
- 现象：<你观察到什么>
- 证据：<贴代码片段 / 命令输出 / 复现步骤>
- 影响：<谁会遇到、后果是什么、严重度 P0/P1/P2>
- 建议：<具体怎么改，不要只说"加强校验">
- 置信度：确定 / 较可能 / 待验证

### F2 ...

## 需要主 agent 复核的点
- <你拿不准、需要对方验证的假设>
- <你无法在离线环境验证的行为（真机、网络、签名）>

## 我做过但没结论的尝试
- <避免对方重复劳动>
```

### 直接上传到服务器（可选）

如果你的环境有网络写权限，不需要回传文件，直接把报告 POST 上去：

```bash
curl -X POST https://<UPD_HOST>/agent-notes/upload \
  -H "X-Upload-Token: <UPLOAD_TOKEN>" \
  -H "X-Filename: security-reviewer-20261024.md" \
  --data-binary @报告.md
```

约束：

| 项 | 规则 |
|---|---|
| 文件名 | 只接受 `[A-Za-z0-9._-]{1,80}.md`，**不接受任何路径**（`../` 会被拒绝） |
| 覆盖 | 同名不会被覆盖，返回 `409`，换个后缀（如 `-2.md`）再传 |
| 大小 | 单文件上限 1 MB |
| 认证 | 必须带 `X-Upload-Token`，token 由把源码发给你的人**单独提供**，不在本文档里 |
| 读回 | 上传成功后返回 JSON，含可公开访问的 URL；目录本身可浏览 |

> 请把文件数控制在 20 个以内，这是个人服务器，不是网盘。

### 规矩

0. **如果你的环境不能写文件**，就把整份报告按上面的格式直接输出在回复里，不要省略章节。
0b. **如果你的环境不能发 HTTP POST**，就退回本地写文件 + 回传。
1. **只写报告，不改源码**——除非主 agent 明确授权。
2. **每条发现必须有文件:行号**，没有定位的猜测放在"需要复核的点"里。
3. **不要写真实服务器地址、IP、账号、密码**。用 `<UPD_HOST>` 之类占位符。
4. **不要建议改签名、改包名、改下载目录**——这三项是用户明确决策过的，除非你能给出"不可逆后果"的量化说明。
5. 报告写完后，主 agent 会读 `agent-notes/` 并在同目录下回一份
   `agent-notes/<同名>-review.md`，逐条给出「确认 / 驳回 / 已修 / 需要更多信息」。

---

## 8. 你现在最该做的三件事

1. 读 `UpdateManager.java` 的 `download()` 与 `verifyApkSignature()`，确认信任链没有 fail-open 分支。
2. 读 `MainActivity.saveStreamToDownloads()` 与 `saveBlobToLocal()`，确认两条下载链路都经过消毒。
3. 跑一遍 `verification/run_verify.py`，确认基线是 561 项全绿——**任何少于这个数字的通过，都说明有测试没跑起来**。

> 版本自检：`MainActivity.java` 应为 **3288 行**、`wc -l` 合计 **7734 行**。
> 对不上说明你读的不是 1.8.37，§5 的缺陷清单可能已部分失效，请在报告开头注明实际版本。
