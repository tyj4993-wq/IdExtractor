# IdExtractor 交接文档

> 交接时间：2026-10-28
> 当前版本：v1.5（已推送并构建成功，真机验证结果未知）
> 仓库：https://github.com/tyj4993-wq/IdExtractor （public，默认分支 `main`）

---

## 0. 一句话现状

**核心功能「粘贴链接提取小说 ID」已完全可用；「自动查询短剧版权中心 IP 库」卡在登录环节——App 内嵌 WebView 无法渲染出登录表单，尝试了 5 个版本仍未解决。**

---

## 1. 项目目标

用户（短剧版权相关从业者）的日常工作流：

1. 从番茄小说 App 复制分享链接（短链或长链）
2. 从中提取 `book_id`（19 位数字）
3. 到「短剧版权中心」IP 库用该 ID 搜索，查看作品信息（题材、评分、字数、读者占比、已上架漫剧数等）

目标是把这个三步流程做成一个安卓 App：**粘贴链接 → 自动提取 ID → 自动查库 → 卡片展示结果**。

## 2. 用户环境约束（重要）

- **只有手机，没有电脑**。所有方案不能依赖 PC 端操作（F12 复制 Cookie 这条路对他不成立）
- 手机：**vivo iQOO，Android 15（API 35）**
- 系统浏览器里**已登录**短剧版权中心，但 App 内无法复用（WebView Cookie 与浏览器完全隔离）

## 3. 已完成且可用的部分

### v1.0 —— 纯 ID 提取（已验证可用）

- 粘贴长链 / 短链 / 纯 ID / 分享文案混合文本，均可正确提取
- 点击结果复制到剪贴板
- 支持从其他 App「分享」链接直达
- 深浅色自适应，适配 Android 15 edge-to-edge

### v1.1 起新增的查询链路（代码已就绪，未跑通）

- `IpApi.search()` 已实现完整接口调用与 JSON 容错解析
- `Prefs` 持久化 Cookie
- `LoginScreen` 内嵌 WebView + 手动粘贴 Cookie 双通道
- 原生卡片渲染（字段中文标签映射 + 原始 JSON 展开兜底）

---

## 4. 已验证的技术事实（不要重复验证）

### 4.1 短链解析

```
https://changdunovel.com/t/q_5xPZEV8ew/
  → HTTP 302（非 JS 跳转）
  → https://changdunovel.com/ug/pages/book-share?...&book_id=7608543566272400408&...
```

纯 HTTP 跟随重定向即可，**不需要 WebView**。

### 4.2 提取正则

```kotlin
private val PURE_ID  = Regex("^\\d{12,25}\$")
private val PARAM_ID = Regex("""(?:^|[?&\s])book_?id=(\d{6,25})""", RegexOption.IGNORE_CASE)
private val JSON_ID  = Regex("\"book_?id\"\\s*:\\s*\"?(\\d{6,25})", RegexOption.IGNORE_CASE)
```

**前置边界 `[?&\s]` 必须有**，否则 `notebook_id=` 会被误匹配为 `book_id`。

已用 Python 复刻验证 6 组输入全部正确（长链 / 文案+链 / 短链 / 纯 ID / URL 编码链 / 干扰项）。

### 4.3 IP 库查询接口（关键）

```
GET https://www.shortdramas.com/api/playlet/ip/list/v1
    ?tab=comic_playlet_ip
    &page_no=1
    &search_type=novel_id
    &search_keyword=<novel_id>
```

- 未登录返回：`{"code":-203,"msg":"用户未登录"}`，`Content-Type: application/json`
- **说明路由存在、参数名正确、只差身份**
- 前端代码中**未发现** `X-Bogus` / `msToken` / `a_bogus` 等风控参数

**假阳性陷阱**：`/api/copyright/ip/list` 返回 200 但 `Content-Type: text/html`，是 SPA 兜底页，不是接口。

### 4.4 站点技术栈

