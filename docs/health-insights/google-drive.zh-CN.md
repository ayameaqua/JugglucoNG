# Google Drive 上传与范围导出

入口：设置 → 健康数据与血糖 → **导出／上传数据给 Agent**。

同一个范围选择用于手机保存和云盘上传：

- **全部整合数据**：所有 CGM 的全部有效本地历史、Auto/Raw、已记录显示值、可信区间、来源与已知佩戴周期、本地日志，以及三星健康已同步的完整本地缓存与子序列。先尝试刷新三星；失败、无权限或关闭读取时仍导出缓存，manifest 保留真实失败/跳过状态。
- **指定 CGM**：勾选一支或多支本机有历史的传感器，保留这些来源的全部本地历史及上述血糖通道/来源信息。排除其他 CGM、日志与三星记录；不调用三星 SDK 刷新。可复用发射器的多个已知佩戴周期通过 wear_session_alias 区分，未知归属仍为 null。

这不是 App 设置/还原备份；不会上传配对凭据、Google 令牌或三星 SDK。原来的备份与还原功能保持独立。时间戳始终使用原始 UTC epoch，不按当前手机时区平移，不重复套用显示校准。

## 第一次配置你的 Google Cloud 项目

此 APK 沿用当前 DUB 的包名和签名，以便覆盖安装时保留 App 数据。Google 按包名与签名识别 Android OAuth 应用。

1. 打开 [Google Cloud 控制台](https://console.cloud.google.com/)，新建一个个人测试项目。
2. 在 API 库启用 [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com)。
3. 在 Google Auth Platform 填写 Branding：应用名、支持邮箱、联系人邮箱等必填信息。控制台要求主页、隐私政策时提供自己可管理的真实网页；仓库中的说明也可用于个人测试的文档链接，公开发布需按 Google 要求完善。
4. Audience 选择 External、Testing，添加你将用于手机上传的 Google 账号为测试用户。如果是 Workspace 账号，另检查组织的第三方授权限制。
5. Data Access 添加唯一请求的范围 `https://www.googleapis.com/auth/drive.file`。这是应用创建/明确授权的文件访问范围，不请求整个云盘、通讯录或隐藏的 appDataFolder。
6. Clients → Create client → Android。填写：
   - 包名：`tk.glucodata.ng.dub`
   - SHA-1：`75:A5:58:68:29:2A:B6:39:F0:A3:C2:A4:7A:C7:36:68:72:9D:5D:22`
7. 保存后回 App，点「连接 Google 账号」，选择同一测试账号并允许上传授权。App 从 Drive 的 about.user 获取账号显示信息。

当前实现只在手机上使用 Google AuthorizationClient 获得短期 access token；**无需服务账号、client secret、Web OAuth 客户端或自行架设服务器**，也无需把 Android client ID 填进 App。包名/签名变更时需要相应 Android OAuth 客户端；页面内「首次使用」显示实际安装 APK 的包名与 SHA-1，优先核对该值。

本次沿用的是 DUB 测试签名；他人自行构建应登记自己使用的包名/签名。三星 SDK 仍需自行准备，并按既定文档启用个人开发者读取授权；普通 SDK-free APK 同样可以导出/上传已有血糖数据。

## 云盘内容与 ChatGPT

登录本身不上传健康数据。点「上传所选范围到 Drive」才会生成并上传一次快照：

- 私人「我的云盘 / JugglucoNG Health」目录。不会创建公开分享链接；之前已共享的同标记目录不用于新的导出。
- 每次操作有独立的快照子目录。上传中名为「未完成-…」；全部文件校验并上传索引后才移除前缀。之前的快照不被覆盖。
- 原始 ZIP 保留规范 JSONL/schema/manifest/inventory。另上传 `text/plain` 的 `.txt` 视图：JSONL 按完整 UTF-8 行分段，通常每段不超过 4 MiB；一条较大的记录完整保留，可超过该分段目标，超过 32 MiB 则此次文本准备失败。
- `START_HERE.txt` 最后上传，包含格式说明、原文件/分段对应、SHA-256 和各文件的授权访问链接。

在 ChatGPT 授权连接同一个 Google 账号，再让它读取本次目录中的 START_HERE.txt 和相应数据分段。Google Drive 私人文件可以通过授权访问；安装插件本身不等于授权。该 App 创建普通 My Drive 文件，方便该账号另行授权的 ChatGPT 连接访问；不使用只有本 App 可见的隐藏存储。

**尚需真实验证**：当前账号是否能通过 Google 授权、上传后的文件是否被你使用的 ChatGPT Drive 连接检索/读取、索引多久更新、是否能完整读取多段。文本视图提高可读性，但不承诺连接器会自动解压 ZIP 或完整遍历目录；应核对 manifest 记录数和所有分段。

## 中断、重试与断开

上传在独立的协程/IO 工作中进行，不改变 CGM 采集或 Health Connect 写入。不自动在后台持续上传；应保持此页面所在 App 存活。旋转屏幕保留当前任务；退出页面/进程退出后任务停止，下次手动上传生成新快照。

每个文件预先取得唯一 Drive ID，同一次上传重试继续使用该 ID。网络/限流重试有上限；断块时查询服务器已提交字节数再继续；会话失效时有界重建。提交成功但响应丢失时，409 后读取同一 ID 并校验 size/MD5，避免重复文件。仅全部核对成功才报告完成。401 清除过期令牌缓存并提示重新授权。取消或失败保留未完成云盘目录，可自行删除。

access token 只存在内存中，不写入偏好、日志或导出；本机偏好只记住上次账号 ID/邮箱/显示名。每次上传重新向 Google 请求当前授权。切换账号会验证新账号身份，目录查询按当前账号进行；断开仅清除本机账号记忆，已有云盘文件保留。需要撤销 Google 侧授权时到 [Google 第三方应用管理](https://myaccount.google.com/connections)。

## 官方依据

- [Android 用户数据授权与 Android OAuth 包名/SHA-1 配置](https://developer.android.com/identity/authorization)
- [Drive 权限范围](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Drive resumable upload、预生成 ID 与错误恢复](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Drive about.get 与 drive.file 授权](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get)
- [ChatGPT 插件授权说明](https://learn.chatgpt.com/docs/plugins)

上述依据用于实现选择；单元测试使用本机数据库和模拟 Drive 协议，不能替代个人 Google 账号/手机/ChatGPT 实测。

2026-10-10 验证：新增 16 个回归用例。SDK-free 完整 Mobile Debug 3,772 项、Wear Debug
2,860 项均通过（零失败、错误、跳过）。专项健康/导出/Drive 测试共 29 项通过。
生成的合成全量 ZIP 含 1,005 条血糖、1 条三星记录、1 条日志和 1 条子序列；1,008 个
record/series 通过 JSON Schema 校验，15 个数据/说明文件的清单字节数、SHA-256、行数及
ZIP CRC 核对通过。这些验证没有登录用户 Google 账号，也没有向真实云盘上传健康数据。
