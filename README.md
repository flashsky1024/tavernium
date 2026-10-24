# Tavernium

把自建的 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 装进手机。

一个轻量的 Android 外壳，用系统 WebView 加载你自己服务器上的 SillyTavern 页面，
再补上浏览器做不到的几件事。零第三方依赖，纯 Java，约 7,700 行。

> 这不是 SillyTavern 官方客户端，与该项目的开发者没有隶属关系。
> 应用本身不含任何模型、角色或内容，只是一个你已经跑起来的 Web 服务的容器。

---

## 它解决什么问题

| 场景 | 浏览器 | Tavernium |
|---|---|---|
| 切到后台，生成到一半被系统冻结 | 断流、丢回复 | 注入脚本伪造页面可见性，前端以为一直在前台 |
| 挂机时屏幕常亮，OLED 烧出残影 | 无解 | 纯黑遮罩 + 隐藏系统导航栏 + 帧率压到最低 |
| 导出的聊天记录落在浏览器下载沙盒里 | 找不到 | 直接写 `下载/SillyTavern/` |
| 锁屏时想知道角色回没回 | 要盯着通知栏 | 后台回复完成时发一条系统通知 |
| 每次打开都要重新找地址、重新登录 | 每次都点一遍 | 记住服务器与凭据，一键进入 |

---

## 特性

- **后台保活** —— 改写 `visibilityState` / `hidden` / `hasFocus`，拦截 `visibilitychange`、
  `pagehide`，让前端在后台继续跑生成
- **防烧屏挂机** —— 纯黑遮罩（OLED 黑像素不发光）+ WebView 停止合成 + 系统导航栏隐藏；
  按住上滑越过屏幕高度 22% 解除，单点不会误触
- **下载落盘** —— 同时接管 `a.download` 与服务端 `Content-Disposition` 两条链路，
  文件名统一消毒后写入公共下载目录
- **回复完成通知** —— 后台、锁屏、挂机时，角色回复完成发一条通知；默认关闭，可选开启
- **内置诊断** —— 帧率、长任务、内存、资源失败的实时探针，日志可一键导出
- **自建更新通道** —— 清单与 APK 同源绑定、逐跳校验、包名/签名/版本/哈希四重验证

---

## 安装

从 [Releases](../../releases) 或作者的更新站下载 APK。

**2.0.0 与 1.8.x 互不兼容**：包名从 `wiki.nandflash.tavernkeep` 改成了
`io.github.flashsky1024.tavernium`，两者是两个独立应用，不会互相覆盖。
从旧版升级请先备份聊天记录，装好新版后重新登录。

| 版本 | 包名 | 用途 |
|---|---|---|
| `tavernium-release-*.apk` | `io.github.flashsky1024.tavernium` | 日常使用 |
| `tavernium-debug-*.apk` | `...tavernium.debug` | 排查问题，与正式版**可同时安装** |

---

## 首次配置

启动后填服务器地址。地址规则是硬性的：

| 地址 | 是否允许 | 原因 |
|---|---|---|
| `https://` 任意域名 | ✅ | 加密通道 |
| `http://192.168.x.x`、`http://10.x`、`http://172.16-31.x` | ✅ | 局域网自建 |
| `http://localhost` | ✅ | 本机调试 |
| `http://` + 公网 IP 或公网域名 | ❌ | 明文传输会把 Basic 凭据暴露给链路上的任何人 |

账号密码是可选项：

- SillyTavern **开了自己的登录页** → 两栏留空
- 前面套了 Nginx Basic Auth → 填 Basic 的用户名密码

---

## 从源码构建

需要 **JDK 17+** 和 **Android SDK 36**。

```bash
git clone <this-repo>
cd tavernium

# release 构建必须有签名配置，否则 Gradle 会直接报错退出
cat > keystore.properties <<'EOF'
storeFile=keystore/your-release.jks
storePassword=***
keyAlias=***
keyPassword=***
EOF

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/tavernium-release-<version>.apk
```

`keystore.properties` 与 `*.jks` 都在 `.gitignore` 中，不会进仓库。

