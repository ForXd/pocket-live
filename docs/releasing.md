# Android 正式版发布与应用内升级验收

## 发版流程

1. 从当前 GitHub 最新正式版记录 tag、包名、versionName、versionCode 和签名证书 SHA-256。在专用测试设备上安装这份旧版正式 APK，保留到发布后做应用内升级。另留一台完整走过上一次系统安装流程的设备，用于连续升级。
2. 更新 `app/build.gradle.kts`。包名为 `org.streampocket`，versionName 与计划发布的 tag 一致（tag 加 v），versionCode 严格大于上版。提交代码后用该提交构建，运行单元测试、更新器设备测试和 release lint。签名使用原 release 密钥，密码和密钥文件留在仓库之外。
3. 对最终签名 APK 运行 `apksigner verify --verbose --print-certs` 和 `aapt dump badging`。逐项核对包名、包内版本、证书与预期值。用同一份 APK 生成 SHA256SUMS，并运行 `python3 scripts/release-manifest.py VERSION APK --notes NOTES --out update.json`。核对清单里的版本、URL、字节数和 SHA-256。
4. 在 GitHub 为该提交建立草稿 Release，上传版本化 APK、SHA256SUMS 和 update.json 后再发布为 Latest。下载发布页的附件重新验哈希，同时检查匿名 GitHub latest API 和 `/releases/latest/download/update.json` 都指向同一个版本。需要修正已发布的 APK 时，递增版本并发新 tag。
5. 用下一节的测试从旧版正式应用点更新。从授权、下载、安装到重新打开全程保留应用数据。首次安装来源授权再单独手动跑一次。有兼容问题的厂商机型，应在其系统安装器上重复确认。保存设备型号、系统版本、测试结果和两个 APK 的身份信息。
6. 验收通过后按发布时间保留最近三个 Release。清理历史附件前确认当前 latest 可用、上一个正式版仍可下载。验收失败时保留出错包和日志，暂停清理，修正后重新验证。

更新器设备测试 `ApkUpdaterDeviceTest` 需要在启动 instrumentation **之前**执行 `adb shell appops set org.streampocket.devtest REQUEST_INSTALL_PACKAGES deny`。Android 会在撤销该权限时终止目标 UID；若上轮测试留下 allow，再在测试进程内撤销，会中断测试。该前置步骤只作用于 debug 测试包，正式应用的真实升级仍使用下面的 allow 设置。

## 发布后验证命令

在专用模拟器设置 ANDROID_SERIAL 为目标设备。示例测试的是已公开的 0.22.0 → 0.23.0；下次替换下列三个版本参数。目标应用须已是 fromVersion，设备可以访问 GitHub，安装器按钮使用中文或英文。下面的 install 命令仅安装测试驱动，正式目标包由旧版应用下载并交给 Android 安装。

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set org.streampocket REQUEST_INSTALL_PACKAGES allow
adb shell am instrument -w -e publishedUpgrade true \
  -e fromVersion 0.22.0 -e toVersion 0.23.0 -e toCode 29 \
  -e class org.streampocket.PublishedUpgradeDeviceTest \
  org.streampocket.devtest.test/androidx.test.runner.AndroidJUnitRunner