- 字节系微前端：**garfish + Module Federation + React 18 + Arco Design**
- 版权模块 publicPath：
  `//lf-fe-novel-admin.fanqieopen.com/obj/bucket-novel-admin/novel/fe/playlet_copyright_vmok/`
- 页面 HTML 74 KB，为 SPA 骨架，数据走 XHR
- **服务端对 Chrome UA 与 WebView UA（含 `; wv`）返回逐字节相同**，排除 UA 拦截

### 4.5 关键域名异常

`tosv.byted.org` **公网 DNS 不存在**：

- 本地 DNS：`Non-existent domain`
- Google 公共 DNS（`dns.google/resolve`）：`Status: 3`（NXDOMAIN）
- 属站点引用内部域名资源，非用户网络问题

### 4.6 登录接口探测

```
POST /passport/web/login
→ {"data":{"captcha":"","desc_url":"","description":"非法应用","error_code":22},"message":"error"}
```

说明 passport 有应用身份校验（`aid` / `account_sdk_source` 等），直接裸调不行。若要做原生登录，需先从登录页 JS 中挖出这些参数。

### 4.7 真机诊断数据（v1.4 采集）

```
DOM 89621B · 高 2133px · bg rgb(51,51,51) · fg rgb(255,255,255) · dark=0
标题: 短剧创作者中心-短剧创作服务平台-抖音集团短剧版权中心
正文: 加入我们 让好创作收获好回报… 手机登录 密码登录 获取验证码…
异常: tosv.byted.org/obj/novel-platform/<hash> → -2 ERR_NAME_NOT_RESOLVED
```

**注意**：「正文」里那段登录文案**很可能是 SPA 的 SEO 隐藏文本，不是真实渲染的表单**。证据是 DOM 字节数增长但页面中央只有一个破损小图标。

---

## 5. 已尝试方案与结果（避免重复踩坑）

| 版本 | 改动 | 真机结果 |
|---|---|---|
| v1.0 | 纯 ID 提取 | ✅ 可用 |
| v1.1 | WebView 登录 + 接口查询 + 原生卡片 | 登录页**全白屏** |
| v1.2 | 加错误捕获、DOM 探针 | 仍白屏，无错误输出 |
| v1.3 | 手动 Cookie 通道 + 探针加背景色 | 出现加载动画后变**深灰屏** |
| v1.4 | 注入 `*{opacity:1;visibility:visible}` | 背景变浅、中央破损小图标，仍无表单 |
| v1.5 | **默认桌面 UA** + 拦截 NXDOMAIN 域名 + 恢复 `display:none` | **待反馈** |

**结论**：单纯修 CSS 可见性无效，问题更可能是**真实视图未生成**（脚本执行链断裂）。

---

## 6. 代码结构

```
IdExtractor/
├─ .github/workflows/build.yml      # CI 构建
├─ app/
│  ├─ build.gradle.kts              # compileSdk/targetSdk=35, minSdk=26
│  └─ src/main/
│     ├─ AndroidManifest.xml        # 仅 INTERNET 权限 + ACTION_SEND 接收
│     ├─ java/com/linkextract/id/
│     │  ├─ MainActivity.kt         # 主界面 + 两级 LaunchedEffect 状态机
│     │  ├─ IdParser.kt             # 链接 → novel_id（本地正则 + 短链跳转）
│     │  ├─ IpApi.kt                # IP 库接口调用 + JSON 容错解析
│     │  ├─ LoginScreen.kt          # WebView 登录 + 诊断 + 手动 Cookie
│     │  └─ Prefs.kt                # Cookie 持久化（SharedPreferences）
│     └─ res/                       # adaptive icon、主题、字符串
├─ build.gradle.kts                 # AGP 8.6.1 / Kotlin 2.0.21 / compose plugin
├─ settings.gradle.kts
└─ gradle.properties
```

### 状态机要点

```kotlin
// 第一步：输入 → novel_id
LaunchedEffect(input) { ... IdParser.extractDirect() / IdParser.resolve() ... }

// 第二步：novel_id + cookie → 查询
LaunchedEffect(novelId, cookie) { ... IpApi.search() ... }
```

