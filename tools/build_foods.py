"""Build skin-core/src/main/resources/foods.tsv from USDA FoodData Central.

Usage:  python tools/build_foods.py <path to FoodData_Central_sr_legacy_food_csv_2018-04>

Every nutrient value in the app comes from this file. Single foods are taken
straight from USDA SR Legacy (2018) by fdc_id; Indian dishes USDA does not list
are built from those same foods by a stated recipe, and ragi comes from NIN's
Nutritive Value of Indian Foods because USDA has no finger millet. The source
column says which, so every number can be traced.
"""
import csv
import sys
from pathlib import Path

NUTRIENTS = {  # USDA nutrient id -> column
    1008: "kcal", 1003: "protein_g", 1004: "fat_g", 1005: "carb_g", 1079: "fibre_g",
    1087: "calcium_mg", 1089: "iron_mg", 1095: "zinc_mg", 1106: "vita_ug", 1162: "vitc_mg",
}
COLS = list(NUTRIENTS.values())

# id, name, aliases, groups, allergens, unit label, unit grams, fdc_id
# groups: GRAINS PULSES DAIRY FLESH EGGS VITA_FV OTHER_FV BREASTMILK (WHO MDD 2021)
SINGLE = [
    ("rice", "Rice (cooked)", "chawal|white rice|plain rice|steamed rice", "GRAINS", "", "tbsp", 15, 169757),
    ("brown_rice", "Brown rice (cooked)", "brown rice", "GRAINS", "", "tbsp", 15, 169704),
    ("oats", "Oats porridge (with water)", "oatmeal|oats|oats porridge|oat porridge", "GRAINS", "", "tbsp", 15, 173905),
    ("millet", "Millet (bajra/jowar, cooked)", "bajra|jowar|millet", "GRAINS", "", "tbsp", 15, 168871),
    ("quinoa", "Quinoa (cooked)", "quinoa", "GRAINS", "", "tbsp", 15, 168917),
    ("pasta", "Pasta (cooked)", "pasta|macaroni|noodles", "GRAINS", "wheat", "tbsp", 15, 169737),
    ("bread", "Whole-wheat bread", "bread|toast", "GRAINS", "wheat", "slice", 28, 172688),
    ("potato", "Potato (boiled)", "aloo|potato|mashed potato", "GRAINS", "", "tbsp", 15, 170440),
    ("corn", "Sweet corn (cooked)", "makka|corn|bhutta", "GRAINS", "", "tbsp", 15, 169999),
    ("lentil", "Masoor dal (cooked lentils)", "masoor|red lentil", "PULSES", "", "tbsp", 15, 172421),
    ("moong", "Moong (cooked mung beans)", "moong|mung|green gram", "PULSES", "", "tbsp", 15, 174257),
    ("chana", "Chickpeas (cooked)", "chana|chole|chickpea|garbanzo|bengal gram", "PULSES", "", "tbsp", 15, 173757),
    ("toor", "Toor dal (cooked pigeon peas)", "toor|arhar|tuvar|pigeon pea", "PULSES", "", "tbsp", 15, 172437),
    ("rajma", "Rajma (cooked kidney beans)", "rajma|kidney beans", "PULSES", "", "tbsp", 15, 175194),
    ("urad", "Urad dal (cooked)", "urad|black gram", "PULSES", "", "tbsp", 15, 172427),
    ("tofu", "Tofu", "tofu|soya paneer", "PULSES", "soy", "tbsp", 15, 172475),
    ("peanut_butter", "Peanut butter (smooth)", "peanut butter|peanut|groundnut|moongphali", "PULSES", "peanut", "tsp", 5, 172470),
    ("almond", "Almonds (ground)", "badam|almond", "PULSES", "tree_nut", "tsp", 3, 170567),
    ("almond_butter", "Almond butter", "almond butter", "PULSES", "tree_nut", "tsp", 5, 168588),
    ("cashew", "Cashews (ground)", "kaju|cashew", "PULSES", "tree_nut", "tsp", 3, 170162),
    ("sesame", "Sesame seeds (ground)", "til|sesame", "PULSES", "sesame", "tsp", 3, 170150),
    ("tahini", "Tahini (sesame paste)", "tahini", "PULSES", "sesame", "tsp", 5, 168604),
    ("milk", "Cow's milk", "doodh|milk", "DAIRY", "milk", "100 ml", 100, 171265),
    ("curd", "Curd / plain yogurt", "dahi|curd|yogurt|yoghurt", "DAIRY", "milk", "tbsp", 15, 171284),
    ("paneer", "Paneer (est. as whole-milk ricotta)", "paneer|cottage cheese", "DAIRY", "milk", "tbsp", 15, 170851),
    ("ghee", "Ghee", "ghee|clarified butter", "", "milk", "tsp", 5, 173412),
    ("butter", "Butter (unsalted)", "butter|makhan", "", "milk", "tsp", 5, 173430),
    ("mustard_oil", "Mustard oil", "mustard oil|oil", "", "", "tsp", 5, 172337),
    ("egg", "Egg (hard-boiled)", "anda|egg|boiled egg|scrambled egg", "EGGS", "egg", "egg", 50, 173424),
    ("egg_yolk", "Egg yolk", "egg yolk|yolk", "EGGS", "egg", "yolk", 17, 172184),
    ("chicken", "Chicken (cooked)", "chicken|murgh", "FLESH", "", "tbsp", 15, 171477),
    ("fish", "Fish (carp, cooked)", "machli|fish|rohu|carp", "FLESH", "fish", "tbsp", 15, 174185),
    ("salmon", "Salmon (cooked)", "salmon", "FLESH", "fish", "tbsp", 15, 175168),
    ("mutton", "Mutton / lamb mince (cooked)", "mutton|lamb|keema", "FLESH", "", "tbsp", 15, 172544),
    ("prawn", "Prawns (cooked)", "prawn|shrimp|jhinga", "FLESH", "shellfish", "tbsp", 15, 175180),
    ("carrot", "Carrot (cooked)", "gajar|carrot", "VITA_FV", "", "tbsp", 15, 170394),
    ("sweet_potato", "Sweet potato (cooked)", "shakarkandi|sweet potato", "VITA_FV", "", "tbsp", 15, 168484),
    ("pumpkin", "Pumpkin (cooked)", "kaddu|pumpkin|kashiphal", "VITA_FV", "", "tbsp", 15, 168449),
    ("mango", "Mango", "aam|mango", "VITA_FV", "", "tbsp", 15, 169910),
    ("papaya", "Papaya", "papita|papaya", "VITA_FV", "", "tbsp", 15, 169926),
    ("spinach", "Spinach (cooked)", "palak|spinach", "VITA_FV", "", "tbsp", 15, 168463),
    ("amaranth", "Amaranth leaves (cooked)", "chaulai|thotakura|keerai|amaranth", "VITA_FV", "", "tbsp", 15, 169202),
    ("moringa", "Drumstick leaves (cooked)", "moringa|drumstick leaves|murungai", "VITA_FV", "", "tbsp", 15, 168417),
    ("banana", "Banana", "kela|banana", "OTHER_FV", "", "banana", 100, 173944),
    ("apple", "Apple", "seb|apple|stewed apple", "OTHER_FV", "", "tbsp", 15, 171688),
    ("pear", "Pear", "nashpati|pear", "OTHER_FV", "", "tbsp", 15, 167776),
    ("chikoo", "Chikoo (sapota)", "chikoo|chiku|sapota|sapodilla", "OTHER_FV", "", "tbsp", 15, 167759),
    ("guava", "Guava", "amrood|guava", "OTHER_FV", "", "tbsp", 15, 173044),
    ("orange", "Orange", "santra|orange|mosambi", "OTHER_FV", "", "segment", 15, 169097),
    ("pomegranate", "Pomegranate", "anar|pomegranate", "OTHER_FV", "", "tbsp", 15, 169134),
    ("grapes", "Grapes (cut lengthwise)", "angoor|grapes", "OTHER_FV", "", "grape", 5, 174683),
    ("watermelon", "Watermelon", "tarbooz|watermelon", "OTHER_FV", "", "tbsp", 15, 167765),
    ("avocado", "Avocado", "avocado|butter fruit", "OTHER_FV", "", "tbsp", 15, 171705),
    ("peas", "Green peas (cooked)", "matar|peas", "OTHER_FV", "", "tbsp", 15, 170017),
    ("beans", "Green beans (cooked)", "beans|french beans", "OTHER_FV", "", "tbsp", 15, 169141),
    ("beetroot", "Beetroot (cooked)", "chukandar|beetroot|beet", "OTHER_FV", "", "tbsp", 15, 169146),
    ("lauki", "Bottle gourd (cooked)", "lauki|dudhi|bottle gourd|sorakaya", "OTHER_FV", "", "tbsp", 15, 169233),
    ("tomato", "Tomato", "tamatar|tomato", "OTHER_FV", "", "tbsp", 15, 170457),
    ("cauliflower", "Cauliflower (cooked)", "gobi|cauliflower", "OTHER_FV", "", "tbsp", 15, 170397),
    ("broccoli", "Broccoli (cooked)", "broccoli", "OTHER_FV", "", "tbsp", 15, 169967),
    ("coconut", "Coconut (fresh)", "nariyal|coconut|coconut chutney|chutney", "OTHER_FV", "", "tbsp", 10, 170169),
    ("dates", "Dates", "khajur|dates", "OTHER_FV", "", "date", 24, 168191),
    ("honey", "Honey", "shahad|honey", "", "", "tsp", 7, 169640),
    ("jaggery", "Jaggery / brown sugar", "gur|jaggery|sugar", "", "", "tsp", 4, 168833),
    ("semolina", "Semolina (suji/rava, dry)", "suji|sooji|rava|semolina|upma", "GRAINS", "wheat", "tbsp", 10, 168933),
    ("wheat_flour", "Whole-wheat flour (atta, dry)", "atta|wheat flour", "GRAINS", "wheat", "tbsp", 8, 168893),
    ("breast_milk", "Breast milk", "breast milk|breastfeed|breastfeeding", "BREASTMILK", "", "100 ml", 100, 171279),
]

