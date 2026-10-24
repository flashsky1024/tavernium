# Tavernium

把你自己搭的 SillyTavern（酒馆）装进 Android 手机，当成一个真正的 App 来用。

Tavernium 不是又一个 AI 聊天应用，它更像一个转接头：酒馆还是你原来那个酒馆，服务器那边什么都不用改，Tavernium 只负责补上手机浏览器做不到的四件事。

你的角色卡和聊天记录都还在你自己的服务器上。应用没有任何第三方库，也没有广告和统计 SDK，安装包只有 227 KB。代码以 [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 开源，协议细节见文末。

## 和用手机浏览器有什么不同

| 你在做什么 | 用手机浏览器 | 用 Tavernium |
|---|---|---|
| 等回复时切出去回个消息 | 浏览器被系统冻结，回复断在半截 | 回复继续生成（个别机型需关闭电池优化） |
| 挂着等一条长回复 | 屏幕一直亮着，OLED 屏久了会烧出输入框和侧边栏的残影 | 切到纯黑挂机画面，不烧屏，屏幕本身几乎不耗电 |
| 导出聊天记录 | 文件存在浏览器自己的目录里，文件管理器找不到 | 直接存进手机的「下载」文件夹 |
| 锁屏等回复 | 只能靠页面里的提示音，锁屏后完全不知道 | 回复完成后发一条系统通知（需手动开启） |

## 它不做什么

- 不提供模型、API Key 或任何云服务，这些都在你的酒馆里配置
- 只保证酒馆本体能正常使用，不保证所有第三方扩展都兼容

## 安装

你需要：

- 一个已经跑起来的 SillyTavern（电脑、服务器、NAS 都行）
- 手机能用浏览器打开它，比如 `http://192.168.1.10:8000`
- 一台 Android 7.0 或更高版本的手机

步骤：

1. 到 [Releases](https://github.com/flashsky1024/tavernium/releases) 下载最新的安装包，文件名形如 `tavernium-release-2.0.0.apk`
2. 在手机上打开安装包。如果系统提示不允许安装未知来源的应用，按提示允许即可
3. 打开 Tavernium，填入你平时在浏览器里访问酒馆用的地址

### 服务器地址的规则

- 局域网地址（如 `192.168.x.x`、`10.x.x.x`）：`http://` 和 `https://` 都可以
- 公网地址（域名或公网 IP）：只能用 `https://`，填 `http://` 会被拒绝

这是刻意的设计。http 不加密，你的聊天内容和登录密码在传输途中都是明文，中间经过的路由器、运营商、公共 Wi-Fi 都能看到，甚至能篡改。局域网是你自己的网络，风险可控，所以放行；公网上不行。

## 使用说明

### 后台保活：切出去也不断

切到其他应用时，Tavernium 会让酒馆网页以为自己还在前台，回复照常生成。

保活期间通知栏会有一条常驻通知，它能降低应用被系统清理掉的概率，属于正常现象。应用只在和服务器通信时短暂阻止手机休眠，空闲时立刻放开，不会一直耗电。

如果切出去还是会断：有些手机的省电策略很激进，会直接冻结后台应用，这时保活会失效，需要到系统设置里把 Tavernium 加入电池优化白名单。各品牌叫法不一样，常见的有「电池优化」「后台耗电管理」「允许后台活动」。

### 防烧屏挂机：等回复时让屏幕黑下来

挂机时，应用画面会被一层纯黑遮住，底部导航栏也会一起隐藏。

为什么是纯黑：OLED 屏（目前多数中高端手机用的都是）显示黑色时，像素是直接熄灭的。不发光就不会老化，也就不会烧屏，屏幕本身也几乎不耗电。这比调低亮度更彻底。

挂机期间回复照常生成。打开了回复通知的话，回完会提醒你。

想回来时，按住屏幕往上滑，滑过大约五分之一屏幕高度就会解除，滑动时能看到进度。随手点一下不会解锁。这套手势参考了 iQOO 游戏空间。

触摸屏幕时会亮起 5 秒提示文字，位置每次随机错开，避免同一块屏幕被反复点亮。

### 回复通知：锁屏也知道角色回了

默认关闭，需要在应用菜单里手动打开。Android 13 及以上会请求通知权限，允许即可。

- 只在你看不到聊天界面时提醒：应用在后台、手机锁屏或挂机时
- 只在角色真正回完一条消息时提醒。开场白、你自己发的消息、系统消息、空消息、静默生成（不显示在聊天里的后台生成）、代写、重复触发、能识别出的生成失败和手动停止，都不会触发
- 通知里只有一句通用提示，不含角色名和回复内容，锁屏时旁边的人看不到你在聊什么

### 导出文件

在酒馆里导出的聊天记录等文件，会直接存进手机的「下载」文件夹，任何文件管理器都能找到。文件名里不能用的字符会被自动清理。

### 应用内更新

Tavernium 自带更新功能。更新包下载后会核对包名、签名证书、版本号和 SHA-256 校验值。签名证书对不上就拒绝安装，防止装上被人替换过的安装包。

第一次更新时，系统可能会询问是否允许 Tavernium 安装应用，允许即可。

### 遇到问题

内置诊断可以实时查看帧率、卡顿、内存占用和加载失败的资源。遇到卡顿、白屏或者有东西加载不出来，可以一键导出日志到「下载/SillyTavern/」，[提 Issue](https://github.com/flashsky1024/tavernium/issues) 时附上。日志只保留最近 4000 行，不会无限占用空间。

## 权限说明

| 权限 | 用途 |
|---|---|
| 网络 `INTERNET` | 连接你的酒馆服务器 |
| 防止休眠 `WAKE_LOCK` | 和服务器通信时短暂阻止休眠，空闲立即释放 |
| 前台服务 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 后台保活，通知栏的常驻通知来自这里 |
| 通知 `POST_NOTIFICATIONS` | 回复完成通知（Android 13 及以上需要） |
| 忽略电池优化 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求加入电池优化白名单，减少被系统冻结 |
| 安装应用 `REQUEST_INSTALL_PACKAGES` | 应用内更新时打开系统安装器 |
| 存储 `WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下） | 把文件存进「下载」文件夹 |

不涉及定位、通讯录、相机、麦克风这类权限。

## 已知限制

1. 为了能连局域网里的 http 酒馆，应用整体允许未加密的 http 连接（`usesCleartextTraffic=true`）。你填的服务器地址和页面里的普通请求都会被检查，但重定向（页面跳转）、Service Worker、WebSocket 这几条路径还没有逐一收紧。
2. 回复通知依赖页面在后台还能运行脚本。系统把应用深度冻结时，通知会延迟或丢失。
3. 只能连一个服务器、一个账号，不能在多个酒馆之间切换。
4. 只为个人自建场景优化，不做多用户、主题系统和插件市场。
5. targetSdk 停在 34，没有跟进到 35/36。Android 15 开始强制「边到边」显示（画面延伸到状态栏和导航栏下面），会影响现有的全屏和沉浸模式。这不影响在 Android 15、16 的手机上安装使用。

---

以下内容写给想了解实现或自己编译的人，只想用的话可以不看。

## 工作原理

Tavernium 是一个适配层，不是客户端。它不重写前端，而是在 WebView 这一层做补偿：注入脚本、拦截请求、接管下载、伪造页面可见性。

**后台保活**：在页面脚本执行前注入 JS，改写 `document.visibilityState`、`document.hidden`、`document.hasFocus`，并拦截 `visibilitychange`、`webkitvisibilitychange`、`blur`、`pagehide` 事件，让前端一直认为自己在前台。配合 `KeepAliveService` 前台服务，按需持有 `PARTIAL_WAKE_LOCK`。这是骗过前端，不是阻止系统休眠，所以挡不住系统层面的深度冻结。

**防烧屏**：在 WebView 上盖纯黑遮罩，同时把 WebView 设为 `INVISIBLE` 停止合成、帧率压到最低。导航栏由系统绘制在窗口外，遮罩盖不住，所以单独调用 `WindowInsetsController.hide(navigationBars())` 隐藏（API < 30 用 `SYSTEM_UI_FLAG_HIDE_NAVIGATION`）。解锁阈值为屏高的 22%，不低于 200dp。

**下载**：接管两条链路。页面里的 `a.download`（含 `blob:` URL）由注入脚本拦截点击，把 Blob 读成 base64 经 JS 桥交给原生；服务端 `Content-Disposition` 触发的下载由 `WebView.setDownloadListener` 接管。两条链路共用一个写盘函数，文件名统一消毒（去掉路径分隔符、控制字符、前导点，超长截断但保留扩展名）。API 29+ 走 MediaStore，低版本写公开下载目录。

**回复通知**：注入脚本监听 SillyTavern 的 `MESSAGE_RECEIVED` 事件，经 JS 桥把「生成完成」交给原生，原生按当前状态（后台 / 锁屏 / 挂机）决定是否发通知。

**页面加载**：

```text
onCreate
  └─ 并行预解析服务器域名，避免首个请求等 DNS
  └─ 创建 WebView
      └─ shouldInterceptRequest
          ├─ 同源 HTML → 拉取文档 → 在 <head> 后注入脚本 → 明文交还 WebView
          ├─ 静态资源 → 命中磁盘缓存直接返回，未命中则回源并写缓存
          └─ 非同源 → 直接放行给 WebKit
      └─ onPageStarted / onPageFinished → 计时、注入心跳脚本、生成桥凭证
```

**缓存**：只存响应 body，`Content-Type` 和 CORS 头在返回时重建。不能把 `Content-Encoding` 一起交还，否则 WebView 会二次解码失败。预取超时的慢主机会进 30 分钟黑名单，避免反复等待。

**JS 桥**：`addJavascriptInterface` 注入的对象在每个 frame 里都存在，包括跨域 iframe，而接口本身拿不到调用方是哪个 frame。因此：

- 每次顶层导航生成一个新的 256 位随机凭证，只放在注入脚本的闭包里
- 桥方法必须携带当前凭证，否则拒绝
- 调用来源还要再过一遍同源检查（协议 + 主机 + 有效端口）

跨域 iframe 拿不到主文档闭包里的变量，也就调不了桥。

**更新信任链**：清单（JSON）与 APK 同源绑定、逐跳校验，下载后核对包名、签名证书、版本号、SHA-256。信任根是签名证书而不是清单里的哈希：哈希和 APK 地址来自同一份清单，清单被篡改时哈希也会被一起改掉。

## 代码结构

14 个 Java 文件，共 7,734 行，零第三方依赖，只用 Android 框架 API 和 JDK。

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 胶水层：WebView 装配、对话框、下载、JS 桥、生命周期 |
| `CacheManager.java` | `shouldInterceptRequest` 的磁盘缓存、慢主机探测、预取线程池 |
| `PerfMonitor.java` | 帧率、长任务、内存探针与报表 |
| `UpdateManager.java` | 清单拉取、下载、签名校验、拉起安装器 |
| `IdleController.java` | 防烧屏挂机状态机（遮罩、手势、计时） |
| `PerfLog.java` | 有界环形日志 |
| `TextKit.java` | 文件名消毒、MIME 转扩展名、URL 缩短 |
| `UrlKit.java` | origin 解析、同源判定、私有网段判定 |
| `KeepAliveService.java` | 前台服务与通知 |
| `HtmlInjector.java` | 在 HTML `<head>` 后注入脚本 |
| `ReplyNotifier.java` | 回复通知渠道与权限 |
| `ApkProvider.java` | 零依赖 FileProvider，只暴露 updates 目录 |
| `SystemBars.java` | 导航栏隐藏与恢复 |
| `BridgeSession.java` | JS 桥的按页随机凭证 |

## 自己编译

需要 JDK 17+ 和 Android SDK 36。

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/tavernium-release-<version>.apk`。

- release 构建前必须准备好 `keystore.properties`，缺少时 Gradle 会直接报错，不会悄悄改用调试签名。签名是更新校验的信任根，签名一旦和已安装的版本不一致，应用内更新会拒绝，Android 也不允许覆盖安装。与其装的时候才发现，不如构建时就停下来。
- debug 构建的包名带 `.debug` 后缀、版本名带 `-debug`，可以和正式版同时安装，数据互相隔离。
- 自己签名的 release 版和官方版签名不同，两者不能互相覆盖安装，想换回官方版需要先卸载。

## 测试

```bash
cd verification && python3 run_verify.py
# 535 项 PASS，0 FAIL
```

不需要 Android SDK，有 JDK、Node.js 和 Python 3 就能跑：

- `gen_stubs.py` 生成最小的 Android API 桩（Activity、WebView、SharedPreferences 等）
- 桩、真实源码和测试一起用 `javac` 编译，在 Java 侧跑单元测试
- 注入页面的 JS 脚本单独抽出来，交给 Node 做行为验证
- 加上 `--android-jar <android.jar 路径>` 可以用真实 SDK 再编译一遍，确认没有依赖桩造成的假象

## 项目信息

| 项 | 值 |
|---|---|
| 版本 | 2.0.0（versionCode 52） |
| 包名 | `io.github.flashsky1024.tavernium`（debug 版为 `io.github.flashsky1024.tavernium.debug`） |
| 系统要求 | Android 7.0（API 24）及以上 |
| targetSdk / compileSdk | 34 / 36 |
| 语言 | Java 17 |
| 安装包 | release 232,009 字节（约 227 KB），debug 1,126,119 字节（约 1.1 MB） |
| CPU 架构 | 不限，纯 Java，没有 native 库 |
| 签名证书 | `CN=Tavernium, O=flashsky1024`，4096 位 RSA，有效期至 2054 年 |

## 许可

[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)。用大白话说：

- 下载安装、日常使用：没有任何义务
- 自己改代码、自己编译、自己用：也没有义务
- 把安装包（改没改都算）分发给别人：需要提供对应的源代码，并继续使用 AGPL-3.0
- 把改过的版本做成网络服务给别人用：需要向使用者提供修改后的源代码

选 AGPL 的原因：这个项目本身就是「把别人的 Web 服务包起来」，如果将来有人把它改成托管服务对外提供，改动应该回流到社区。

以上只是通俗解释，具体以协议原文为准。
