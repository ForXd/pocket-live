# 参考与数据来源

推荐功能参考 LibreSpark/LibreTV 的 `src/lib/douban.ts`：
https://github.com/LibreSpark/LibreTV/blob/main/src/lib/douban.ts

推荐列表直连豆瓣网站的公开 JSON 接口 `https://movie.douban.com/j/search_subjects`，封面使用响应中的 `cover` 字段；未使用参考站点的受密码保护 API，也未内置第三方图片代理。这个网站接口没有可用性保证，失败时界面提供重试和订阅分类浏览。推荐元数据不提供播放授权或播放地址。

自动 HLS 过滤的分组思路及阈值参考 LibreTV（AGPL-3.0）的 `src/lib/m3u8.ts`：
https://github.com/LibreSpark/LibreTV/blob/main/src/lib/m3u8.ts

本项目采用 AGPL-3.0-or-later（见 LICENSE）。Android 实现仅处理完整、未加密、没有未知标签的点播列表，并维护过滤前后时间轴映射；多码率、独立音轨、加密、fMP4、直播等复杂流保持原样。自动规则属于启发式判断，默认关闭，可以在订阅页开启，下次播放生效。