# NIN, Nutritive Value of Indian Foods (Gopalan et al.), finger millet, per 100 g dry.
RAGI_DRY = {"kcal": 328, "protein_g": 7.3, "fat_g": 1.3, "carb_g": 72.0, "fibre_g": 3.6,
            "calcium_mg": 344, "iron_mg": 3.9, "zinc_mg": 2.3, "vita_ug": 3.5, "vitc_mg": 0}

# id, name, aliases, groups, allergens, unit, grams, recipe [(ingredient, share)]
RECIPES = [
    ("khichdi", "Moong dal khichdi", "khichdi|khichri|kichdi|rice and lentils|lentil rice|risotto", "GRAINS,PULSES", "milk", "tbsp", 15,
     [("rice", 0.49), ("moong", 0.48), ("ghee", 0.03)]),
    ("dal", "Toor dal (thin, home-style)", "dal|daal|dal tadka|toor dal|lentils|lentil soup|lentil curry|yellow lentils", "PULSES", "milk", "tbsp", 15,
     [("toor", 0.65), ("water", 0.33), ("ghee", 0.02)]),
    ("moong_dal", "Moong dal (thin)", "moong dal|yellow dal", "PULSES", "milk", "tbsp", 15,
     [("moong", 0.65), ("water", 0.33), ("ghee", 0.02)]),
    ("idli", "Idli", "idli|idly|rice cake|rice cakes|steamed rice cake|steamed cakes|dumpling|dumplings", "GRAINS,PULSES", "", "idli", 40, [("rice", 0.67), ("urad", 0.33)]),
    ("roti", "Roti / chapati", "roti|chapati|phulka|chapathi", "GRAINS", "wheat", "roti", 30, [("wheat_flour", 0.72), ("water", 0.28)]),
    ("curd_rice", "Curd rice", "curd rice|dahi chawal|thayir sadam", "GRAINS,DAIRY", "milk", "tbsp", 15, [("rice", 0.6), ("curd", 0.4)]),
    ("ragi_porridge", "Ragi porridge (with water)", "ragi|nachni|finger millet|ragi malt|ragi kanji", "GRAINS", "", "tbsp", 15, [("ragi_dry", 0.12), ("water", 0.88)]),
    ("suji_kheer", "Suji kheer (semolina in milk)", "suji kheer|rava kheer|semolina pudding", "GRAINS,DAIRY", "milk,wheat", "tbsp", 15, [("semolina", 0.10), ("milk", 0.88), ("jaggery", 0.02)]),
    ("veg_mash", "Mixed vegetable mash (carrot, peas, potato)", "vegetable mash|veg puree|mixed veg", "VITA_FV,OTHER_FV,GRAINS", "", "tbsp", 15, [("carrot", 0.34), ("peas", 0.33), ("potato", 0.33)]),
    ("palak_dal", "Palak dal", "palak dal|dal palak|spinach dal", "PULSES,VITA_FV", "milk", "tbsp", 15, [("toor", 0.5), ("spinach", 0.25), ("water", 0.23), ("ghee", 0.02)]),
    ("sambar", "Sambar (toor dal with vegetables)", "sambar|sambhar|vegetable stew|lentil stew|vegetable curry", "PULSES,OTHER_FV", "", "tbsp", 15,
     [("toor", 0.45), ("veg_mix", 0.20), ("water", 0.33), ("mustard_oil", 0.02)]),
    ("upma", "Upma (cooked semolina)", "upma|uppittu|rava upma", "GRAINS", "wheat", "tbsp", 15,
     [("semolina", 0.25), ("water", 0.63), ("veg_mix", 0.09), ("mustard_oil", 0.03)]),
    ("dosa", "Dosa (est. from rice and urad)", "dosa|dosai|uttapam", "GRAINS,PULSES", "", "dosa", 60,
     [("rice", 0.62), ("urad", 0.31), ("mustard_oil", 0.04), ("water", 0.03)]),
    ("poha", "Poha (est. as rice with peas)", "poha|aval|flattened rice|chivda", "GRAINS", "", "tbsp", 15,
     [("rice", 0.88), ("peas", 0.08), ("mustard_oil", 0.04)]),
    ("vada", "Medu vada (est., fried)", "vada|vadai|medu vada|wada", "PULSES", "", "vada", 35,
     [("urad", 0.80), ("mustard_oil", 0.15), ("water", 0.05)]),
    ("egg_bhurji", "Scrambled egg (bhurji)", "bhurji|egg bhurji|scrambled egg", "EGGS", "egg,milk", "tbsp", 15, [("egg", 0.95), ("ghee", 0.05)]),
]


