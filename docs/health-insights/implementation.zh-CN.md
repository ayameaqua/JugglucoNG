# 综合健康数据第一版实施记录

2026-10-09。代码只在用户 fork 的 `feature/samsung-health-insights` 分支。
基于原有 `build/custom-dub-final`，保留 HC、Anytime 绝对时间锚点、带 offset CSV 和 BLE MAC 搜索工作。

## 页面和接入点

手机设置 →「健康数据与血糖」。默认显示联动时间线，另有分析、来源与授权两个分区。
开关默认关闭；选择类型、读取天数、请求授权后可以手动同步。进入页面距上次尝试超过
5 分钟时刷新；手动同步和导出不受该间隔限制。默认读取 30 天，支持 3/7/30/90 天和
1–3650 天自定义范围。已有缓存不因关闭开关、取消选中或权限变化而删除。

Samsung SDK 仅请求 READ；不写血糖、不读取通知、不创建 MCP 服务。
它的最终接入点是独立的 `HealthInsightCoordinator`：页面进入、手动同步、全量导出。
不从 HC 上传队列触发，也不把三星读取结果写回 HC 或 CGM 血糖表，避免回环。
Samsung 任务运行在独立 IO 协程和缓存数据库中。失败保留已有数据和成功游标，
不参与 BLE 采集事务或 HC 发送锁；下一次手动/页面刷新会重新读取失败类型。

SDK 1.1.0 的 25 个类型/授权入口全部有适配路径；实际可读仍由三星服务、设备、账号
和授权决定。常规记录分页读取，随后读取 changes；DELETE 明确删除对应父记录和子序列。
睡眠先读取，血氧/皮温额外读取睡眠关联；同 UID 直接读取和关联读取合并为一个记录。
关联读取后处理 changes，避免 DELETE 被较早的关联载荷复活。一个类型只有全部页面
成功才提交父记录、完整子序列和游标；失败的暂存数据不会出现在导出中。重复分页 token
会报失败，不无限循环。重启后可从上次成功范围重读，未承诺从一个类型的半页续传。

步数、活动汇总和目标走官方聚合接口；能量得分保留 SDK 本地日期；个人资料走专用接口。
位置和个人资料默认不选中，位置未单独授权时会从运动载荷移除。只映射已确认的单位，
其余全部公共字段及嵌套序列保留在 attributes，并标明 normalization=partial。
序列仅在 SDK 提供绝对时间时拆出；无绝对时间的数组保留原结构，不伪造测量时间。

## 一次导出包含什么

2026-10-10 增加「导出／上传数据给 Agent」统一页面：本地保存、Google 账号连接与 Drive
手动上传共用范围选择，支持全部整合数据，或指定一支/多支 CGM。Google 个人项目配置、
私人目录、可读文本与上传验证边界见 [Google Drive 使用说明](google-drive.zh-CN.md)。
仅指定 CGM 时不刷新三星、不包含其他传感器、三星或日志；下文描述的是全部整合数据。

全部整合数据先等待已启用来源刷新结束，随后固定各源快照并生成 ZIP。
三星缓存锁之后分页读取 Room，用独立只读连接的 data_version 验证期间没有并发提交；
发生更新会丢弃本轮输出重读，连续三轮繁忙则报错，不交付混合快照。导出不持有 Room
写事务、不改变 journal mode、不暂停采集。manifest 明确说明不是跨数据库同一原子事务。
浏览页面的 24 小时曲线、7 天/150 条列表限制不会影响导出。
manifest 列出每个文件的字节数、SHA256 和 JSONL 行数，便于核验数据完整性。

- `glucose.jsonl`：所有传感器、所有来源、所有本地血糖记录；Auto 和 Raw 分开，mg/dL。
- `recorded-display.jsonl`：已有实际显示值、显示通道和校准指纹，避免再套显示校准。
- `uncertainty.jsonl`：已有血糖可信区间及置信信息。
- `samsung-records.jsonl`、`series.jsonl`、`relations.jsonl`：全部已缓存类型及完整子序列和关联。
- `journal.jsonl`：全部本地日志，包括备注、宏量营养字段和每次胰岛素的曲线快照等。
- `manifest.json`、概要、README、record/series schema、字段目录和能力清单。

「全部」是全部已缓存有效来源记录；不是未授权数据或三星账户全历史。导出只刷新用户
选定的同步范围，不暗中扩大范围或申请权限。manifest 保留失败、关闭、未安装、未授权
和读取范围状态。旧缓存仍可导出不等于本次刷新全部成功。
用户通过系统文件保存界面保存到 Download，随后由文件管理器分享给 ChatGPT/Agent。

时间以 UTC Instant/epoch 为比较依据，保留三星原 offset；CGM 未知 offset 为 null。
切换手机时区只影响显示，不对历史 +1h 或 -75s。每日汇总保留 calendar_basis/query_timezone，
不当作连续采样。公开记录/设备/传感器 ID 是安装范围 HMAC 别名；不导出 BLE 凭据、签名
密钥或 SDK 二进制。用户自己输入的备注仍是健康导出的内容。

## 可选 SDK 构建与个人使用

普通构建默认 `samsungHealthEnabled=false`，SDK adapter 替换为不可用实现；Wear 不含 SDK。
个人 phone 构建：

```sh
./gradlew :Common:assembleMobileReleasedub -PjugglucoAbi=arm64-v8a \
  -PsamsungHealthEnabled=true \
  -PsamsungHealthSdkPath=/absolute/path/to/samsung-health-data-api.aar
```

SDK AAR 必须自行向三星获取并接受许可；缺少本地文件会明确失败。
本地 AAR 没有 Maven POM，构建显式加入其 Parceler 所需 Kotlin runtime，R8 保留公共 DTO
及 field/getter 名称。SDK 变体仅手机 minSdk=29；普通 phone/Wear 原 minSdk 不改变。

[官方要求](https://developer.samsung.com/health/data/overview.html)：Android 10+、Samsung Health
6.30.2+，不支持模拟器。个人测试需在三星健康开启 Data Read 开发者模式，再为
**JugglucoNG DUB 包 `tk.glucodata.ng.dub`** 请求读权限。Probe 的授权不会迁移到这个包。
版本检查之后真实 getStore/授权/读取仍可能因地区、签名或合作资格失败，页面如实显示原因。
[开发模式](https://developer.samsung.com/health/data/guide/developer-mode.html) 限定测试/调试；
公开分发需遵循三星合作和许可流程。本仓库与 Actions 只构建不带 SDK 的默认变体；
SDK ZIP/AAR/JAR 和含 SDK APK 均不得上传 GitHub。

## 验证与已知边界

独立 Probe 已有六类真实读取证据（S23 Ultra/Watch8），不等于此次 Juggluco 集成真机通过。
这次执行 JVM/Robolectric 回归、真实 Room/缓存全量 ZIP 生成、JSON schema 验证、arm64 R8
release 构建和签名/ABI 检查；测试替身验证未安装、拒绝权限、关闭、失败重试和去重。
具体数量、构建产物和日志见本次交付报告。

当前图表是 24 小时血糖曲线与睡眠/运动区间，列表展示其他指标，统计按各血糖来源分别
计算区间均值/范围/点数。完整设计中的独立多指标轨道、可拖动共同游标、事件前后自定义
比较窗口、暂停按钮和主导航快捷入口尚未实现。没有内置医学判断或因果解释。

仍需用户 S23 Ultra 实测：DUB 包 SDK 授权、各可用类型/地域能力、完整嵌套序列、读取耗时、
保存/分享和 ChatGPT 对真实 ZIP 的读取。未接入这台手机，不宣称已完成真机兼容性验证。
