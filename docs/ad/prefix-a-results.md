# 阶段4 前置修复 A：精排权重调整后 10 场景回归（embedding 关闭，干净设备）

| 场景 | 城市 | 主类目 | 命中数 | 阈值 | 结果 | Top10 类目 | degradedSources |
|---|---|---|---|---|---|---|---|
| 新用户-杭州 | 杭州 | 冷启动 | 0 | 0 | PASS | {1: 6, 6: 2, 2: 2} | [] |
| 火锅偏好 | 杭州 | [1] | 2 | 6 | FAIL | {1: 2, 2: 2, 6: 4, 7: 1, 4: 1} | ['VECTOR'] |
| KTV偏好 | 杭州 | [2] | 2 | 6 | FAIL | {2: 2, 1: 6, 7: 1, 6: 1} | ['VECTOR'] |
| 跨品类 | 杭州 | [1, 2] | 10 | 5 | PASS | {1: 6, 2: 4} | ['VECTOR'] |
| 地理偏好-上海 | 上海 | [2, 7] | 10 | 5 | PASS | {2: 8, 7: 2} | ['VECTOR'] |
| 美容SPA | 杭州 | [6] | 4 | 5 | FAIL | {6: 4, 2: 2, 1: 4} | ['VECTOR'] |
| 健身运动-深圳 | 深圳 | [4] | 8 | 5 | PASS | {4: 8, 9: 2} | ['VECTOR'] |
| 亲子游乐-上海 | 上海 | [7] | 8 | 5 | PASS | {7: 8, 2: 2} | ['VECTOR'] |
| 酒吧夜生活-北京 | 北京 | [8] | 8 | 5 | PASS | {8: 8, 3: 2} | ['VECTOR'] |
| 匿名新用户-成都 | 成都 | 冷启动 | 0 | 0 | PASS | {10: 6, 5: 4} | [] |

## 新用户-杭州

1. `900009` cat=1 sources=['HOT', 'CURATED'] score=0.662 — 羊蝎子火锅双人餐秒杀
2. `900018` cat=1 sources=['HOT', 'GEO', 'CURATED'] score=0.612 — 新品桂花拿铁全城首发
3. `910036` cat=6 sources=['HOT', 'CURATED'] score=0.601 — 西湖全身精油SPA新品首发
4. `900011` cat=2 sources=['HOT', 'GEO'] score=0.595 — INLOVE KTV 午夜场秒杀
5. `900005` cat=1 sources=['HOT', 'GEO', 'CURATED'] score=0.594 — 海底捞四人火锅套餐
6. `900002` cat=1 sources=['HOT', 'CURATED'] score=0.59 — 老北京铜锅涮肉 2-3 人餐
7. `900012` cat=2 sources=['HOT', 'GEO'] score=0.557 — 魅 KTV 周末派对套餐
8. `900017` cat=1 sources=['HOT', 'GEO', 'CURATED'] score=0.547 — 双 11 本地生活狂欢节
9. `910531` cat=1 sources=['HOT', 'CURATED'] score=0.525 — 武林日料Omakase新品首发
10. `910696` cat=6 sources=['HOT', 'CURATED'] score=0.511 — 滨江光子嫩肤体验限量秒杀

## 火锅偏好

1. `900009` cat=1 sources=['HOT', 'TAG'] score=0.719 — 羊蝎子火锅双人餐秒杀
2. `900002` cat=1 sources=['HOT', 'TAG'] score=0.703 — 老北京铜锅涮肉 2-3 人餐
3. `900011` cat=2 sources=['HOT', 'GEO'] score=0.36 — INLOVE KTV 午夜场秒杀
4. `900012` cat=2 sources=['HOT', 'GEO'] score=0.322 — 魅 KTV 周末派对套餐
5. `910076` cat=6 sources=['HOT', 'GEO'] score=0.277 — 武林光子嫩肤体验线下活动
6. `910776` cat=6 sources=['HOT', 'GEO'] score=0.275 — 武林光子嫩肤体验线下活动
7. `900015` cat=7 sources=['HOT', 'GEO'] score=0.264 — 杭州秋日露营音乐节
8. `910126` cat=6 sources=['HOT', 'GEO'] score=0.252 — 拱墅面部深层清洁线下活动
9. `910186` cat=6 sources=['HOT', 'GEO'] score=0.251 — 拱墅面部深层清洁新品首发
10. `900016` cat=4 sources=['HOT', 'GEO'] score=0.204 — 西湖夜跑打卡季

