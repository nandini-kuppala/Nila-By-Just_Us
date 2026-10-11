"""Build app/src/main/assets/babyproof/hazards.json: the detector vocabulary and the fix catalogue.

One entry per thing the detector looks for. Each carries:
  - the OWL-ViT query, tokenised here (CLIP BPE, 16 tokens) so the phone needs no tokenizer
  - the query's embedding from OWL-ViT's text half (tools/split_owl.py), so the phone runs
    only the image half, once per tile, and scores every query with a dot product
  - its hazard category, whether it is critical (never silently dropped) and its score threshold
  - the fix shown to the parent, written in advance from published childproofing guidance,
    and the source it comes from. The models only *see*; they never write advice.

Run after editing HAZARDS or the thresholds:
    python3 tools/build_hazards.py
"""
import json, os
from tokenizers import Tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
TOK = Tokenizer.from_file(os.path.join(HERE, "..", "work", "tokenizer", "tokenizer.json"))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "babyproof", "hazards.json")

AAP = {"name": "AAP HealthyChildren: Childproofing your home",
       "url": "https://www.healthychildren.org/English/safety-prevention/at-home/Pages/Childproofing-Your-Home.aspx"}
CPSC_ANCHOR = {"name": "CPSC Anchor It!", "url": "https://www.anchorit.gov/"}
CPSC = {"name": "CPSC: Childproofing your home",
        "url": "https://www.cpsc.gov/Safety-Education/Safety-Guides/Kids-and-Babies/Childproofing-Your-Home-12-Safety-Devices-to-Protect-Your-Children"}
BATTERY = {"name": "AAP HealthyChildren: Button battery injuries",
           "url": "https://www.healthychildren.org/English/safety-prevention/at-home/Pages/Button-Battery-Injuries-in-Children-A-Growing-Risk.aspx"}
WHO_DROWN = {"name": "WHO: Drowning fact sheet", "url": "https://www.who.int/news-room/fact-sheets/detail/drowning"}