两个 effect 都用 `AtomicInteger` 序号防止旧异步结果覆盖新输入。

---

## 7. 构建与发布流程（重要）

### 推送

本机**无需创建 token**。PortableGit 自带 Git Credential Manager：

```
/mingw64/bin/git-credential-manager
```

直接 `git push origin main` 即可成功（凭据由 GCM 自动提供）。

### CI 构建

- 触发：`push` 到 `main` 或 `master`，或手动 `workflow_dispatch`
- 无 `gradle-wrapper`，靠 `gradle/actions/setup-gradle@v4` + `gradle-version: '8.7'`
- 环境：JDK 17（temurin）、AGP 8.6.1、Kotlin 2.0.21
- 产物：`dist/IdExtractor-<run_number>.apk` → artifact `IdExtractor-apk`
- 耗时：首次约 3 分钟，有缓存后约 50 秒

### 下载 artifact（有坑）

**必须用 curl，不要用 Python urllib**：artifact 下载会 302 到对象存储，urllib 会把 `Authorization` 头带过去导致 **401**。

```bash
# 1. 取 token（不打印内容）
CRED=$(printf "protocol=https\nhost=github.com\n\n" | timeout 20 git credential fill)
TOKEN=$(printf "%s" "$CRED" | grep '^password=' | cut -d= -f2-)

# 2. 列 artifact，取 id
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/tyj4993-wq/IdExtractor/actions/runs/<RUN_ID>/artifacts"

# 3. 下载
curl -sL -H "Authorization: Bearer $TOKEN" \
  -o dist/apk.zip \
  "https://api.github.com/repos/tyj4993-wq/IdExtractor/actions/artifacts/<ARTIFACT_ID>/zip"
```

### 已知的签名问题

**CI 每次构建的 debug keystore 是随机生成的，签名不同 → 无法覆盖安装，必须先卸载旧版。**

待修复：把 debug keystore 固定下来（提交到仓库或用 Actions 缓存），之后才能直接覆盖升级。

---

## 8. 建议的下一步（按优先级）

### 方案 A：等 v1.5 反馈

桌面 UA 是最有希望的尝试。若桌面模式能显示表单，问题即解决。

### 方案 B：原生登录（若 WebView 彻底失败）

在 App 内自己做「手机号 + 验证码」界面，直接调 passport 接口，完全不加载网页。

需要做的：

1. 从登录页 JS 里挖出 `aid`、`account_sdk_source`、`device_platform` 等必填参数
2. 找到发送验证码的接口（通常形如 `/passport/mobile/send_code`）
3. 处理可能的图形验证码风控

成本较高，但能彻底摆脱 WebView 渲染问题。

### 方案 C：换思路拿 Cookie

用户只有手机，但可以考虑：

- 让用户在**手机浏览器**登录后，通过支持 Cookie 查看的第三方浏览器（如 Kiwi、Via）导出
- 或者 App 提供「从浏览器分享链接导入」的辅助流程

这些都需要用户额外装软件，体验一般，作为备选。

### 方案 D：放弃登录，改用其他数据源

如果该 IP 库的作品信息有公开渠道（如番茄小说公开详情页），可以绕开登录系统直接取。需先确认公开接口能提供哪些字段。

---

## 9. 环境/工具链备忘

- 本机 **没有** JDK、Android SDK、Gradle、Android Studio，**不要尝试本地编译**
- 构建完全走 GitHub Actions
- Python 3.13 可用于辅助脚本：`C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe`
- 沙箱中 `os.remove()` 会被 safe-delete 拦截并中断脚本，**清理临时文件要避开删除操作**

---

## 10. 协作偏好

- 用户偏好**先讨论选型再动手**，不喜欢未经确认就铺开实现
- 汇报时要给**确定的结论和证据**，不要含糊的可能性堆砌
- 每次改动都要说明「这版解决了什么、还可能卡在哪」
- 用户不做开发，不要让他执行命令行或读代码，所有技术操作由 AI 完成
