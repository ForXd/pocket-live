# v0.39.0 设计实现验收

final result: passed

## 比较依据

用户选择第二套「幕间」，其后明确要求全屏选集改为右侧半透明半屏抽屉。后者覆盖原概念图中的窄侧栏。保持现有功能、用户来源内容与原生 Android 操作语义。

- 原始视觉：`artifacts/v0.39.0/final-ui/references/phone.png`、`wide.png`、`fullscreen.png`。
- 当前实现：同目录 `polished/phone-final/design-{home,detail,history,search,subscriptions,settings}.png`、`polished/wide-final/`、`polished/tablet-final/`；最新长集列表及播放器见 `all-pages/fixed/`。
- 同图比较：`final-compare-{home,detail,history,wide,fullscreen}.jpg`。左侧批准概念，右侧原生渲染。另查看完整截图核对选集文字、搜索框、导航图标和按钮；这些细节在合成缩略图中不足以单独验收。
- 手机 1080×2400 px，420 dpi（约 411×914 dp）；横屏 2400×1080 px。宽屏 1600×1000 px，200 dpi（1280×800 dp）；竖屏平板 1200×1920 px，240 dpi（800×1280 dp）。另验证手机字号 2.0。
- 手机概念画板 1536×1024 px，裁取单页约 464×932 px；对照去除系统栏后按等宽缩放到 400 px。宽屏概念和实现宽高比不同，按等宽缩小比较主要区域，不据此作像素差异判断。原生 dp 取代 CSS 尺寸。
- 状态：固定本地数据、正常加载完成；图片缺失、长标题、单线路、续播、500 集、日间与夜间。概念中的影片不打包到正式应用，测试海报与播放信号图也仅存在于 androidTest。

## 比较历史与修复

1. 首轮发现 [P2] 大图使用缩略图解码档、历史播放按钮可见尺寸 40dp、额外推荐工具栏挤占首屏。增加有界大图档，播放按钮补到 48dp，筛选合并到顶部标签旁；首屏显示三列海报。后续图：`phone-final/design-home.png`、`design-history.png`。
2. [P2] 详情旧顶部栏占用空间、选中集辨识不足。返回按钮叠在海报上，播放动作移到选集前，当前集使用米白底。图：`phone-final/design-detail.png`。
3. [P2] 宽屏内容仍有旧最大宽度限制，右侧浪费空间。改为可用宽度分栏，搜索与详情并排、订阅与来源分栏。图：`wide-final/design-detail.png`、`design-subscriptions.png`。
4. [P1] 初版全屏选集窄到标题换行。用户进一步明确半透明半屏方案；已改为右侧 50% 覆盖抽屉，视频不压缩，支持 50 集分段跳转和回到当前集，保持列表懒加载。正常和两倍字号均通过 500 集测试，含第 451 集定位与实际切集解码。图：`player-final/player-episodes-500.png`；日志：`light-player-final.log`、`player-font2.log`。
5. 独立手机静态视觉复核 8/10，无阻断问题；建议统一图标、强化当前集和海报融合。已换用同套 Lucide 描边图标，当前集米白强调，主海报加入顶部过渡。最终对照图记录修复结果。

## 五项视觉检查

- 字体：片名采用系统衬线，操作与正文采用原生无衬线；中文字形受设备字体影响，不伪装为概念图中的精确字体。长片名截断、完整详情标题及 2.0 字号路径可操作。
- 布局：手机以海报、主动作和内容列表分层；宽屏采用侧栏和双栏。主按钮与线路、选集顺序一致。系统状态栏、任务栏及手势区保留系统行为，未绘制假设备外框。
- 色彩：暖黑 #181715、米白 #efe3d0，浅色模式使用暖白与深棕。全屏强制深色控制面；选集抽屉采用原生系统背景模糊与约 88% 不透明暖黑底。系统关闭模糊时为约 97% 不透明底；已实际关闭系统模糊并截图验证。文字与实色集数格保持可读。
- 图像：大图独立解码上限 512,000 像素，普通海报 128,000，复用原磁盘字节及统一内存预算。来源低清/缺图仍可能出现；缺图使用明确占位而非虚构海报。生产包不含概念电影素材。
- 内容：搜索、探测、换源、续播、字幕/播放设置、订阅管理、历史与原设置功能保留。未增加账号、演员或收藏功能。时间轴保留 Media3 已有前后跳转步长，图稿的 10 秒符号不作为修改业务行为的依据。

## 验证与边界

130 项单元测试通过；release lint 0 errors、53 warnings（依赖更新建议、既有 PiP/资源/API 提示等，未以关闭检查规避）。搜索/分类 9 项浏览回归、搜索规则、订阅、历史分组、直播、详情长列表、更新器及播放生命周期相关测试通过各自最终运行。正常日夜、2.0 字号、平板横竖屏与宽屏播放器均有最终截图/测试日志。

