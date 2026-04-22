# Boss直聘外贸招聘监控 APK 打包指南

## 项目结构

```
BossJobScraper/
├── app/
│   ├── build.gradle              # 模块级构建配置
│   ├── proguard-rules.pro        # 代码混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bossscraper/app/
│       │   ├── MainActivity.java        # 主界面
│       │   ├── adapter/JobAdapter.java  # 列表适配器
│       │   ├── model/JobItem.java       # 数据模型
│       │   ├── network/BossApiClient.java  # 抓取客户端
│       │   ├── viewmodel/JobViewModel.java # 业务逻辑
│       │   ├── service/ScraperService.java # 前台服务
│       │   └── receiver/BootReceiver.java  # 开机接收器
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/item_job.xml
│           └── values/ ...
├── build.gradle                  # 项目级构建配置
├── settings.gradle
└── gradle/wrapper/gradle-wrapper.properties
```

## 编译环境要求

| 工具 | 版本要求 |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) 或更高 |
| JDK | 17 |
| Android SDK | Compile SDK 34, Min SDK 24 |
| Gradle | 8.4 |

## 编译步骤

### 方式一：Android Studio（推荐）

1. **打开项目**
   ```
   File → Open → 选择 BossJobScraper 目录
   ```

2. **等待 Gradle 同步**（首次需下载依赖，约 2-5 分钟）

3. **编译 Debug APK**（直接安装测试）
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   生成路径：`app/build/outputs/apk/debug/app-debug.apk`

4. **编译 Release APK**（发布版本）
   ```
   Build → Generate Signed Bundle/APK → APK
   ```
   需要创建或选择签名密钥。

### 方式二：命令行（需配置 Android SDK）

```bash
# 进入项目目录
cd BossJobScraper

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK（需要签名配置）
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

### Windows 用户

```cmd
cd BossJobScraper
gradlew.bat assembleDebug
```

## 功能说明

### 核心功能
- **实时抓取**：调用 Boss直聘 搜索接口，抓取外贸/国际贸易/跨境电商相关岗位
- **列表展示**：每条记录显示岗位名称、公司名称、公司地址、薪资、发布时间
- **地区筛选**：顶部 Spinner 支持按城市筛选（全国/北京/上海/广州/深圳等21个城市）
- **自动更新**：每 5 分钟自动刷新一次数据，右上角显示倒计时
- **手动刷新**：点击"立即刷新"按钮或下拉列表触发即时刷新

### 搜索关键词
- 外贸、外贸业务员、国际贸易、外贸跟单、跨境电商、外贸销售、进出口、外贸经理

### 关于登录限制
Boss直聘 需要登录才能获取完整数据。**未登录状态下，App 会显示演示数据**。
如需抓取真实数据，有两种方案：

**方案A（推荐）：WebView 登录**
在 App 内嵌 WebView，让用户手动登录 Boss直聘，之后复用 Cookie 发起请求。

**方案B：手动填入 Cookie**
用浏览器登录 Boss直聘后，提取 `wt2` 等 Cookie 值，在设置页面填入。

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 访问 Boss直聘 接口 |
| ACCESS_NETWORK_STATE | 检测网络状态 |
| FOREGROUND_SERVICE | 后台持续运行（前台服务通知） |
| POST_NOTIFICATIONS | Android 13+ 显示通知 |
| RECEIVE_BOOT_COMPLETED | 开机自启（可选）|

## 常见问题

**Q: 编译时提示 SDK 版本不符？**  
A: 在 Android Studio 的 SDK Manager 中安装 API Level 34。

**Q: 运行后显示"演示数据"？**  
A: 因 Boss直聘需登录，未登录时展示内置演示数据，功能逻辑完全一致。

**Q: 如何修改刷新间隔？**  
A: 修改 `JobViewModel.java` 中的 `AUTO_REFRESH_INTERVAL` 常量（单位：毫秒）。

**Q: 如何增加更多城市？**  
A: 在 `BossApiClient.java` 的 `CITY_CODES` 数组中追加城市名和城市代码。

## 法律声明

本软件仅供学习研究使用，请遵守 Boss直聘 [用户协议](https://www.zhipin.com/agreement.html)。
商业使用请通过官方渠道获取数据授权。