```

要求输出 **OK (1 test)**，再检查系统包信息。测试确认旧版页面显示目标版本、触发实际下载、在系统安装器中点击更新，并断言包管理器返回目标 versionName 和 versionCode、firstInstallTime 未变。最后重新启动正式应用，读取其更新页版本。直接用 ADB 安装新版或只看到系统确认窗口，都没有覆盖完整路径。

## 0.22.0 → 0.23.0 排查记录

用户反馈在系统安装窗口点更新后提示 0.22.0 已安装，而应用显示下载的是 0.23.0。没有原设备当时的安装会话、待安装文件或系统日志，无法确定该安装器当时识别了哪一份文件。

- API 37 独立模拟器上，从公开 0.22.0（28）经真实 GitHub 下载、首次授权、系统确认，包管理器读到 0.23.0（29）。自动回归还验证了首次安装时间和重开后的版本。
- 连续升级对照中，在隔离设备上为原版 0.21.0 预置真实 0.22.0 的已验证下载夹具，让系统安装 0.22.0。接着由 0.22.0 在线下载 0.23.0。系统确认窗口出现时从更新器共享目录取出实际 APK，与公开附件逐字节一致，哈希为 `c0247505dc8c448564904e598914ba4631554ac3a1268b879caf4bf701bb50e8`。第二次覆盖升级也成功。
- 两个旧版本都把校验后的文件放在 `updates/ready/update.apk`，因此给安装器的 content URI 完全相同。已验证地址复用；厂商安装器是否因复用而展示旧信息仍待设备证据。现在用版本和完整 SHA-256 构成文件名与 URI，同时兼容旧格式的已验证下载。交付前仍校验 APK 内部版本、递增版本号、摘要与签名。
- 另行复现了检查阶段的旧状态：同一进程自动检查后，设置入口曾沿用最长 24 小时的检查间隔。现在手动打开立即查询，自动检查仍限频。这个问题发生在下载之前，无法解释上述安装器提示。

再次出现系统版本不符时，应在卸载前记录手机型号、Android/厂商系统版本、应用更新页当前/目标版本、安装器完整提示，以及重新打开后系统应用信息中的版本。能连接 ADB 时，记录操作时间和以下输出；分享日志前请移除其他应用或个人信息。保留这些信息后，可从该 Release 下载同一正式 APK 直接覆盖安装，对比系统安装器的结果。

```sh
adb shell dumpsys package org.streampocket | grep -E 'versionCode=|versionName=|firstInstallTime|lastUpdateTime'
adb logcat -d -s PackageManager PackageInstallerSession ActivityManager | grep org.streampocket
```

## v0.39.0 publication verification (2026-11-11)

- Release commit: `dd10cc23a1133ae0dac4479533288a5aa0d46fc4`; version 0.39.0 (47).
- Public APK SHA-256: `68906853a701999317cff80c5d1a7b2d1d30e41071328219dccfb1beaf0a26a5`; 2,833,032 bytes. Downloaded APK, SHA256SUMS and latest/download/update.json match. Anonymous GitHub latest API returns v0.39.0 and the release commit.
- API 37 emulator: genuine in-app download and system-installer upgrade from 0.38.0 to 0.39.0 passed PublishedUpgradeDeviceTest, OK (1 test). Package manager confirms code 47; firstInstallTime remains 2026-10-28 22:23:52. No uninstall or ADB replacement of the production app.
- 130 unit tests passed; release lint has zero errors; final combined device regression passed 12 tests. Alternative media detection probes the current episode with at most three concurrent requests and cancels remaining work after two READY results. Closing, leaving and reopening verify cancellation and stale-session isolation.
- Twenty resource-pressure cycles created/released 52/52 players, with zero retained player weak references, active media calls and alternative-probe calls. These are controlled emulator results, not guarantees for all physical devices or external providers.
- Local evidence: artifacts/v0.39.0/published-upgrade.log, public/, final-feature-device-tests.log. Full visual audit: design-qa.md.

## v0.40.0 发布验收（2026-11-11）

- 正式应用提交：`fe28e09e2930bbd312350c3563e5a73ac78911c7`；版本 0.40.0（48）。
- APK SHA-256：`c836378695dda4c3117a6084f2e958b8840cbabc20d2c7e7c0844f9f86b19b1c`，2,771,591 字节。原正式证书 SHA-256 保持 `7dd2deb1eb4668ae56f830f09ba9c0a5a322763392d8846074c1da2daef02b7f`。
- 重新下载公开 APK、SHA256SUMS、latest/download/update.json 验证一致；匿名 GitHub latest API 指向 v0.40.0 及上述应用提交。
- emulator-5554 / API 37 从正式 0.39.0 经实际应用内下载和系统安装器升级至 0.40.0，PublishedUpgradeDeviceTest 为 OK (1 test)。versionCode 48，firstInstallTime 仍为 2026-10-28 22:23:52。未卸载正式包，也未用 ADB 安装替代升级。
- 131 项单元测试、16 项最终设备回归、额外两倍字号全屏播放器回归均通过；release lint 0 错误。20 轮压力测试创建/释放播放器 56/56，残留弱引用、活动媒体请求及换源探测请求为 0。Java 堆四次采样约 8.8/9.7/9.2/7.9 MB；这些是模拟器覆盖路径的数据。
- 普通日夜、两倍字号、大屏主要页面与简介流程通过；独立视觉评审指出的系统栏安全区和浅色海报按钮对比问题已修复并复核。独立 UX 走查证据与外部简介数据限制见 docs/ui-v0.40.0.md。
- 验收后按规则保留 v0.40.0、v0.39.0、v0.38.0 三个 Release；删除旧 v0.37.1 Release 附件，保留 Git tag。
- 本地证据目录：artifacts/v0.40.0/，包括 public/、final-device-tests.log、published-upgrade.log、ui-review.html。

## v0.41.0 发布验收（2026-11-11）

- 正式应用提交：`f5392986ba33fe0fbdea0cdfca064e11817b13d4`；版本 0.41.0（49）。
- APK SHA-256：`80aea397bebfffd9050d9ee38f9bcb0565c68fe74b1bbd51593827f7e0305b94`，2,787,976 字节；原正式签名证书不变，apksigner 验证通过。
- 匿名下载公开 APK、SHA256SUMS 和 latest/download/update.json 三者一致。匿名 GitHub latest API 返回 403 rate limit，未宣称该接口验证成功；登录 API 确认 Latest 及目标应用提交，公开清单和真实升级另外验证分发可用。
- emulator-5554 / API 37 从正式 0.40.0 通过应用内实际下载、系统安装器升级到 0.41.0，PublishedUpgradeDeviceTest 为 OK (1 test)。包管理器确认 code 49 / 0.41.0，firstInstallTime 保持 2026-10-28 22:23:52。没有卸载正式应用或用 ADB 覆盖安装替代升级。
- 131 项单元测试通过，release lint 0 errors / 63 warnings。最终专项共覆盖 16 个不同设备回归案例：预取 6、资源与容错 4、广告过滤 1、玻璃 1、播放器布局 1、网络关闭 1、更新器 2。广告测试原同名“关闭”匹配歧义已以按钮角色定位修正，并通过最终重跑；不是忽略失败。
- 系统禁用窗口模糊的播放器 / 玻璃专项额外 2 项通过，含反复打开/关闭窗口、同一播放器保留与 500 集定位。日夜、2 倍字号、1280×800dp 大屏主要页面测试通过；独立静态视觉复核无阻断问题。降级视频面板不承诺动态背景捕获。
- 受控 HLS 高响应延迟对比，二次缓冲 1 → 0；三切片耗时约 3800 → 2568ms。20 轮资源压力创建/释放播放器 54/54，播放器弱引用、活动媒体和换源探测请求均为 0。适用范围及内存边界见 docs/ui-v0.41.0.md。
- 验收后保留 v0.41.0、v0.40.0、v0.39.0 三个 Release，删除旧 v0.38.0 Release 附件并保留 Git tag。
- 本地证据：artifacts/v0.41.0/public/verification.txt、final-playback.log、fallback-player.log、published-upgrade.log、installed-version.txt、ui-review.html。

## v0.42.0 发布验收（2026-11-11）

- 应用提交 `6d9524ebca00e601920ecca645f92e6150804361`，版本 0.42.0（50）；正式证书保持不变。
- APK SHA-256 `23c615e7a74397509544cfa641093bc9959d54797960e1dfda74a8a1529566f9`，2,787,976 字节。apksigner / 包内版本验证通过；匿名重新下载 APK、SHA256SUMS 与 latest/download/update.json 一致，登录 GitHub latest API 确认目标提交。
- emulator-5554 / API 37 从 0.41.0 实际应用内下载、系统安装器升级至 0.42.0，PublishedUpgradeDeviceTest 为 OK (1 test)。versionCode 50；firstInstallTime 保持 2026-10-28 22:23:52，未卸载或 ADB 覆盖安装正式包。
- 131 项单元测试、14 项设备回归通过；release lint 0 errors / 63 warnings。新增大于旧上限的 8 MiB 切片回收测试覆盖暂停、恢复与关闭；初次失败揭示已完成 Future 保留结果，显式清空后完整重跑通过。20 轮压力测试播放器创建/释放 56/56，播放器弱引用、活动媒体与换源请求均归零。
- 手机日夜主要页面、普通 / 两倍字号全屏操作通过。控件截图验证按钮无描边，选集 / 换源及其他播放动作位于时间轴下方，顶部只保留返回与片名。自动测试点击实际可见的底部更多按钮，验证倍速、换源、500 集定位和窗口反复关闭。额外稳定截图测试关闭测试播放器的控制条自动隐藏计时，正式应用仍保留原有自动隐藏。
- 预取数据最高约 96 MiB，按堆与低内存状态缩减；原播放器缓冲与解码内存另外计算。详细边界见 docs/ui-v0.42.0.md。
- 验收后保留 v0.42.0、v0.41.0、v0.40.0 三个 Release，删除 v0.39.0 Release 附件、保留 Git tag。
- 证据目录 artifacts/v0.42.0/：final-device.log、player-font2.log、player-capture/、dark/、light/、public/、published-upgrade.log、installed-version.txt。