20 轮切换和后台恢复测试：50 个播放器创建/50 个释放，弱引用残留 0、活动媒体请求 0；Java 堆采样 8.43/8.48/8.20/8.21 MB，PSS 约 150 MB，最大释放耗时 63ms。只说明覆盖路径未见持续增长或资源残留，不代表所有设备均无泄漏。

受控 10 域名/1000 结果、真实帧时钟、30 次滑动：480×800 软件模拟器首结果 209ms、完成 823ms，帧中位数 16ms、P95 25ms；简单列表对照 17/25ms。1080×2400 调试模拟器为 38/89ms，对照 21/53ms，显示软件渲染在高分辨率下的局限。没有物理 Android 设备的帧率保证；完整数据保留在 `performance-480.log`、`480-frames.txt`、`real-clock-scroll-frames.txt`，不只报告较好的采样。

剩余 P3：不同来源图片的清晰度/裁切、系统字体细节；可在实际设备与真实来源上继续观察。Final device matrix and independent visual review passed; no remaining P0/P1/P2 findings.

## 用户追加要求：逐页细节复核

统一字号、字重与圆角后，扩展检查到所有应用页面类别及主要弹层。只在全屏选集使用背景毛玻璃；普通页面和操作弹层使用稳定实色表面，避免装饰压过内容。

| 页面 / 状态 | 最新截图目录与文件 | 检查内容 |
|---|---|---|
| 首页、搜索、详情、历史、订阅、设置 | `polished/phone-final/design-*.png` | 标题/正文/按钮层级、图标、输入框、页面圆角 |
| 分类、直播及直播来源 | `all-pages/flows/catalog-random.png`、`live-channels.png`、`live-sources.png` | 列表、筛选入口、选择项、浅色对比度 |
| 推荐筛选、详情线路 | `all-pages/flows/recommend-category-sheet.png`、`detail-lines.png` | 弹层标题、选中态、操作按钮 |
| 添加订阅、JSON、外观、更新、广告路径、历史筛选及清空确认 | `polished/phone-final/sheet-*.png` | 7 个弹层的标题、字段、圆角、禁用态 |
| 500 集与吸顶导航 | `all-pages/fixed/detail-jump.png`、`detail-last-episode.png` | 返回与吸顶标题分离，三位集数单行完整 |
| 竖屏播放、播放选项、竖屏选集、横屏与半屏选集 | `all-pages/fixed/player-*.png` | 控件对比度、面板边角、背景模糊、500 集分段 |
| 无系统模糊 | `all-pages/blur-disabled/player-episodes.png`、`blur-state.txt` | 系统明确报告 disabled，深色回退清晰 |
| 两倍字号弹层 | `all-pages/font2-pages/sheet-*.png` | 不裁掉操作按钮，长内容滚动及横向选项可达 |
| 大屏横/竖屏 | `polished/wide-final/`、`polished/tablet-final/` | 双栏/侧栏、弹层宽度、文字与留白 |

独立复核发现并修复：

- 毛玻璃曾只呈现透明遮罩。Compose 标准 Dialog 覆盖窗口主题，改用在建窗前设好透明主题的 ComponentDialog；新截图已确认背景边界柔化，文字本身不被模糊。没有逐帧截图或额外解码器。
- 部分次级按钮仍为默认胶囊，部分弹层标题为衬线。已统一功能按钮 12dp、搜索及集数格 8dp、弹层 20dp；操作弹层标题均为 20sp 无衬线。
- 订阅时间改为 `yyyy-MM-dd HH:mm`。
- 详情返回按钮与吸顶选集重叠：滚过海报后展示独立导航行，并增加相对边界回归断言。
- 详情三位集数孤字换行：正常手机改为自适应四列，保持原字号；大字号自动减少列数。
- 原生播放控件在亮画面上对比不足：显示控件时增加 50% 暗色保护，关闭控件即消失。

最终静态复核确认上述 P2 均消除。功能回归 `all-pages/flows.log` 为 16 项通过；修复后三个受影响路径在 `all-pages/fixes.log` 通过（4 项）。播放器面板连续开关 10 次，覆盖返回键、点击外部与关闭按钮；关闭后窗口数量回到基线，播放实例数量不增加。

新增原生浮层之后重新运行资源压力测试：46 个播放器创建/46 个释放，残留弱引用与媒体请求均为 0；Java 堆 7.09/8.18/9.37/6.86 MB，PSS 141383/142055/142928/141192 KiB，最大释放 68ms（`polished/resource-stress.log`）。这不是所有设备无泄漏的保证。

新增两条 lint warning 为半屏尺寸使用 Configuration 与右侧窗口 Gravity.RIGHT：前者经手机、宽屏和字号矩阵核对，后者符合用户明确指定右侧展开。未通过关闭 lint 隐藏问题。

Final matrix passed: phone light/dark, landscape and portrait tablet, wide playback. Final 2x font regression: OK (5 tests), `all-pages/final-font2.log`. Independent final review of wide and large-font screenshots found no P1/P2. Gallery: `artifacts/v0.39.0/final-ui/ui-review.html`.