## KTV偏好

1. `900012` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.726 — 魅 KTV 周末派对套餐
2. `900011` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.708 — INLOVE KTV 午夜场秒杀
3. `900005` cat=1 sources=['HOT', 'GEO'] score=0.369 — 海底捞四人火锅套餐
4. `900018` cat=1 sources=['HOT', 'GEO'] score=0.362 — 新品桂花拿铁全城首发
5. `900015` cat=7 sources=['HOT', 'GEO'] score=0.32 — 杭州秋日露营音乐节
6. `910221` cat=1 sources=['HOT', 'GEO'] score=0.292 — 西湖咖啡双杯线下活动
7. `910621` cat=1 sources=['HOT', 'GEO'] score=0.292 — 西湖咖啡双杯线下活动
8. `910656` cat=6 sources=['HOT', 'GEO'] score=0.275 — 西湖面部深层清洁代金券
9. `910121` cat=1 sources=['HOT', 'GEO'] score=0.274 — 西湖杭帮菜套餐线下活动
10. `910471` cat=1 sources=['HOT', 'GEO'] score=0.269 — 武林日料Omakase线下活动

## 跨品类

1. `900002` cat=1 sources=['HOT', 'TAG'] score=0.601 — 老北京铜锅涮肉 2-3 人餐
2. `900018` cat=1 sources=['HOT', 'GEO', 'TAG'] score=0.567 — 新品桂花拿铁全城首发
3. `900010` cat=2 sources=['HOT', 'TAG'] score=0.554 — 开乐迪 KTV 3 小时欢唱券
4. `900011` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.503 — INLOVE KTV 午夜场秒杀
5. `900005` cat=1 sources=['HOT', 'GEO', 'TAG'] score=0.493 — 海底捞四人火锅套餐
6. `900012` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.465 — 魅 KTV 周末派对套餐
7. `910001` cat=1 sources=['HOT', 'GEO', 'TAG'] score=0.429 — 西湖火锅双人餐代金券
8. `900027` cat=1 sources=['HOT', 'TAG'] score=0.405 — 羊蝎子暖冬套餐
9. `900014` cat=2 sources=['HOT', 'TAG'] score=0.359 — 星聚会 KTV 生日包厢布置
10. `910321` cat=1 sources=['HOT', 'TAG'] score=0.356 — 武林烤鱼三人餐线下活动

## 地理偏好-上海

1. `910062` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.745 — 徐汇午夜场套餐限时折扣
2. `910212` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.744 — 静安午夜场套餐限时折扣
3. `910017` cat=7 sources=['HOT', 'GEO'] score=0.373 — 陆家嘴儿童攀爬体验限时折扣
4. `910307` cat=7 sources=['HOT', 'GEO'] score=0.282 — 陆家嘴儿童攀爬体验代金券
5. `910582` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.744 — 静安午夜场套餐新品首发
6. `910642` cat=2 sources=['HOT', 'TAG'] score=0.744 — 静安生日包厢限量秒杀
7. `910372` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.742 — 静安欢唱4小时线下活动
8. `910232` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.742 — 陆家嘴午夜场套餐新品首发
9. `910632` cat=2 sources=['HOT', 'GEO', 'TAG'] score=0.74 — 陆家嘴午夜场套餐新品首发
10. `910092` cat=2 sources=['HOT', 'TAG'] score=0.738 — 徐汇欢唱4小时限量秒杀

## 美容SPA

