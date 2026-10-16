# 晚安打卡助手（Android）

系统级定时打卡 App：用 AlarmManager 精确闹钟触发，不依赖后台常驻。
打卡请求格式基于 2026-10-16 对打卡页的实测抓包（见各 Kotlin 文件注释）。

## 结构

```
app/src/main/
├── java/com/gnuestc/checkin/
│   ├── MainActivity.kt      # 单页 UI：登录入口 / 测试按钮 / 权限引导
│   ├── LoginActivity.kt     # 内嵌 WebView：jzsz 首页触发 SSO 登录，自动捕获并验证 SESSION
│   ├── Prefs.kt             # 本地配置存取
│   ├── CheckinApi.kt        # OkHttp 打卡接口（复刻抓包请求）
│   ├── CheckinRunner.kt     # 单次打卡流程：查 today → 判断 PENDING → POST
│   ├── AlarmScheduler.kt    # 精确闹钟：每天 21:05，失败重试至 23:20
│   ├── CheckinReceiver.kt   # 闹钟触发执行器
│   ├── BootReceiver.kt      # 开机重建闹钟 + 窗口内补打
│   ├── Notifier.kt          # 结果通知
│   └── LogStore.kt          # 运行日志（最近 60 行，含后台事件）
└── res/                     # 布局与文案
```

## 使用步骤

1. Android Studio 打开 `android/` 目录，首次同步会自动补全 Gradle wrapper
2. 真机安装（Debug 直接 Run 即可，侧载自用）
3. 点「登录账号」→ 页面自动进入学校统一身份认证（CAS）→ 登录成功后自动返回并保存会话
4. 确认「后台自动打卡服务」开关为 ON（关闭会取消所有定时，不再自动打卡）
5. 点「权限检查」：依次授予精确闹钟、电池优化白名单；再在系统设置里允许自启动（国产 ROM）
6. 点「测试打卡」验证一次；之后每天 21:05 自动执行，事件记录在运行日志里

## 关键参数（改这里就能适配变化）

- 打卡点坐标 / 文案：`CheckinApi.kt` 的 `LATITUDE`、`LONGITUDE`、`REVERSE_GEOCODE`
- 触发时间 / 重试窗口：`AlarmScheduler.kt` 的 `TRIGGER_HOUR/MINUTE`、`RETRY_INTERVAL_MS`、`WINDOW_END_*`
- UA：`CheckinApi.kt` 的 `USER_AGENT`

## 安全说明

- `SESSION` 是账号级凭证，App 目前用普通私有 SharedPreferences 存（仅本 App 可读），
  且已关闭 allowBackup 防止备份导出；对个人侧载够用。如需更高强度可后续换 Keystore 加密。
- 服务端会记录 clientIp / UA / 时钟偏差等遥测字段，建议固定在一台设备 + 一个网络环境运行。

## 已知边界

- 首次触发依赖你已配置 Cookie 且权限到位；耳机充电/低电量模式不影响精确闹钟（setExactAndAllowWhileIdle）
- 每次打开 App 会确保定时存在：被系统清理/强停后自动恢复；在 21:00–23:30 内打开会安排 1 分钟后尽快补打
- 手持「强行停止」App 后闹钟会被系统清除，下次打开 App 会自动恢复
- 极端弱网下单次执行可能超时失败，由 5 分钟重试链兜底
