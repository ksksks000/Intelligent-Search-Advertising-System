#!/usr/bin/env python3
"""生成阶段 2 合成活动 SQL（默认 800 条，5 城市、10 类目、5 类型）。

用法：
  python loadtest/gen_synthetic_activities.py
输出：
  src/main/resources/db/zs_p2_synthetic.sql
"""
import os
import random

random.seed(42)

COUNT = 800
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "db", "zs_p2_synthetic.sql")

CITIES = {
    "杭州": [
        ("西湖", 120.1551, 30.2741),
        ("武林", 120.1660, 30.2800),
        ("滨江", 120.2110, 30.2080),
        ("拱墅", 120.1500, 30.3200),
    ],
    "上海": [
        ("静安", 121.4470, 31.2280),
        ("徐汇", 121.4360, 31.1880),
        ("陆家嘴", 121.5060, 31.2450),
        ("五角场", 121.5140, 31.3000),
    ],
    "北京": [
        ("国贸", 116.4610, 39.9090),
        ("三里屯", 116.4550, 39.9370),
        ("中关村", 116.3160, 39.9830),
        ("望京", 116.4700, 40.0000),
    ],
    "深圳": [
        ("南山", 113.9300, 22.5330),
        ("福田", 114.0550, 22.5430),
        ("罗湖", 114.1310, 22.5480),
        ("宝安", 113.8830, 22.5550),
    ],
    "成都": [
        ("春熙路", 104.0810, 30.6570),
        ("太古里", 104.0830, 30.6530),
        ("高新区", 104.0660, 30.5720),
        ("宽窄巷子", 104.0550, 30.6690),
    ],
}

CATEGORIES = {
    1: ("美食", ["火锅双人餐", "日料Omakase", "咖啡双杯", "烤鱼三人餐", "杭帮菜套餐"]),
    2: ("KTV", ["欢唱4小时", "午夜场套餐", "生日包厢"]),
    3: ("丽人美发", ["剪发造型", "染发护理", "头皮护理"]),
    4: ("健身运动", ["月卡体验", "私教体验课", "瑜伽周卡"]),
    5: ("按摩足疗", ["肩颈按摩60分钟", "足疗套餐", "全身推拿"]),
    6: ("美容SPA", ["全身精油SPA", "面部深层清洁", "光子嫩肤体验"]),
    7: ("亲子游乐", ["亲子乐园门票", "手工烘焙课", "儿童攀爬体验"]),
    8: ("酒吧", ["精酿啤酒双人套餐", "鸡尾酒买一赠一", "驻唱之夜"]),
    9: ("轰趴馆", ["轰趴馆4小时", "生日派对包场", "团建套餐"]),
    10: ("美睫美甲", ["美甲单色", "美睫套餐", "手部护理"]),
}

TYPES = {
    0: "代金券",
    1: "限时折扣",
    2: "线下活动",
    3: "新品首发",
    4: "限量秒杀",
}

# 阶段 4 前置修复 A-2：按类目补细标签，避免合成活动只有“美食/代金券”等通用标签
FINE_TAGS = {
    1: ["火锅", "涮肉", "烤鱼", "杭帮菜", "日料"],
    2: ["KTV", "欢唱", "包厢"],
    4: ["健身", "瑜伽", "私教", "月卡"],
    6: ["SPA", "光子嫩肤", "美甲", "面部清洁"],
    7: ["亲子", "儿童", "手工", "烘焙"],
    8: ["酒吧", "鸡尾酒", "驻唱", "精酿"],
}

BASE_FINE = {
    "火锅双人餐": "火锅",
    "日料Omakase": "日料",
    "咖啡双杯": "杭帮菜",
    "烤鱼三人餐": "烤鱼",
    "杭帮菜套餐": "杭帮菜",
    "欢唱4小时": "欢唱",
    "午夜场套餐": "包厢",
    "生日包厢": "包厢",
    "月卡体验": "月卡",
    "私教体验课": "私教",
    "瑜伽周卡": "瑜伽",
    "全身精油SPA": "SPA",
    "面部深层清洁": "面部清洁",
    "光子嫩肤体验": "光子嫩肤",
    "亲子乐园门票": "亲子",
    "手工烘焙课": "烘焙",
    "儿童攀爬体验": "儿童",
    "精酿啤酒双人套餐": "精酿",
    "鸡尾酒买一赠一": "鸡尾酒",
    "驻唱之夜": "驻唱",
}


