"""Score the scene model's answers against the labelled set, on a laptop.

The app decides what to do about a hazard in `HazardRules.kt`. This is the same
rules in Python, so a prompt change can be measured against the eighteen
photographs without a phone in the loop, and so the number quoted in the
documentation can be reproduced by somebody who does not trust it.

    python score.py answers.csv

`answers.csv` is `image,reply` -- the image path relative to `images/`, and the
model's whole reply with newlines escaped as `\\n`. The app writes one line per
look to logcat under the tag `WatchService`; `--from-logcat` reads that form
instead.

Keep the two rule sets in step. If you change HazardRules.kt, change this, and
run both: the Kotlin unit tests in `app/src/test/java/com/nila/vision/` cover the
same cases.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

NEGATED = re.compile(
    r"^\W*(none|no|nothing|n/?a|not visible|unclear)\b"
    r"|no (immediate |visible |obvious |real )?(danger|hazard|risk)"
    r"|not (dangerous|a hazard|in danger|unsafe)"
    r"|nothing (dangerous|unsafe|of concern)",
    re.I,
)

FAMILIES = [
    ("stairs", r"stairs?|staircase|stairway|steps?|ladder"),
    (
        "electrical",
        r"cables?|cords?|wires?|plugs?|sockets?|outlets?|chargers?|power ?strips?|"
        r"extension|electric\w*|tv|television|computer|laptop|monitor|appliances?|microwave|heater|fan",
    ),
    ("heat", r"stove|oven|hob|fire|flames?|candles?|iron|kettle|boiling|hot (drink|water|cup|tea|coffee)"),
    ("sharp", r"knife|knives|scissors|blades?|razor|sharp|broken glass|needles?|nails?"),
    (
        "poison",
        r"medicine|medication|pills?|tablets?|chemicals?|detergent|bleach|cleaning|cleaner|"
        r"batter(y|ies)|plastic bags?",
    ),
    (
        "fall",
        r"edge|climb\w*|window|balcony|railing|counter|countertop|shelf|shelves|"
        r"table|bed|sofa|couch|sink|worktop",
    ),
    ("water", r"pool|bucket|toilet|bath ?tub|tub|water|pond|basin"),
    ("choking", r"small (object|item|toy|thing)s?|coins?|buttons?|marbles?|beads?|chok\w*|magnets?"),
]
FAMILIES = [(name, re.compile(rf"\b({words})\b", re.I)) for name, words in FAMILIES]

NEVER_BENIGN = {"stairs", "electrical", "heat", "sharp", "poison"}
ADULT = re.compile(
    r"\b(adult|parent|mother|mum|mom|father|dad|caregiver|carer|person|someone|woman|man|hand)\b", re.I
)
SUPERVISING = re.compile(r"\b(hold\w*|support\w*|bath\w*|wash\w*|help\w*|supervis\w*|carry\w*)\b", re.I)
FOOD_OR_TOY = re.compile(
    r"\b(toys?|rattle|teether|teething|dummy|pacifier|soother|food|eat\w*|snack|biscuit|cookie|"
    r"cracker|fruit|spoon|bottle|cup)\b",
    re.I,
)


def field(reply: str, name: str) -> str:
    match = list(re.finditer(rf"^[\s*#-]*{name}[\s*]*:[\s*]*(.*)$", reply, re.I | re.M))
    return match[-1].group(1).strip().strip("*").strip() if match else ""


def doing(reply: str) -> str:
    """The sentence about what the baby is doing, under either field name.

    The app's form calls it DOING. The runs this set was first measured with
    called it ACTIVITY, and those logs are still worth scoring -- so both are
    accepted rather than silently reading an empty string, which is what turns
    "in a bathtub being held by an adult" into an unsupervised drowning.
    """
    return field(reply, "DOING") or field(reply, "ACTIVITY")


def assess(hazard: str, context: str, model_said_danger: bool) -> tuple[str, str | None]:
    """Returns (level, family) exactly as HazardRules.assess does."""
    hazard = hazard.strip()
    negated = not hazard or bool(NEGATED.search(hazard))
    whole = f"{context} {hazard}"
    family = None if negated else next((n for n, r in FAMILIES if r.search(whole)), None)
    supervised = bool(ADULT.search(whole)) and bool(SUPERVISING.search(whole))

    if negated:
        return ("UNSURE" if model_said_danger else "SAFE"), None
    if family == "water" and supervised:
        return "SAFE", "water"
    if family == "choking" and FOOD_OR_TOY.search(whole):
        return "UNSURE", "choking"
    if model_said_danger and family is None:
        return "DANGER", "other"
    if model_said_danger:
        return "DANGER", family
    if family in NEVER_BENIGN:
        return "DANGER", family
    return "SAFE", family


def read_answers(path: Path) -> dict[str, str]:
    answers: dict[str, str] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.reader(handle):
            if len(row) < 2 or row[0].strip().lower() == "image":
                continue
            answers[row[0].strip()] = row[1].replace("\\n", "\n")
    return answers


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("answers", type=Path, help="image,reply CSV from a run")
    parser.add_argument("--labels", type=Path, default=Path(__file__).with_name("labels.csv"))
    args = parser.parse_args()

    answers = read_answers(args.answers)
    rows = list(csv.DictReader(args.labels.open(encoding="utf-8")))

    tp = fn = fp = tn = 0
    unsure = []
    print(f"{'image':44} {'expected':9} {'model':7} {'guard':7} family")
    for row in rows:
        reply = answers.get(row["image"])
        if reply is None:
            print(f"{row['image']:44} (no answer)")
            continue
        concern = field(reply, "CONCERN")
        model_danger = bool(concern) and not re.match(r"^(none|cannot tell)", concern, re.I)
        level, family = assess(
            field(reply, "HAZARD"),
            f"{doing(reply)} {field(reply, 'POSITION')}",
            model_danger,
        )
        expected_danger = row["expected"] == "danger"
        raised = level == "DANGER"
        tp += expected_danger and raised
        fn += expected_danger and not raised
        fp += (not expected_danger) and raised
        tn += (not expected_danger) and not raised
        if level == "UNSURE":
            unsure.append(row["image"])
        mark = "" if expected_danger == raised else "  <- wrong"
        print(
            f"{row['image']:44} {row['expected']:9} "
            f"{'DANGER' if model_danger else 'safe':7} {level:7} {family or '':10}{mark}"
        )

    total = tp + fn + fp + tn
    print()
    print(f"caught {tp} of {tp + fn} dangers, {fp} false alarms, {tp + tn}/{total} correct")
    if unsure:
        print(f"held back as unsure (never alerts): {', '.join(unsure)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
