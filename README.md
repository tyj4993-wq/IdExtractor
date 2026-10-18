# 链接 ID 提取（IdExtractor）

一个极简的安卓工具：粘贴小说分享链接，自动提取其中的 `book_id`，点击结果即可复制。

## 功能

- **粘贴即识别**：长链接参数中含 `book_id` 时，本地正则立即命中，无需联网
- **短链自动解析**：如 `https://changdunovel.com/t/q_5xPZEV8ew/` 这类不含 ID 的短链，App 会自动跟随 HTTP 跳转，从最终地址中取出 ID
- **点击复制**：结果卡片整块可点，复制后显示「已复制到剪贴板」
- **分享直达**：也可在浏览器/阅读 App 里「分享」链接到本应用，直接进入提取

实测样例：

| 输入 | 输出 |
|---|---|
| 完整长链接（含 `book_id=7608543566272400408`） | `7608543566272400408` |
| 分享文案 + 链接（`推荐一部好书《…》https://…`） | `7608543566272400408` |
| 短链 `changdunovel.com/t/q_5xPZEV8ew/` | `7608543566272400408`（联网解析） |
| 直接粘贴 19 位 ID 本身 | 原样返回 |

## 技术参数

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material 3） |
| 最低系统 | Android 8.0（API 26） |
| 目标系统 | Android 15（API 35） |
| 第三方依赖 | 无（网络用 `HttpURLConnection`） |
| 权限 | 仅 `INTERNET` |
| APK 体积 | 约 3–5 MB（debug） |

## 构建方式一：GitHub Actions（无需本地环境）

1. 在 GitHub 新建一个仓库（私有或公开均可）
2. 把本目录下**所有文件**（含 `.github` 隐藏目录）上传到仓库根目录
3. 仓库会自动触发 `Build APK` 工作流；也可在 **Actions → Build APK → Run workflow** 手动触发
4. 构建完成后，进入该次运行页面，在底部 **Artifacts** 下载 `IdExtractor-apk.zip`，解压得到 APK

> 首次构建约 3–5 分钟，之后有缓存会更快。

## 构建方式二：Android Studio

用 Android Studio（Koala 2024.1.1 及以上）直接 `Open` 本目录，等待 Gradle Sync 完成后
执行 **Build → Build Bundle(s)/APK(s) → Build APK(s)**。

## 在 Android 15 / iQOO 上安装

1. 把 APK 传到手机（微信文件传输 / 数据线 / 网盘均可）
2. 点击安装，系统会提示「未知来源应用」—— 需在弹窗中选择**允许**该来源安装
3. vivo / iQOO 的 i管家可能弹出**风险扫描提示**，选择「继续安装」即可（debug 签名的应用因无应用商店来源会触发此提示，属正常现象）
4. 安装完成后首次打开，若提示网络权限，允许即可

## 目录结构

```
IdExtractor/
├─ .github/workflows/build.yml      # 云端自动构建 APK
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/linkextract/id/
│     │  ├─ MainActivity.kt         # Compose 界面与状态管理
│     │  └─ IdParser.kt             # ID 提取与短链解析
│     └─ res/                       # 图标、主题、字符串
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle.properties
```

## 实现要点

- **正则加前置边界**：`(?:^|[?&\s])book_?id=(\d{6,25})`，避免把 `notebook_id` 之类的字段误判为 `book_id`
- **请求过期判定**：输入变化时递增请求序号，异步解析返回后校验序号，防止旧请求覆盖新结果
- **输入去抖**：输入停顿 400 ms 后才发起网络请求，逐字输入不会触发多次请求