def fine_tag(category_id: int, base: str) -> str:
    if base in BASE_FINE:
        return BASE_FINE[base]
    candidates = FINE_TAGS.get(category_id, [])
    if not candidates:
        return ""
    # 没有显式映射时按 base 稳定选一个，保证至少有一个细标签
    return candidates[sum(ord(c) for c in base) % len(candidates)]


def esc(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def main() -> None:
    city_names = list(CITIES.keys())
    rows = []
    for i in range(COUNT):
        # 城市按 10 条一组轮换，类目在组内轮换，保证每个城市都有 10 个类目样本
        city = city_names[(i // 10) % len(city_names)]
        area, base_x, base_y = random.choice(CITIES[city])
        category_id = (i % 10) + 1
        category_name, bases = CATEGORIES[category_id]
        activity_type = (i // 10) % 5
        base = random.choice(bases)
        title = "%s%s%s" % (area, base, TYPES[activity_type])
        extra_tag = fine_tag(category_id, base)
        if extra_tag:
            tags = "%s,%s,%s,%s" % (category_name, extra_tag, TYPES[activity_type], city)
        else:
            tags = "%s,%s,%s" % (category_name, TYPES[activity_type], city)
        description = "%s，位于%s%s，%s，数量有限，先到先得。" % (title, city, area, tags)
        x = round(base_x + random.uniform(-0.05, 0.05), 6)
        y = round(base_y + random.uniform(-0.05, 0.05), 6)
        if activity_type in (2, 3):
            original_price = 0
            pay_value = 0
            actual_value = 0
        else:
            original_price = random.randint(5000, 60000) // 100 * 100
            pay_value = max(100, int(original_price * random.uniform(0.3, 0.9)) // 100 * 100)
            actual_value = original_price - pay_value
        stock = random.randint(20, 100) if activity_type == 4 else random.randint(50, 500)
        quality = random.randint(40, 99)
        row = (
            910001 + i, "NULL", title, TYPES[activity_type],
            description, "/imgs/blogs/blog1.jpg", activity_type, category_id,
            tags, city, area, x, y, original_price, pay_value, actual_value, stock,
            "2026-10-01 00:00:00", "2026-12-31 23:59:59", 2, quality, 0, "NULL",
        )
        rows.append(row)

    columns = ("id, shop_id, title, sub_title, description, images, activity_type, category_id, "
               "tags, city, area, x, y, original_price, pay_value, actual_value, stock, "
               "begin_time, end_time, status, quality_score, source_type, source_id")

    def literal(value):
        if value == "NULL":
            return "NULL"
        if isinstance(value, (int, float)):
            return str(value)
        return "'%s'" % esc(str(value))

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("-- 阶段 2 合成活动数据：%d 条，5 城市、10 类目、5 类型\n" % COUNT)
        f.write("-- 由 loadtest/gen_synthetic_activities.py 生成，可重复执行\n\n")
        for start in range(0, len(rows), 100):
            chunk = rows[start:start + 100]
            f.write("INSERT INTO `tb_activity` (%s) VALUES\n" % columns)
            f.write(",\n".join("(" + ", ".join(literal(v) for v in row) + ")" for row in chunk))
            f.write("\nON DUPLICATE KEY UPDATE `title` = VALUES(`title`), "
                    "`sub_title` = VALUES(`sub_title`), `description` = VALUES(`description`), "
                    "`tags` = VALUES(`tags`), `city` = VALUES(`city`), `area` = VALUES(`area`), "
                    "`x` = VALUES(`x`), `y` = VALUES(`y`), "
                    "`quality_score` = VALUES(`quality_score`), `stock` = VALUES(`stock`);\n\n")
    print("generated %d rows -> %s" % (COUNT, os.path.abspath(OUT)))


if __name__ == "__main__":
    main()