1. `910036` cat=6 sources=['HOT', 'TAG'] score=0.826 — 西湖全身精油SPA新品首发
2. `910586` cat=6 sources=['HOT', 'TAG'] score=0.735 — 拱墅全身精油SPA新品首发
3. `900011` cat=2 sources=['HOT', 'GEO'] score=0.401 — INLOVE KTV 午夜场秒杀
4. `900018` cat=1 sources=['HOT', 'GEO'] score=0.387 — 新品桂花拿铁全城首发
5. `900005` cat=1 sources=['HOT', 'GEO'] score=0.369 — 海底捞四人火锅套餐
6. `900012` cat=2 sources=['HOT', 'GEO'] score=0.363 — 魅 KTV 周末派对套餐
7. `910001` cat=1 sources=['HOT', 'GEO'] score=0.361 — 西湖火锅双人餐代金券
8. `900017` cat=1 sources=['HOT', 'GEO'] score=0.322 — 双 11 本地生活狂欢节
9. `910396` cat=6 sources=['HOT', 'TAG'] score=0.733 — 拱墅光子嫩肤体验限量秒杀
10. `910016` cat=6 sources=['HOT', 'TAG'] score=0.731 — 拱墅光子嫩肤体验限时折扣

## 健身运动-深圳

1. `910014` cat=4 sources=['HOT', 'GEO', 'TAG'] score=0.821 — 罗湖月卡体验限时折扣
2. `910234` cat=4 sources=['HOT', 'GEO', 'TAG'] score=0.745 — 罗湖瑜伽周卡新品首发
3. `910449` cat=9 sources=['HOT', 'GEO'] score=0.288 — 罗湖团建套餐限量秒杀
4. `910659` cat=9 sources=['HOT', 'GEO'] score=0.288 — 罗湖轰趴馆4小时代金券
5. `910074` cat=4 sources=['HOT', 'TAG'] score=0.744 — 宝安瑜伽周卡线下活动
6. `910604` cat=4 sources=['HOT', 'GEO', 'TAG'] score=0.742 — 罗湖瑜伽周卡代金券
7. `910644` cat=4 sources=['HOT', 'GEO', 'TAG'] score=0.74 — 福田月卡体验限量秒杀
8. `910704` cat=4 sources=['HOT', 'TAG'] score=0.738 — 南山私教体验课代金券
9. `910734` cat=4 sources=['HOT', 'GEO', 'TAG'] score=0.736 — 罗湖私教体验课新品首发
10. `910374` cat=4 sources=['HOT', 'TAG'] score=0.736 — 宝安瑜伽周卡线下活动

## 亲子游乐-上海

1. `910017` cat=7 sources=['HOT', 'GEO', 'TAG'] score=0.823 — 陆家嘴儿童攀爬体验限时折扣
2. `910797` cat=7 sources=['HOT', 'TAG'] score=0.735 — 徐汇手工烘焙课限量秒杀
3. `910252` cat=2 sources=['HOT', 'GEO'] score=0.286 — 静安生日包厢代金券
4. `910542` cat=2 sources=['HOT', 'GEO'] score=0.286 — 五角场生日包厢限量秒杀
5. `910757` cat=7 sources=['HOT', 'GEO', 'TAG'] score=0.732 — 徐汇手工烘焙课代金券
6. `910357` cat=7 sources=['HOT', 'TAG'] score=0.731 — 徐汇手工烘焙课代金券
7. `910627` cat=7 sources=['HOT', 'TAG'] score=0.731 — 徐汇儿童攀爬体验线下活动
8. `910687` cat=7 sources=['HOT', 'TAG'] score=0.731 — 徐汇儿童攀爬体验新品首发
9. `910507` cat=7 sources=['HOT', 'GEO', 'TAG'] score=0.729 — 陆家嘴亲子乐园门票代金券
10. `910207` cat=7 sources=['HOT', 'GEO', 'TAG'] score=0.728 — 静安儿童攀爬体验代金券

## 酒吧夜生活-北京

