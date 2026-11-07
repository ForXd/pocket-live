# 0.32.0 搜索与分类返回位置恢复

## 原因与修复

在实际 Pocket 页面中，搜索结果滚动到第 12 条，进入详情、开始播放、返回选集再返回搜索：原版本测试在 `anchor.assertIsDisplayed()` 失败。只修复列表回顶后，第 12 条恢复，但其屏幕坐标从 168dp 变为 232.38dp，证明搜索栏仍强制展开。

- 原有 `rememberLazyGridState` / `rememberLazyListState` 已由固定页面槽位的 SaveableStateHolder 保存位置，但页面重新进入时 `LaunchedEffect` 会再次执行 `scrollToItem(0)`，覆盖恢复值。
- 新增 `ScrollToTopOnChange`，保存最后应用的筛选/排序标识，只在标识实际变化时回顶。应用于关键词搜索、分类搜索和推荐，仍保留主动换筛选或排序时回顶的行为。
- 搜索栏折叠状态移到播放分支之外，保存两个浮点数；详情进入/返回不再触发展开。切换首页分区、底部导航或键盘状态时仍正常展开。

## 内存与性能边界

继续使用现有结果与两个固定页面槽位（results / recommendations），不按影片、关键词或历史导航追加缓存。新增保存内容只有筛选标识和折叠高度，不含影片列表、图片、View、Activity、播放器或 Context。滚动位置仍由 Compose 的标量 Saver 保存；离开列表后不保留其 Composition。协程依旧属于页面生命周期，播放器释放路径未改动。

新回归覆盖真实搜索、选题材及排序、滚动、详情、播放、返回，连续往返三次：核对原条目的屏幕坐标、收起的搜索栏、筛选/排序与查询请求数。每次返回还等待播放器创建/释放计数归零、采样任务归零及媒体请求归零。这验证指定路径的资源回收，不代表对所有设备做完整堆泄漏证明。

另覆盖分类列表播放返回的位置与请求数、推荐切换页面返回的位置，以及主动改变排序后仍回到顶部。

发布目标 0.32.0 / versionCode 39。构建和正式升级结果在验收后补充。

## 正式发布验证（2026-11-07）

- 源码提交 `8f54f4463040c9e79aa082f792a58c818d32ad71`，从该提交构建；121 项单元测试通过，release lint 0 错误、46 项警告。
- 最终设备回归 `OK (16 tests)`：HomeBrowse 7、CatalogRegression 5、DiscoverySort 1、UiLayout 1、ApkUpdater 2。新增往返测试验证三次播放后的准确位置、筛选、排序、请求数和播放器/采样器/媒体请求释放；排序变更仍正常回顶。原始失败已被相同路径的新断言覆盖。
- 正式 APK 包名 `org.streampocket`，版本 0.32.0（39），证书 SHA-256 `7dd2deb1eb4668ae56f830f09ba9c0a5a322763392d8846074c1da2daef02b7f`，沿用原证书。
- APK SHA-256 `ad0d13623d2ef2a0f6e7497cc794c71dd7b315d2abecb0a1c4fa20d919d2e80e`；公开下载 APK 与本地签名构建逐字节一致。匿名/认证 GitHub latest API 与公开 latest/update.json 均为 v0.32.0。
- API 37 模拟器从正式 0.31.0 应用内下载并通过系统安装器更新到 0.32.0（39），`PublishedUpgradeDeviceTest: OK (1 test)`。重开版本正确，firstInstallTime 保持 `2026-10-28 22:23:52`，lastUpdateTime 为 `2026-11-07 20:08:09`；未卸载或用 ADB 替代正式升级流程。
- 公开发布页 https://github.com/ForXd/pocket-live/releases/tag/v0.32.0 。验证上一版附件仍可下载后，只保留 v0.32.0、v0.31.0、v0.30.0 三个 Release；旧 Git tag 保留。
