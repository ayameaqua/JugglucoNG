# 历史页按传感器浏览

历史页独立查询 Room 中的传感器数据，不再使用主页「主传感器优先」的显示合并结果。

- **单个**：初次进入时跟随主页主传感器，也可以在历史页指定其他来源，再切回「跟随主页」。历史页的选择不改变主页主传感器。
- **多个合并**：选择多个来源，各自的记录按时间排列。
- **全部合并**：包括已结束传感器与 CSV 导入来源。

合并不取平均值，也不按主传感器覆盖另一支 CGM 的数据。同一时刻不同传感器的两条记录均保留；同一物理传感器的存储别名仍按现有规则去除重复。鱼跃的 3 分钟与硅基的 1 分钟采样间隔各自保留。

每条记录显示来源名称／短标识及颜色圆点，血糖数值带相同来源的颜色。当前传感器的颜色取自主页使用的颜色分配和用户颜色设置；新增历史来源不改变当前传感器的颜色。已存在的 Health Connect 来源短标识直接复用，不新增第二套传感器编号。

列表的显示模式、软件校准、校准标记、趋势与变化量采用该记录自己的传感器上下文。图表逐传感器画曲线，使用来源颜色；不会把两个传感器的交错记录连接成一条曲线。历史页来源选择同时用于 CSV 和文本导出；三星整合页面的全量 Agent 导出继续保持原有行为。日记仍作为独立时间事件显示。

查询按图表窗口加实时尾段加载，日期范围的计数按所选来源计算，不要求一次加载全部血糖历史。时间戳始终为原始 epoch，展示和导出才按当地时区格式化。本次不改变数据库结构、CGM 参数、BLE 连接或上报机制。

## 验证

2026-10-10：SDK 不启用的 Mobile Debug 全量单元测试 3,743 项通过，包含历史数据库迁移；Mobile Release 最终相关测试 496 项通过。新增测试使用实际 Room 查询验证单个来源间隔、合并保留同一时间戳双记录、已结束来源、CSV 来源、别名、来源与日期计数及跨时区 epoch 不变；列表辅助测试验证默认跟随、手动选择、空选、多窗口去重、时间边界、独立趋势，以及增加历史来源或切换查看范围不改变当前传感器的来源颜色。

额外尝试 Release 全量测试时，9 个迁移测试因 Release 不打包历史 schema 测试资产而失败；这些迁移用例均已在 Debug 全量测试通过。项目现有构建配置明确将这些资产限定在 Debug，本次不改变 Release 包内容来适配迁移测试。

## 修改文件

- 数据读取：`HistoryBrowseData.kt`、`HistoryRepository.kt`、`HistoryDao.kt`、`HistoryReading.kt`。
- 页面与来源选择：`HistoryBrowserViewModel.kt`、`HistorySensorControls.kt`、`HistoryBrowseScreen.kt`、`MainNavigation.kt`。
- 来源颜色、图表、趋势与导出：`ReadingRow.kt`、`DashboardChart.kt`、`TimelineRows.kt`、`HistoryDataTransfer.kt`。
- 中文与默认英文文案：`Common/src/main/res/values-zh/strings.xml`、`Common/src/main/res/values/strings.xml`。
- 新增回归测试：`HistoryBrowseRepositoryTests.kt`、`HistorySensorBrowsingTests.kt`。
- 本说明：`docs/history-sensor-browsing.zh-CN.md`。

未在用户的 S23 Ultra、Watch8 或两支 CGM 上实机验证。应在更新后分别从主页选中硅基、鱼跃打开历史，再选择多个来源；核对来源颜色、实际采样间隔，以及同一时刻的两条记录。测试证明浏览查询不会隐藏另一支 CGM 的已存记录，并不能证明此前手机上缺失的硅基采样已经采集或补齐。
