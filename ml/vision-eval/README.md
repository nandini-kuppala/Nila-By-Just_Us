# vision-eval — the labelled set behind the hazard rules

Eighteen photographs of real babies, eight safe and ten with a hazard in reach,
used to measure what the camera lane says about danger. They are the reason
`HazardRules.kt` exists in the shape it does: every rule in that file is here as
a photograph that the model got wrong without it.

```
images/safe/      8 photographs - crawling, sleeping, a meal, a bath, toys, a beach
images/danger/   10 photographs - stairs, cables, an open appliance, a sink
labels.csv        what each photograph is, and which rule it exercises
score.py          the guard rails in Python, and the confusion matrix
images/CREDITS.md where each photograph came from and its licence
```

## What it measured

Each stage, on the same eighteen photographs, on the phone:

| | dangers caught | missed | false alarms | correct |
|---|---|---|---|---|
| Free-text prompt, model's own verdict | 7 | 3 | 4 | 11/18 |
| The four-field form, model's own verdict | 9 | 1 | 3 | 14/18 |
| The form, plus the guard rails | 9 | 1 | 0 | **17/18** |

The guard rails take out every false alarm and keep every catch. The remaining
miss is `d10_climbing_onto_sink.jpg`, where the model describes a baby looking at
its reflection and never mentions the climbing: no rule can rescue a hazard that
was never named, which is the honest limit of this approach.

**The rules were written after seeing these answers.** That is how they were
derived and it is also their weakness, so the number above is a floor on a set
the rules have seen, not an estimate of new rooms. New photographs are the only
way to improve on it.

## Running it

The app writes one line per look to logcat:

```
adb logcat -s WatchService | grep "look ("
```

Collect `image,reply` pairs into a CSV and score them:

```bash
python score.py answers.csv
```

`answers-2026-09-13.csv` is the run the table above was measured from, kept so the
number can be checked without a phone:

```bash
python score.py answers-2026-09-13.csv
# caught 9 of 10 dangers, 0 false alarms, 17/18 correct
```

`score.py` is a direct port of `HazardRules.kt`. If you change one, change the
other and run both — the Kotlin side is covered by
`android/app/src/test/java/com/nila/vision/HazardRulesTest.kt`, whose test names
are the same cases as the rows in `labels.csv`.

## Adding photographs

Anything with a baby and a visible hazard, or a baby and a near-miss that should
*not* alarm. The second kind is the more useful: the failure mode of a small
vision model is confident alarm, so the set needs more baths, more teething toys
and more meals than it needs more staircases. Record the source and licence of
each in `images/CREDITS.md`.