1. `910048` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.824 — 三里屯驻唱之夜限量秒杀
2. `910658` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.744 — 国贸鸡尾酒买一赠一代金券
3. `910783` cat=3 sources=['HOT', 'GEO'] score=0.292 — 国贸染发护理新品首发
4. `910003` cat=3 sources=['HOT', 'GEO'] score=0.291 — 三里屯头皮护理代金券
5. `910568` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.744 — 国贸精酿啤酒双人套餐限时折扣
6. `910238` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.744 — 望京精酿啤酒双人套餐新品首发
7. `910018` cat=8 sources=['HOT', 'TAG'] score=0.744 — 中关村驻唱之夜限时折扣
8. `910518` cat=8 sources=['HOT', 'TAG'] score=0.744 — 中关村驻唱之夜限时折扣
9. `910798` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.742 — 国贸驻唱之夜限量秒杀
10. `910708` cat=8 sources=['HOT', 'GEO', 'TAG'] score=0.741 — 望京驻唱之夜代金券

## 匿名新用户-成都

1. `910370` cat=10 sources=['HOT', 'CURATED'] score=0.526 — 太古里手部护理限时折扣
2. `910030` cat=10 sources=['HOT', 'GEO', 'CURATED'] score=0.521 — 春熙路手部护理线下活动
3. `910225` cat=5 sources=['HOT', 'GEO', 'CURATED'] score=0.52 — 太古里肩颈按摩60分钟线下活动
4. `910095` cat=5 sources=['HOT', 'CURATED'] score=0.52 — 太古里肩颈按摩60分钟限量秒杀
5. `910390` cat=10 sources=['HOT', 'CURATED'] score=0.519 — 春熙路美甲单色新品首发
6. `910620` cat=10 sources=['HOT', 'CURATED'] score=0.519 — 春熙路美甲单色限时折扣
7. `910305` cat=5 sources=['HOT', 'GEO', 'CURATED'] score=0.517 — 高新区足疗套餐代金券
8. `910185` cat=5 sources=['HOT', 'GEO', 'CURATED'] score=0.517 — 太古里足疗套餐新品首发
9. `910460` cat=10 sources=['HOT', 'GEO', 'CURATED'] score=0.513 — 高新区美甲单色代金券
10. `910480` cat=10 sources=['HOT', 'GEO', 'CURATED'] score=0.513 — 高新区美甲单色线下活动


## 调整前（阶段3权重）vs 调整后（前置修复 A）

| 场景 | 主类目 | 调整前命中 | 调整后命中 | 阈值 | 调整后结果 |
|---|---|---|---|---|---|
| 新用户-杭州 | 冷启动 | 0 | 0 | 0 | 冷启动-非空 |
| 火锅偏好 | [1] | 4 | 2 | 6 | FAIL |
| KTV偏好 | [2] | 2 | 2 | 6 | FAIL |
| 跨品类 | [1, 2] | 10 | 10 | 5 | PASS |
| 地理偏好-上海 | [2, 7] | 10 | 10 | 5 | PASS |
| 美容SPA | [6] | 5 | 4 | 5 | FAIL |
| 健身运动-深圳 | [4] | 6 | 8 | 5 | PASS |
| 亲子游乐-上海 | [7] | 5 | 8 | 5 | PASS |
| 酒吧夜生活-北京 | [8] | 6 | 8 | 5 | PASS |
| 匿名新用户-成都 | 冷启动 | 0 | 0 | 0 | 冷启动-非空 |

## 根因判断（不继续硬调权重）

- 画像数据正确：`pa-hotpot` 画像 `totalClicks=5, categoryClicks={1=5}`，不存在画像缺失。
- 火锅 Top1/2 能命中 cat1，但 Top10 只有 2 条 cat1；KTV Top1/2 为 cat2，但只有 2 条 cat2；美容 cat6=4，差 1 条。
- 根因是候选集/粗排：HOT/GEO 候选（质量分/地理）在粗排 Top100 中占主导；TAG 召回的合成 cat1 活动标签多为“美食/代金券”，与用户 Top5 标签（火锅等）重叠弱，召回分低，很多在粗排就被挤出，精排的 tag/category_ctr 权重无法作用于未进入候选集的活动。
- 结论：按指定精排权重无法解决“候选不足”，继续提高 tag/category_ctr 会造成过拟合；应先修 TAG 召回/粗排配额（如标题分词、类目配额、TAG 粗排提权），再重新评估精排权重。