`local.properties` 里的 `sdk.dir` 指向你的 SDK 路径；没有这个文件时 Gradle 会读 `ANDROID_HOME`。

> 默认的更新源常量在 `UpdateManager.DEFAULT_BASES` 中指向作者的服务器。
> 自行分发请改成你自己的地址，否则你的用户会收到作者发布的更新。

---

## 测试

离线测试不依赖 Android SDK，只需要 JDK 和 Node：

```bash
cd verification
python3 run_verify.py
# 期望：535 项 PASS，0 FAIL
```

原理：`gen_stubs.py` 生成一套最小的 Android API 桩，和真实源码一起 `javac` 编译，
再跑单元测试；JS 相关的逻辑用 Node 做行为验证（帧率节流、资源错误钩子、桥调用鉴权）。

```bash
# 额外用真实 android.jar 编译一遍，确认没有依赖桩的假象
python3 run_verify.py --android-jar /path/to/android-36/android.jar
```

---

## 项目结构

```
tavernium/
├── app/src/main/java/io/github/flashsky1024/tavernium/
│   ├── MainActivity.java      胶水层：WebView 装配、对话框、下载、JS 桥
│   ├── CacheManager.java      shouldInterceptRequest 的磁盘缓存与慢主机探测
│   ├── UpdateManager.java     清单拉取、下载、签名校验、拉起安装器
│   ├── BridgeSession.java     JS 桥的按页凭证
│   ├── IdleController.java    防烧屏挂机状态机
│   ├── ReplyNotifier.java     回复完成通知
│   ├── PerfMonitor.java       帧率/长任务/内存探针
│   └── ...                    共 10 个文件
└── verification/              离线测试体系（桩 + javac + node）
```

更细的设计说明、缺陷分级与踩坑记录见 [`tavernium/AGENT-HANDOFF.md`](tavernium/AGENT-HANDOFF.md)，
本轮改动过程见 [`tavernium/DEVELOPMENT.md`](tavernium/DEVELOPMENT.md)。

---

## 已知限制

- **`usesCleartextTraffic` 仍为 `true`**，为了兼容局域网自建酒馆。应用层已限制服务器入口与
  普通请求，但重定向、Service Worker、WebSocket 等路径仍未逐一收口
- **回复通知依赖页面在后台仍能执行 JS**。系统深度冻结 WebView 时，通知会延迟或丢失
- **单服务器、单账号**，没有多实例切换
- 只针对个人自建场景优化，不打算做多用户、主题系统或插件市场

---

## 常见问题

**装完打开一片白？**
服务器地址不符合上面的规则。应用会短暂白屏后弹出设置框并提示原因，按提示改成
`https://` 或局域网 IP 即可。

**提示"签名不一致"？**
你装过同包名的旧构建（比如早期用 debug 密钥签的包）。卸载旧版再装。

**聊天记录在哪？怎么备份？**
SillyTavern 的数据主体在服务端，本地主要是 WebView 的 localStorage / IndexedDB
（`/data/data/io.github.flashsky1024.tavernium/app_webview/`）。
卸载应用会清空这部分，重要内容请用应用内的导出功能存到下载目录。

**为什么后台一会儿还是断了？**
省电策略会冻结后台进程。请在系统设置里把本应用加入电池优化白名单（首次启动会提示）。

---

## 许可

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。

简单说：

- 可以自由使用、修改、分发
- 如果修改后**通过网络向他人提供服务**，必须公开修改后的源码
- 分发时必须保留同样的许可，并注明改动

选择 AGPL 而不是 MIT，是因为这个项目本身就是"把别人的 Web 服务包起来"的形态，
如果将来有人把它改成托管服务分发，改动应该回流到社区。自己编译自己用不承担额外义务。

---

## 免责

本项目按"现状"提供，不附带任何担保。因使用本软件导致的任何数据丢失、服务中断或
账号问题，作者不承担责任。

SillyTavern 是其各自所有者的项目名，此处仅用于说明兼容对象。
