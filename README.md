# Mimi 课表

武夷学院课程安排与成绩查询工具。

把教务系统里散落的课表和分数，收进一个能随手打开的地方。

## 功能

- **课程表** —— 按周次查看当日课程，支持周次切换
- **成绩查询** —— 分学期拉取成绩，查看已修课程与分数
- **桌面小组件** —— 主屏直接看当天课程，不用打开应用
- **公告与更新** —— 启动时拉取公告，检测新版本
- **崩溃日志上报** —— 异常时记录日志，可一键反馈

## 技术栈

| 项 | 值 |
| :-- | :-- |
| 语言 | Java |
| 最低版本 | Android 7.0（API 21） |
| 目标版本 | Android 14（API 34） |
| 包名 | `com.mimi.kebiao` |
| 当前版本 | 3.1（versionCode 17） |

课程表与成绩页面通过 WebView 承载教务系统（`jwxt.wuyiu.edu.cn`），Java 层负责登录态维持、会话超时判断、数据缓存与桌面小组件刷新。

## 构建

```bash
# 1. 准备签名配置（不会被提交）
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties，填入你的密钥库路径与密码

# 2. 配置本地 SDK 路径
echo "sdk.dir=/your/path/to/Android/sdk" > local.properties

# 3. 构建
./gradlew assembleRelease
```

> 仓库里的 `android-sdk/` 已按 `.gitignore` 排除 —— 那是开发机内置的完整 SDK，体积约 437M。
> 克隆后请自行安装 Android SDK 并在 `local.properties` 中指定路径。

## 目录结构

```
app/src/main/
├── java/com/wuyi/kebiao/
│   ├── MainActivity.java          # 主界面（Fragment 容器）
│   ├── SplashActivity.java        # 启动页
│   ├── HomeFragment.java          # 课表页
│   ├── GradeFragment.java         # 成绩页
│   ├── ProfileFragment.java       # 我的
│   ├── AnnouncementFetcher.java   # 公告拉取
│   ├── UpdateChecker.java         # 更新检查
│   ├── CrashHandler.java          # 崩溃处理
│   ├── FeedbackSender.java        # 反馈上报
│   └── widget/                    # 桌面小组件
└── assets/www/
    ├── index.html                 # 课表页面
    └── grade.html                 # 成绩页面
```

## 说明

签名密钥（`*.keystore`）与 `keystore.properties` 均已被 `.gitignore` 排除，**切勿**提交到仓库。

## License

保留所有权利。源码开放用于学习交流，未经许可不得用于商业用途。
