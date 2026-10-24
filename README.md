# Tavernium

把自建的 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 前端包成 Android 应用。
零第三方依赖，纯 Java，约 7,700 行。

> 这不是 SillyTavern 官方客户端，与该项目没有隶属关系。应用本身不含任何模型、角色或内容，
> 只是给一个你已经在运行的 Web 服务套了个壳。

## 为什么需要壳

浏览器能打开 SillyTavern，但有三件事它做不到：

| 问题 | 浏览器 | Tavernium |
|---|---|---|
| 切到后台一会儿，页面被冻结，流式输出断在半截 | ✗ | 注入脚本伪造可见性，页面以为自己始终在前台 |
| 挂机时屏幕常亮，OLED 烧屏 | ✗ | 纯黑遮罩 + 隐藏导航栏 + 帧率压到最低 |
| 导出的聊天记录落在浏览器的下载沙盒里 | ✗ | 直接写 `下载/SillyTavern/` |

## 特性

- **后台保活** —— 改写 `visibilityState` / `hidden` / `hasFocus`，拦住 `visibilitychange`、`pagehide`，
  前端不会因为"切后台"而停止生成
- **防烧屏挂机** —— 纯黑遮罩（OLED 黑像素不发光）+ WebView 停止合成 + 系统导航栏隐藏，
  上滑越过屏幕高度 22% 解除，单点不会误触
- **下载落盘** —— 拦截 `a.download` 与 `Content-Disposition` 两条链路，文件名统一消毒后写入下载目录
- **回复完成通知** —— 后台/锁屏/挂机时，角色回复完成发一条通知；默认关闭，需手动开启
- **内置诊断** —— 帧率、长任务、内存、资源失败的实时探针，日志可导出
- **自建更新通道** —— 清单同源绑定、逐跳校验、包名/签名/版本/哈希四重验证

## 安装

从作者提供的更新站下载 APK，或自行构建（见下）。

首次启动会让你填服务器地址：

| 地址形式 | 是否允许 |
|---|---|
| `https://` 任意域名 | ✅ |
| `http://192.168.x.x` / `10.x` / `172.16-31.x` | ✅ 局域网自建 |
| `http://localhost` | ✅ 本机调试 |
| `http://` + 公网 IP 或公网域名 | ❌ 会被拒绝 |

这条限制不是洁癖：应用会把 Basic 凭据附加到同源请求上，明文公网等于把密码交给链路上的任何人。

## 构建

需要 JDK 17+ 和 Android SDK 36。

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/tavernium-release-<version>.apk
```

`local.properties` 里的 `sdk.dir` 需要指向你自己的 SDK 路径。

## 测试

离线测试不依赖 Android SDK，只需要 JDK 和 Node：

```bash
cd verification && python3 run_verify.py
# 期望 535 项 PASS
```

原理是生成一套最小的 Android API 桩，和真实源码一起编译，再跑单元测试与 JS 行为测试。
`verification/` 目录里还有 `--android-jar` 选项，可以额外用真实 SDK 编译一遍。

## 已知限制

- **release 构型目前用 debug 签名**。个人自用阶段如此，不适合当作正式分发渠道；
  换正式 keystore 会导致已安装用户无法覆盖升级，这是有意推迟的决策
- **HTTPS 页面禁止混合内容**，`usesCleartextTraffic` 仍为 `true` 以兼容局域网酒馆，
  但应用层已限制服务器入口与普通请求
- **回复通知依赖页面在后台仍运行 JS**。系统冻结 WebView 时通知会延迟或丢失
- 只针对单人自建场景优化，没有账号体系、多服务器切换、主题

## 文档

- [`tavernium/DEVELOPMENT.md`](tavernium/DEVELOPMENT.md) —— 改动记录与遗留项
- [`tavernium/AGENT-HANDOFF.md`](tavernium/AGENT-HANDOFF.md) —— 代码地图、缺陷分级、协作协议

## 许可

[MIT](LICENSE)
