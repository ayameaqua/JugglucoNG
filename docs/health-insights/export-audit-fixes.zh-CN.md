# 2026-10-12 导出数据审计与修复

本次依据真实 Drive 快照和独立 ChatGPT 读取结果，修复 fork 的
`feature/samsung-health-insights`。SDK、私人快照和含 SDK APK 不上传 GitHub。

| 项目 | 判断与处理 |
| --- | --- |
| 来源目录重复、历史别名缺项 | 明确导出缺陷，已修复。目录按 Room 实际存储别名生成，而不是按解析后的设备名覆盖。 |
| 同一设备多个别名共用 source UID / lookup ID | 可以合理存在。新增 canonical_sensor_alias、identity_basis、known_wear_sessions，明确设备、存储别名、探头周期的不同含义；不把共享设备 ID 当成不同探头的证据。 |
| 显示分钟与采样秒/毫秒无法精确关联 | 明确关联缺陷，已修复。保留分钟桶，在唯一同别名、同分钟候选时导出 source_record_id 与原始 source_timestamp_epoch_ms；缺失/多候选保持 null。association_basis 说明这是导出时推导的关联，不冒充当时捕获的采样 ID。 |
| 早期 Anytime Auto / Raw 差距大 | 需核查语义，不能仅凭分布判为错误。当前 CT4 Raw 是线性电流估计，Auto 为 MK4 MODEL；导出不重复转换。旧 Room 行没有逐样本算法版本/参数，明确输出未知，不用当前 QR 或默认值填充旧历史。 |
| 三星标准 metrics 不全 | 补齐已明确单位的身体成分、运动 session/log、活动汇总/目标、血氧/心率/皮温及子序列。每项记录 SDK 路径、官方单位依据与转换。未映射原文继续保留，仍为 partial。 |
| 点测血氧标为区间均值 | 已修复：绝对开始/结束 Instant 相等为 point / instant；非空区间保留 interval / mean，min/max 独立。 |
| 睡眠 duration 被当成睡眠时间 | 已修复：record_interval_duration、sdk_session_duration 与 estimated_asleep_duration / estimated_awake_duration 分开。LIGHT/DEEP/REM 计入已识别睡眠，AWAKE 计入清醒，未知阶段和缺口计入 unclassified_duration；重叠/越界不求和。明确为设备估计。 |
| 初始异常值和大跳变没有质量提示 | 加 first_available_record、early_known_wear、abrupt_auto_change_review。仅用于复核，保留原始数值，不自动排除，不作医学判断。按真实时间排序，与插入/回填顺序无关。 |
| success 但是否有数据不清晰 | 返回上次成功提交的去重 returned_count、deleted_count、success_no_records / success_with_records 和缓存数量、时间/本地日期覆盖。旧缓存迁移不猜上次返回数；失败/拒绝权限仍保留已有缓存。 |

## 兼容与保护

包 manifest 升为 0.3.0；record / series envelope 保持 0.1.0，新增字段位于允许扩展的
attributes / metrics 或独立目录、显示流。旧导出中的 sleep.metrics.duration 没有可靠的
“实际睡眠时长”含义；新包改用上述明确名称，消费者应检查 manifest 版本。

三星缓存数据库仅通过 1→2 添加两个可空计数列，保留 records、series、relations、同步
游标和设置。既有缓存的标准映射在快照读取时补齐，不覆盖缓存原始载荷、不扩大授权范围。
新读取也使用同一标准映射。空聚合响应不再创建一条虚假的零值记录；既有空载荷仍保留。

不迁移或重写血糖历史/显示表，不重新校准，不更改 epoch、不做固定 +1h 或 −75s。
不改变 BLE 握手、连接、CGM 算法、物理传感器生命周期或 HC 发送。默认参数及历史 QR
变动可能解释部分 Raw 差异，但没有逐样本证据就不能断言具体根因。

质量阈值是一项可追溯导出策略：同一存储别名且没有已知佩戴边界，间隔不超过 5 分钟，
Auto 绝对差至少 90 mg/dL、速度至少 10 mg/dL/min 才标记复核。已知佩戴最初 30 分钟标
early_known_wear；首次可用记录不代表物理传感器刚启动。这些阈值不是医疗有效性判据。

## 官方字段依据

- [身体成分：质量、百分比、身高、体水分单位](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-body-composition-type/index.html)
- [运动 session：Duration、kcal、m、心率、功率、速度](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-session/index.html)
- [运动日志：bpm、m/s、W、cadence](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-log/index.html)
- [睡眠会话：睡前至醒来区间，不等于排除清醒后的时长](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-sleep-session/index.html)
- [血氧：百分比与连续子序列](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-blood-oxygen-type/index.html)
- [活动时长：Java Duration](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-activity-summary-type/-companion/-t-o-t-a-l_-a-c-t-i-v-e_-t-i-m-e.html)

## 验证范围

包含目录别名/共享设备身份、分钟关联的唯一/歧义/缺失/跨来源、乱序插入、质量复核、
缓存保留及迁移、已知单位、Duration 纳秒、点测/区间、阶段缺失/未知/重叠的回归测试。
可选私有快照回放测试由本地 system property 指定，不提交真实健康数据。
离线回放通过不等于新 APK 已在手机上完成更新、SDK 同步与再次上传；须区分这两种证据。

本地验证结果：63 项针对性测试（含真实私人快照离线回放）通过；完整手机 3798、手表
2860 项，共 6658 项，失败、错误、跳过均为 0。快照回放保留 12550 条血糖、2918 条三星
主记录、95204 条子序列、11191 条显示记录和 68 条关联；数值与原始时间不变。
回放环境通过原快照中的来源映射模拟设备解析，不等于实际手机原生身份注册过程再次验证。
私人输出和测试日志位于仓库之外的本地 artifacts，不上传到 fork。
