# 🤖 AI 自动助手

手机截屏 → DeepSeek API 分析 → 自动点击

用于自动完成淘宝京东 618 签到、日常任务等。

## 使用

1. 安装 APK 后打开
2. 输入你的 DeepSeek API Key
3. 填写任务描述（如"淘宝618签到领红包"）
4. 点击「启动」，授权屏幕截取和无障碍权限
5. 打开目标 App，AI 自动帮你完成

## 技术栈

- Kotlin + Android Jetpack
- OkHttp 调用 DeepSeek API（deepseek-chat，支持多模态）
- MediaProjection 截屏 + AccessibilityService 模拟点击
