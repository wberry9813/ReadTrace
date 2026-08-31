# 阅迹壁纸 ReadTrace · BOOX 修复测试版

这是 ReadTrace 的非官方 BOOX 修复测试分支，面向文石电纸书的实际使用场景，重点改善壁纸更新滞后、阅读时长口径、无关书籍混入统计，以及墨水屏上数值控件难以精确操作的问题。

当前测试版本：[`pr-2-test.1`](https://github.com/YuJiahao1015/ReadTrace/releases/tag/pr-2-test.1)

当前发布分支：[`boox-reliability-test`](https://github.com/YuJiahao1015/ReadTrace/tree/boox-reliability-test)

上游 PR 分支：[`fix/reading-accuracy-refresh-reliability`](https://github.com/YuJiahao1015/ReadTrace/tree/fix/reading-accuracy-refresh-reliability)

上游合并请求：[`wberry9813/ReadTrace#2`](https://github.com/wberry9813/ReadTrace/pull/2)

## 项目关系与致谢

本 Fork 基于原项目 ReadTrace 继续调试，不代表原作者的官方发布版本。

- 原项目作者：[wberry9813](https://github.com/wberry9813)
- 原项目：[wberry9813/ReadTrace](https://github.com/wberry9813/ReadTrace)
- 本 Fork 维护者：[YuJiahao1015](https://github.com/YuJiahao1015)
- 本 Fork：[YuJiahao1015/ReadTrace](https://github.com/YuJiahao1015/ReadTrace)

ReadTrace 的产品设计、基础功能和原始实现来自原作者。本 Fork 目前只用于提交修复、分发真机测试包和收集验证结果；正式版本请以上游仓库为准。

## 本测试版改进

- 阅读事件严格按完整路径归属；只有文件名唯一时才允许跨等价存储路径匹配，不再把空路径、未知路径或同名歧义事件猜到最近打开的书上。
- 书单、图表和总阅读时长复用同一批已归属事件，减少总时长与书单对不上的情况。
- 被筛选书籍不会再以默认“在读”状态回流到书单或月历。
- 微信读书仅在完整自然月使用月度排行；当天、周、最近 N 天等周期使用日级统计和本机快照差分，避免无关书籍混入。
- 本地与联网刷新使用独立任务；同时监听 NeoReader 元数据和阅读统计变化，并改进精确定时、前台服务及重试行为。
- 壁纸先写入临时文件，完整落盘后再原子替换，减少屏保读到半张图片或旧文件的概率。
- 数值滑杆替换为适合墨水屏的大触控步进器，支持加减、常用预设和点击数值精确输入。
- 设置预览增加 `800ms` 防抖，图片生成、保存和调试查询移到后台线程；普通排版调整不会反复重启自动刷新任务。
- 页面选中状态改用圆点标记，减少大面积黑底反白造成的墨水屏残影。

## 主要功能

- 数据来源：NeoReader 本地记录、微信读书，以及两者合并的混合来源。
- 统计周期：当天、昨天、本周、上周、本月、最近 7 天、最近 30 天和自定义日期。
- 壁纸类型：阅读账单、摘录菜单、当前阅读封面、自动封面优先和月历封面墙。
- 内容选项：阅读时长、书单、进度、作者、图表、备注、条码和自定义字体。
- 自动刷新：每日定时或熄屏触发，生成后覆盖同一张壁纸文件。
- 设备尺寸：内置 Poke、P6、Palma、Leaf、Page、Note、Tab、T10、T13 等 BOOX 预设，也支持自定义宽高。

## 下载与安装

从 [Fork 测试版 Releases](https://github.com/YuJiahao1015/ReadTrace/releases) 下载 APK：

- [`app-arm64-v8a-debug.apk`](https://github.com/YuJiahao1015/ReadTrace/releases/download/pr-2-test.1/app-arm64-v8a-debug.apk)：推荐近年的 64 位 BOOX 设备。
- [`app-armeabi-v7a-debug.apk`](https://github.com/YuJiahao1015/ReadTrace/releases/download/pr-2-test.1/app-armeabi-v7a-debug.apk)：仅在 arm64 版本提示不兼容时尝试。

> [!WARNING]
> 这是 Android Debug 签名 APK，通常不能覆盖安装原作者发布的正式版。请先备份应用配置；如果系统提示签名冲突，需要卸载原版后安装，而卸载可能清除应用数据。

测试包基于提交 [`5ce841b`](https://github.com/YuJiahao1015/ReadTrace/commit/5ce841b2be48e72818c55d9a5ad97b1a82fae3ed)，应用内版本号仍显示 `1.0.4`。请以 Release 标签和提交号确认测试版本。应用内“检查更新”仍指向原项目 Release，这是当前测试包的已知行为。

安装步骤：

1. 下载适合设备的 APK，并在 BOOX 浏览器或文件管理器中打开。
2. 如果系统拦截安装，为当前浏览器或文件管理器开启“允许安装未知来源应用”。
3. 打开“阅迹壁纸”，按提示授予文件和图片访问权限。
4. 在 BOOX 应用优化或冻结管理中解除冻结，并允许后台运行。

## 首次使用

1. 在设置页确认“阅读器尺寸预设”；匹配不到时选择自定义分辨率。
2. 选择统计周期、数据来源和壁纸类型。
3. 使用微信读书或混合来源时，填写 API Key 并先测试连接。
4. 点击“刷新预览”，核对书单、总时长和图片尺寸。
5. 点击“生成壁纸”，将图片保存到固定位置。
6. 在 BOOX 屏保或壁纸设置中选择生成的图片。

默认输出路径：

```text
/storage/emulated/0/Pictures/NeoReader/neoreader_wallpaper.png
```

后续自动刷新会覆盖同一路径，不需要重新选择屏保图片。

## 数据来源与统计口径

### NeoReader

读取文石系统的本地元数据和阅读统计 Provider，适合离线使用。NeoReader 通常在退出阅读会话后才写入最新进度、时长和当前书籍，因此刚读完后应先退出书籍，再刷新或锁屏。

本测试版只统计能够可靠归属到书籍的阅读事件。没有路径、路径未知、同名文件存在歧义或被当前筛选排除的事件，不会进入书单、图表和总时长；生成摘要及日志会记录未归属原因。

### 微信读书

需要联网和 API Key。每日总时长来自接口的日统计；安装后的日级书籍记录通过书架和排行快照差分逐步积累。首次同步只建立基线，不会把历史累计阅读量全部算到当天。

微信接口无法完整还原安装应用之前每天具体读了哪些书，因此只有阅读总时长、没有可确认书籍的日期不会使用月度排行猜测封面。

### 混合来源

把 NeoReader 与微信读书的时长和书单合并。联网失败时继续使用本地数据和已有缓存，不让整张壁纸生成失败。熄屏瞬间不会发起网络请求；联网数据通常在解锁后或每日定时任务中更新，下一次锁屏显示新图。

## 墨水屏交互

数值设置使用以下三种方式，避免依赖触控不灵敏的细滑杆：

- 点击 `−` 或 `+` 按固定步长调整。
- 点击常用预设值快速选择。
- 点击中间的当前数字，直接输入精确数值。

连续修改参数时，应用会在停止操作约 `800ms` 后生成一次预览。旧的后台预览结果不会覆盖后续的新设置。

## 自动刷新与权限

自动刷新支持“每日定时”和“熄屏触发”：

- 每日定时更省电；Android 12 及以上可以在应用内打开精确定时授权页，未授权时会安全降级为系统允许的非精确任务。
- 熄屏触发更及时，但会增加唤醒和耗电；设置中的最小间隔用于限制频率。
- NeoReader 在退出书籍后才可能落库，因此本次锁屏仍是旧图、下一次锁屏更新不一定是任务失败。
- 请确保应用未被冻结，并允许开机启动、后台运行和必要的前台服务通知。

应用可能申请文件/媒体访问、所有文件访问、开机启动、前台服务和精确定时权限。微信读书、混合来源及版本检查会访问网络；NeoReader 本地记录和本地封面不会上传到本 Fork。

## 常见问题

### 下载项看不到 `.apk` 后缀

Release 页面可能优先显示资产标签，真实文件名仍以 `.apk` 结尾。下载响应也是 Android APK 类型，可以正常安装。

### 无法覆盖安装官方版

测试 APK 与官方版签名不同。请先备份设置，再卸载官方版并安装测试版；不要在没有备份时直接卸载。

### 壁纸没有及时更新

先退出当前书籍，打开应用点击“刷新预览”，确认新数据已经出现，再锁屏测试。随后检查应用是否被冻结、自动刷新是否开启、熄屏最小间隔是否尚未满足，以及每日定时是否获得精确定时权限。

### 阅读时长或书单不符合预期

先把时长显示单位切换为分钟，并选择明确的统计周期。查看生成摘要和日志中的未归属事件数量；本测试版会舍弃无法可靠确认书籍的事件，因此总时长可能低于系统中包含未知事件的原始累计值，但不会把这些时长错误显示到无关书籍上。

### 微信或混合月历只有时长，没有封面

首次同步只建立快照基线。继续正常使用并在联网状态下完成至少两次同步后，应用才能通过差分确认当天读过哪些书。无法确认书籍的历史日期只显示时长属于预期行为。

### 页面操作仍然迟钝

在 BOOX 应用优化中优先使用适合表单操作的刷新模式，并关闭不必要的动画增强。数值设置优先使用预设或直接输入，避免连续快速点击。

## 调试日志

遇到问题时可导出：

```text
/storage/emulated/0/Download/neoreader_debug_log.txt
/storage/emulated/0/Download/neoreader_auto_refresh_log.txt
```

反馈前请删除 API Key、书名、设备标识和个人文件路径等敏感内容。测试反馈可以在[上游 PR #2](https://github.com/wberry9813/ReadTrace/pull/2) 留言，并注明设备型号、Android 版本、数据来源、统计周期和复现步骤。

## 开发构建

开发环境：JDK 17、Android SDK 36、Gradle Wrapper 8.14.3。

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Debug APK 输出位置：

```text
android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

提交修复前应至少验证：书单与总时长口径、无关书籍过滤、连续设置操作、手动预览、壁纸保存、NeoReader 退出书籍后的刷新、熄屏触发、每日定时，以及微信快照差分。

## 贡献与反馈

本测试版的 BOOX 稳定性反馈请集中提交到[上游 PR #2](https://github.com/wberry9813/ReadTrace/pull/2)。如果问题在原项目最新版中也存在，可以同时在[上游仓库](https://github.com/wberry9813/ReadTrace)搜索现有 Issue。

感谢原作者 [wberry9813](https://github.com/wberry9813) 提供 ReadTrace 的项目基础，也感谢 [Ela0Li/ReadTrace-hanvon](https://github.com/Ela0Li/ReadTrace-hanvon) 提供兼容版 README 的组织方式参考。
