# Android 健康来源与 Watch8 使用说明

本次范围：手机端健康数据读取来源二选一，以及 Wear OS 手表查看血糖、提醒、手机同步。
没有新增三星血糖写入，没有让手机端健康读取承担手表传感器实时采集。

## 手机入口与数据范围

设置 → 健康数据与血糖 → 来源与授权：

1. 选择「Android 健康数据（Health Connect）」或「三星健康（直接读取）」。
2. 打开「读取健康数据」，选择类型和读取天数，点击「请求读取授权」。
3. 点击「手动同步」。进入页面也按原来的五分钟节流规则刷新，未启用时保留缓存。
4. 在时间线查看选中来源，或用已有「导出／上传数据给 Agent」导出或上传。

旧安装保留原三星来源、选择、缓存及别名；新安装默认 HC。两种来源分别记住类型选择。
切换来源不删除旧数据。图表和分析只使用当前来源；完整导出保留两个来源已存在的缓存，
分别放入 `samsung-records.jsonl`、`health-connect-records.jsonl`，manifest 0.4.0 标明当前来源。
同一测量可能被平台互相镜像，Agent 不应将两个平台记录简单合并相加。CGM 单个／多个
导出仍只含选中的血糖来源，不刷新或携带这两种平台数据及日志。

HC 已实现 18 类、27 种 record 类型：睡眠阶段、步数、心率序列、运动、皮肤温度基线和
变化量、血氧、热量和距离、爬楼、其他来源血糖、血压、体重／身高／体脂／骨量／瘦体重／
身体水分质量／基础代谢、体温和基础体温、饮水、营养、静息心率、呼吸频率、RMSSD、VO2 max。
系统不支持的皮肤温度会禁用；三星专有目标、能量分数、资料及运动轨迹没有伪装为 HC 能力。
身体成分等组内只获部分权限时读取已授权类型，并如实标记部分授权。

Health Connect 必须已存在其他应用写入的数据。开启读取不会使三星健康中未写入 HC 的
记录自动出现。没有额外历史授权时，保守请求最近 30 天；需要更早数据时勾选单独历史权限
并重新授权，实际可读范围仍由系统决定。此次没有请求后台健康读取权限。

## 记录完整性

- HC 使用官方分页和 Changes API。按平台 record ID、类型、provider 生成稳定本机别名，
  同一记录更新为 upsert。只处理平台明确返回的删除，不因空结果、撤权或失败清空缓存。
- 整个类型成功后，在同一数据库事务内提交记录、子序列及变更 token；中途失败保留旧记录和
  token。重启后可重新读取。token 不导出；过期或权限集合重建可能漏掉更早删除，保留明确
  的 `deletion_history_gap`，后续成功也不自动宣称该历史缺口已补齐。
- 两种平台分别使用健康缓存，HC 数据不进入 CGM 血糖表、日志或上传队列，排除当前及已知
  Jugg 包来源；原来的 CGM → HC 写入开关、增量触发及幂等规则未修改。
- UTC 绝对时间不随时区重复转换；保留原始 offset、完整 Instant 字符串与 epoch 毫秒索引。
  来源应用、recording method、SDK 类型和带单位原字段可追溯。
- HC 心率均值是采样点算术均值；睡眠区间、已识别睡眠、清醒和未分类时长分开；温度变化量
  不冒充绝对温度；身体水分 kg 不换算成 L；点测血氧仍为点测。未映射信息保持 partial。

## Watch8：查看、提醒与手机同步

使用配套 **Wear releasedub modernWear** APK，手机和手表应用包名均为
`tk.glucodata.ng.dub`，证书相同。这是 [Wear Data Layer 官方要求](https://developer.android.com/training/wearables/data/overview)，
普通 Release 与 DUB 不可混搭。现有国际版 DUB 可直接更新，不需要清除数据或结束传感器。

1. 先在 Galaxy Wearable 正常配对手表，然后将手表 APK 安装到手表。
   可用手表无线调试配对后执行 `adb -s 手表地址 install -r 手表APK路径`。
2. 手机 Jugg → 设置 → 数据交换，打开 Wear OS；两端保持可用 Google Play 服务。
3. 手表启动 Jugg → 设置 →「手机连接与同步」，刷新查看已连接设备和最新采样时间，
   点击「向手机请求同步」。已配对节点不等于对端 Jugg 已就绪，请以采样更新为准。
4. 手表按现有流程允许通知，在提醒页面设置血糖提醒；新增入口可打开系统通知设置。
5. 在可用的兼容表盘上添加 Jugg 血糖复杂功能。此构建保留应用、提醒、同步、复杂功能，
   移除旧式 WatchFaceService 与旧表盘编辑页。

[新版 Wear OS 的表盘使用 WFF](https://developer.android.com/training/wearables/wff)。本次没有创建
新的 WFF 独立表盘，也不宣称旧绘制服务在 Watch8 可用。应用启动、查看、复杂功能与同步
仍需真机确认。连接通道沿用项目现有实现；若地区版手机或手表没有可用 Google Play 服务，
此通道无法工作，本次没有开发替代传输协议。手动同步只发起请求，不显示为已完成传输。

## 构建与依赖

默认 SDK-free 手机构建即可使用 HC；三星读取仍按原方案由用户本地提供官方 SDK：

```sh
./gradlew :Common:assembleMobileReleasedub -PjugglucoAbi=arm64-v8a \
  -PsamsungHealthEnabled=true \
  -PsamsungHealthSdkPath=/absolute/path/to/official-sdk.aar
./gradlew :Common:assembleWearReleasedub -PjugglucoAbi=arm64-v8a -PmodernWear=true
```

`modernWear` 为可选 release manifest overlay，不改变普通旧手表构建默认行为。
手表没有三星 SDK。源码与文档只提交用户 fork 的既有开发分支；SDK、含 SDK APK、真实健康
数据及签名私钥不提交。签名证书延续既有 DUB，Google OAuth 登记无需因本次功能重配。

## 验证边界

已增加可运行回归：来源单选与持久化、来源选择分别保存、旧数据库兼容、缓存隔离和双流
导出、分页／重复／失败事务、权限声明与授权不足、更早历史限制、Changes 删除／更新／
过期、UTC+7 时间、心率／睡眠／温度变化量／质量与容量区分。既有 Wear 同步、并行传感器、
提醒与复杂功能测试随完整套件执行；真实旧导出另作回放校验。

实际执行结果及产物哈希见本地交付报告。本次没有连接 S23 Ultra 或 Watch8，不能宣称
真机 HC 授权与数据读取、手表通知／振动、断线恢复、待机持续同步、地区 GMS 和表盘兼容性
已验证。SDK 许可与开发模式约束沿用原实现。

官方读取与同步依据：[读取记录](https://developer.android.com/health-and-fitness/health-connect/read-data)、
[Changes 与 token](https://developer.android.com/health-and-fitness/health-connect/sync-data)。
