# 记账本 · 原生 Android App

原生界面的个人记账 App，数据走云端账本，与网页端、Excel 端实时同步。

## 功能
- WorkBuddy 账号登录（跳转系统浏览器，回调到 App）
- 流水列表（下拉刷新，长按删除）
- 新增流水（类别、周期、日期、项目、金额、账户）
- 分类统计

## 技术栈
- Java + AndroidX（AppCompat / Material / RecyclerView / SwipeRefreshLayout）
- OkHttp + Gson/JSON
- 云端：WorkBuddy Cloud REST API（PostgREST 风格）
- 编译：GitHub Actions（Gradle 8.9 / JDK 17 / Android SDK 35）

## 构建
推送到 main 分支会自动触发 `.github/workflows/build.yml` 构建，
产物 `app-release-unsigned.apk` 在 Actions 的 Artifacts 里下载，
再在本地用签名密钥签名即可安装。

## 安全说明
- 登录令牌保存在 App 本地 SharedPreferences，不上传仓库
- 应用层不硬编码任何用户凭证