def load(usda: Path):
    food = {}
    for row in csv.DictReader(open(usda / "food.csv", encoding="utf-8")):
        food[int(row["fdc_id"])] = row["description"]
    wanted = {f[-1] for f in SINGLE}
    vals = {fid: {c: 0.0 for c in COLS} for fid in wanted}
    for row in csv.DictReader(open(usda / "food_nutrient.csv", encoding="utf-8")):
        fid = int(row["fdc_id"])
        if fid not in wanted:
            continue
        nid = int(row["nutrient_id"])
        if nid in NUTRIENTS and row["amount"]:
            vals[fid][NUTRIENTS[nid]] = float(row["amount"])
    return food, vals


def main():
    usda = Path(sys.argv[1])
    food, vals = load(usda)
    per = {}
    rows = []
    for fid_, name, aliases, groups, allergens, unit, grams, fdc in SINGLE:
        v = vals[fdc]
        assert v["kcal"] > 0 or fid_ in ("water",), (fid_, food[fdc])
        per[fid_] = v
        rows.append([fid_, name, aliases, groups, allergens, unit, grams] + [round(v[c], 2) for c in COLS] +
                    [f"USDA FDC {fdc}: {food[fdc]}"])
    per["water"] = {c: 0.0 for c in COLS}
    per["ragi_dry"] = RAGI_DRY
    per["veg_mix"] = {c: (per["carrot"][c] + per["peas"][c] + per["beans"][c]) / 3 for c in COLS}
    for fid_, name, aliases, groups, allergens, unit, grams, parts in RECIPES:
        assert abs(sum(s for _, s in parts) - 1) < 1e-6, fid_
        v = {c: sum(per[i][c] * s for i, s in parts) for c in COLS}
        recipe = " + ".join(f"{int(s * 100)}% {i}" for i, s in parts)
        src = "Recipe from USDA foods: " + recipe
        if any(i == "ragi_dry" for i, _ in parts):
            src = "Recipe: " + recipe + " (ragi: NIN Nutritive Value of Indian Foods)"
        rows.append([fid_, name, aliases, groups, allergens, unit, grams] + [round(v[c], 2) for c in COLS] + [src])
    out = Path(__file__).resolve().parent.parent / "skin-core/src/main/resources/foods.tsv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["id", "name", "aliases", "groups", "allergens", "unit", "unit_g"] + COLS + ["source"])
        w.writerows(rows)
    print(f"{len(rows)} foods -> {out}")


if __name__ == "__main__":
    main()
