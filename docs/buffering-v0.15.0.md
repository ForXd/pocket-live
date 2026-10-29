# v0.15.0 · 提前补充播放缓冲

## 复现与原因

现有配置使用 Media3 1.6.1 DefaultLoadControl，最低时间目标 20 秒、最高 60 秒，32 MiB 字节目标优先。缓冲达到最高时间目标后停止预取，直到低于最低目标才重新加载。因此“最多缓冲 60 秒”不等于一直拥有接近 60 秒的抗波动余量。

排查顺序：补充启动过晚；高码率触及字节预算；持续带宽不足或解码压力。本次受控复现证明第一种情况存在，不声称定位了用户未提供日志的所有卡顿。

`BufferingPolicyDeviceTest.prefetchedVideoSurvivesTransientOutage` 调用生产工厂创建的真实 Media3 控制器，用 100 ms 步长回放 120 秒轨迹：初始预存 60 秒，正常网络每秒可下载 4 秒媒体，第 36–61 秒暂停传输。旧代码缓冲见底 10 个采样，断言失败；只将最低时间目标调至 50 秒，同轨迹见底次数变为 0。测试只模拟媒体到达与消费，不包含真实网络超时、重试、解码或屏幕帧率，不应将 10 个采样解释成实机精确卡顿时长。

复现命令（先构建并安装 debug 与 androidTest APK）：

```sh
adb shell am instrument -w -e class org.streampocket.BufferingPolicyDeviceTest org.streampocket.devtest.test/androidx.test.runner.AndroidJUnitRunner
```

旧策略输出：`expected:<0> but was:<10>`；新策略输出：`OK (2 tests)`。

## 实现与取舍

- 最低时间目标改为 50 秒，最高仍为 60 秒，保留 10 秒加载滞回区间，提前补充消耗的缓冲。
- 抽取生产 LoadControl 工厂，让回归直接调用播放器采用的配置。
- 首次起播仍为 1 秒，再次缓冲仍为 4 秒，倍速和直播目标偏移继续交给 Media3 处理。
- 32 MiB 媒体分配目标优先，视频不落盘。高码率或倍速视频不保证达到 50 秒；不为追求时间目标无限增加内存。
- 继续使用现有自适应选轨及“流畅优先”。不新增定时重连、自动换源或无限重试。
- 提前离开长视频可能多预取数据；同样看完的视频总媒体量不因此增加。源站持续带宽不足、HTTP 错误、解码性能问题仍需分别处理。

依据：[固定版本 DefaultLoadControl 源码](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java) 与 [LoadControl 参数定义](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/LoadControl.java)。沿用已有依赖版本。

## 验证

- 89 项单元测试通过。
- Android API 37 模拟器：实际 LoadControl 弱网轨迹、首次/再次起播、2 倍速阈值、直播偏移阈值及达到字节预算停止预取检查通过。
- 150 秒合成 HLS 真实播放器：预取达到 55 秒以上，缓冲内跳转后恢复预取，随后继续播放并前进；退出后请求全部取消。
- 流畅优先选轨、503 重试与 20 轮换源/前后台压力测试通过。共 6 项设备测试通过，回收后播放器弱引用剩余 0，最后一次 PSS 未高于首次采样。
- debug、androidTest、release 构建成功。正式 APK 使用原证书，v2/v3 签名验证通过；模拟器完成 0.14.0（20）→ 0.15.0（21）覆盖安装与冷启动。APK 不含测试视频、播放列表或签名私钥。

未覆盖实体设备、用户实际片源与不同网络运营商，无法承诺消除所有卡顿。