# id, query, category, critical, threshold, label, fix, source
HAZARDS = [
    ("socket", "an electrical wall socket", "electric", True, 0.12, "Wall socket",
     "Fit socket covers or use shuttered sockets on every socket a baby can reach, and switch sockets off when not in use.", CPSC),
    ("switchboard", "a wall switch board with sockets", "electric", True, 0.10, "Switch board",
     "If the board is low enough to reach, cover the sockets and keep chargers and plugs out of it when not in use.", CPSC),
    ("power_strip", "an extension board power strip", "electric", True, 0.15, "Extension board",
     "Lift extension boards off the floor or put them in a closed cable box; switch off and unplug when not in use.", AAP),
    ("cable", "a tangle of cables", "electric", False, 0.15, "Loose cable",
     "Tuck cables behind furniture or into cord covers so they cannot be chewed or pulled.", AAP),
    ("charger", "a phone charger plugged into a socket", "electric", True, 0.12, "Charger left plugged in",
     "Unplug chargers when not in use; a baby can put the pin of a live charger in the mouth.", AAP),
    ("stairs", "a staircase", "fall", True, 0.10, "Stairs",
     "Fit a screw-mounted safety gate at the top and a gate at the bottom of the stairs.", AAP),
    ("window", "an open window", "fall", True, 0.10, "Window",
     "Fit window guards or stops so it opens no more than about 10 cm, and keep beds and furniture away from windows.", AAP),
    ("balcony", "a balcony railing", "fall", True, 0.10, "Balcony",
     "Keep the balcony door locked; check that railing gaps are too narrow for a baby and move anything climbable away.", AAP),
    ("bookshelf", "a tall bookshelf", "tip-over", False, 0.12, "Tall shelf",
     "Anchor tall shelves to the wall with anti-tip straps or brackets; keep heavy things on the bottom shelves.", CPSC_ANCHOR),
    ("tv", "a television", "tip-over", False, 0.12, "TV",
     "Mount the TV on the wall or anchor it to a low, stable stand; hide the cables.", CPSC_ANCHOR),
    ("dresser", "a chest of drawers", "tip-over", False, 0.12, "Chest of drawers",
     "Anchor it to the wall; a baby climbing the open drawers can pull it over.", CPSC_ANCHOR),
    ("lamp", "a floor lamp", "tip-over", False, 0.30, "Floor lamp",
     "Move floor lamps behind furniture or replace them while the baby is learning to stand.", AAP),
    ("table_corner", "a sharp table corner", "sharp", False, 0.30, "Sharp corner",
     "Fit soft corner guards on sharp corners at the baby's head height.", AAP),
    ("knife", "a kitchen knife", "sharp", True, 0.12, "Knife",
     "Store knives and scissors in a latched or high drawer.", AAP),
    ("scissors", "a pair of scissors", "sharp", False, 0.14, "Scissors",
     "Store scissors and blades in a latched or high drawer.", AAP),
    ("medicine", "medicine bottles and pills", "poison", True, 0.10, "Medicines",
     "Lock medicines away, up high, in their child-resistant packs; never leave them on tables or in bags.", AAP),
    ("cleaner", "a bottle of cleaning liquid", "poison", True, 0.10, "Cleaning products",
     "Store cleaners, detergents and pesticides locked or high, in their original bottles; fit cabinet latches.", CPSC),
    ("coins", "coins on a table", "choking", True, 0.12, "Coins",
     "Keep coins and anything smaller than a toilet-roll tube out of reach; they are choking hazards.", AAP),
    ("battery", "a small battery", "choking", True, 0.12, "Batteries",
     "Keep button batteries, remotes and spare batteries out of reach; a swallowed button battery burns within hours.", BATTERY),
    ("plastic_bag", "a plastic bag", "choking", False, 0.14, "Plastic bag",
     "Keep plastic bags and wrappers out of reach; knot them and throw them away.", AAP),
    ("small_toys", "small toys on the floor", "choking", False, 0.16, "Small objects",
     "Clear small objects off the floor and low tables; test them with a toilet-roll tube.", AAP),
    ("stove", "a gas stove", "burn", True, 0.10, "Stove",
     "Turn pot handles inward, cook on the back burners, fit knob covers and keep the baby out of the kitchen while cooking.", AAP),
    ("cylinder", "a red LPG gas cylinder", "burn", True, 0.15, "Gas cylinder",
     "Turn the regulator off after cooking and block the baby's access to the cylinder and pipe.", AAP),
    ("iron", "a clothes iron", "burn", True, 0.12, "Iron",
     "Unplug the iron and put it away out of reach as soon as you finish; keep the cord out of reach while it cools.", AAP),
    ("kettle", "an electric kettle", "burn", False, 0.12, "Kettle",
     "Keep kettles and their cords at the back of the counter; never hold hot drinks while holding the baby.", AAP),
    ("heater", "a room heater", "burn", True, 0.16, "Room heater",
     "Guard the heater, keep it out of the baby's room or switched off, and keep cords out of reach.", AAP),
    ("candle", "a diya lamp flame", "burn", False, 0.09, "Flame",
     "Keep candles, diyas and lighters out of reach and never leave a flame alone.", AAP),
    ("bucket", "a bucket of water", "water", True, 0.10, "Bucket",
     "Never leave water standing in buckets or tubs; empty them and store them upside down. A baby can drown in a few centimetres of water.", WHO_DROWN),
    ("toilet", "a toilet", "water", False, 0.12, "Toilet",
     "Keep the toilet lid down and the bathroom door closed; fit a lid lock.", WHO_DROWN),
    ("tub", "a bathtub", "water", False, 0.12, "Bathtub",
     "Empty the tub right after use and never leave the baby alone in or near water.", WHO_DROWN),
    ("blind_cord", "a hanging curtain cord", "strangle", True, 0.14, "Hanging cord",
     "Tie up or cut blind and curtain cords, and keep the cot away from windows.", AAP),
    ("plant", "a potted plant", "poison", False, 0.14, "House plant",
     "Check the plant is not poisonous and keep pots and soil out of reach.", AAP),
]

def encode(text):
    ids = TOK.encode(text).ids[:16]
    if ids[-1] != 49407:  # keep <|endoftext|> even if truncated
        ids[-1] = 49407
    return ids, [1] * len(ids) + [0] * (16 - len(ids)), ids + [0] * (16 - len(ids))

TEXT = os.path.join(os.path.dirname(__file__), "..", "work/models/split/owl_text.onnx")

def main():
    import numpy as np, onnxruntime as ort
    text = ort.InferenceSession(TEXT, providers=["CPUExecutionProvider"])
    out = []
    for hid, query, cat, critical, thr, label, fix, src in HAZARDS:
        _, mask, padded = encode(f"a photo of {query}")
        embed = text.run(None, {"input_ids": np.array([padded], np.int32),
                                "attention_mask": np.array([mask], np.int32)})[0].reshape(-1)
        out.append({"id": hid, "query": query, "category": cat, "critical": critical, "threshold": thr,
                    "label": label, "fix": fix, "source": src, "input_ids": padded, "attention_mask": mask,
                    "embed": [round(float(v), 6) for v in embed]})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump({"version": 2, "prompt": "a photo of {query}", "hazards": out}, open(OUT, "w"), indent=1)
    print(f"{len(out)} hazards -> {OUT}")

if __name__ == "__main__":
    main()
