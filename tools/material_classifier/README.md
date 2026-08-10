# Material classifier training

Fine-tunes MobileNetV2 (ImageNet transfer learning) on TrashNet + a Kaggle recyclables
dataset, remapped to this app's `MaterialType` taxonomy, and exports a quantized `.tflite` +
label file straight into `app/src/main/assets/`.

## Setup

TensorFlow does not yet support Python 3.14 — use 3.11.

```
py -3.11 -m venv venv
venv\Scripts\python.exe -m pip install -r requirements.txt
```

`requirements.txt` pins `tensorflow-cpu==2.16.1`, but TF 2.16's default Keras 3 TFLite
converter path hits an MLIR bug converting MobileNetV2 (`missing attribute 'value'` on the
`Conv1` op) on this platform. Downgrade in the venv before training:

```
venv\Scripts\python.exe -m pip install "tensorflow-cpu==2.15.1"
```

## Get the datasets

**TrashNet** (MIT licensed, ~2527 images across cardboard/glass/metal/paper/plastic/trash):

```
curl -L -o dataset-resized.zip https://huggingface.co/datasets/garythung/trashnet/resolve/main/dataset-resized.zip
unzip dataset-resized.zip -d dataset-resized
```

**Kaggle `alistairking/recyclable-and-household-waste-classification`** (MIT licensed, 30
categories x 500 images each, split into `default`/`real_world` subfolders per category —
this is what makes `PLASTIC_FILM` a real trained class and gives `PLASTIC_JUG` dedicated
bottle/jug images instead of TrashNet's single mixed "plastic" bucket):

```
venv\Scripts\python.exe -m pip install kaggle
```

Requires a Kaggle account + API token at `~/.kaggle/kaggle.json` (Kaggle account → Settings →
API → Create New Token). **Windows/PowerShell gotcha**: if you hand-create this file with
`Set-Content`/`Out-File -Encoding utf8`, PowerShell 5.1's "utf8" adds a UTF-8 byte-order-mark
that breaks the JSON parser (`Expecting value: line 1 column 1`) — strip it or write the file
with a BOM-less tool.

```
venv\Scripts\python.exe -m kaggle datasets download -d alistairking/recyclable-and-household-waste-classification -p .
unzip recyclable-and-household-waste-classification.zip -d kaggle-waste
```

## Train

```
venv\Scripts\python.exe train.py
```

Two-phase training: Phase 1 trains just the classifier head on a frozen MobileNetV2 backbone;
Phase 2 unfreezes the backbone's last `FINE_TUNE_UNFREEZE_LAYERS` layers (BatchNorm layers stay
frozen throughout to avoid destabilizing on a still-modest dataset) and continues at a much
lower learning rate so the backbone's own features adapt to this data, not just the head.
Training augmentation also does a resize-then-random-crop (not just flip/brightness/contrast)
to approximate variable framing, since both source datasets lean toward clean/centered photos.
The script evaluates both phases and exports whichever one actually measured better on the
held-out split — it does not assume the later phase always wins.

`load_image` decodes with `tf.io.decode_image` (not `decode_jpeg`) since TrashNet is JPEG and
the Kaggle dataset is PNG.

Prints validation accuracy, a confusion matrix, and per-class precision/recall for each phase,
then writes:
- `app/src/main/assets/material_classifier_v1.tflite`
- `app/src/main/assets/material_classifier_labels.txt` (one `MaterialType` name per line, in
  the model's output-index order — the Android-side classifier reads this file directly)

### Current results (fine-tuned, exported model)

Trained on TrashNet's cardboard/metal/glass/paper/trash folders (its ambiguous single "plastic"
folder is deliberately excluded) plus all 30 Kaggle categories, remapped per
`TRASHNET_FOLDER_TO_MATERIAL`/`KAGGLE_FOLDER_TO_MATERIAL` in `train.py`. `glass`/`paper` were
promoted from the `OTHER` catch-all to their own classes (per the City of Madison's curbside
guidelines — see `tools/madison_guide/`) — this makes the task strictly harder since `OTHER` no
longer soaks up those photos as an easy bucket. Overall validation accuracy: **89.6%** (down from
93.1% on the previous 5-class run — an expected tradeoff for two more classes, not a regression
in the underlying recipe).

| Class | Precision | Recall |
|---|---|---|
| CARDBOARD | 0.942 | 0.925 |
| GLASS | 0.883 | 0.945 |
| METAL_CAN | 0.896 | 0.913 |
| OTHER | 0.956 | 0.854 |
| PAPER | 0.790 | 0.913 |
| PLASTIC_FILM | 0.933 | 0.900 |
| PLASTIC_JUG | 0.870 | 0.902 |

`PAPER` is the weakest class (0.790 precision) — the confusion matrix shows `OTHER` items
misclassified as `PAPER` 82 times and `CARDBOARD` misclassified as `PAPER` 17 times, which
tracks: thin paper and thin cardboard share a lot of visual texture, and the Kaggle `OTHER`
categories (food waste, clothing, etc.) apparently include some paper-adjacent packaging.
`GLASS` also confuses with `PLASTIC_JUG` in both directions (7/20 misclassifications) — clear
glass and clear plastic bottles look similar to a coarse classifier at this resolution.

`PLASTIC_FILM` is a genuine trained class for the first time as of the prior run (TrashNet had
zero film examples, so it was previously excluded from the model's output space entirely, not
just weak).

### Hue/saturation augmentation (this run)

Both source datasets' `CARDBOARD` photos are almost entirely natural brown/tan corrugated stock,
so `train.py`'s augmentation now also jitters hue (`±0.08`) and saturation (`0.6x-1.4x`) to stop
the model from shortcutting on color. Overall validation accuracy is flat (**89.1%** vs. 89.6% —
noise at this dataset size, not a regression): `CARDBOARD` precision moved 0.942 → 0.903 but
recall moved 0.925 → 0.936. The validation set itself is drawn from the same two datasets, so it
can't actually measure whether color robustness improved — it has no dark/non-brown cardboard
examples either. Real-world spot check: a photo of black chipboard-style cardboard that the
*previous* (pre-augmentation) model already called correctly at 81.2% confidence now scores
96.2% with this run — encouraging, but one photo isn't a validation set.

**Caveat that applies to every number on this page**: it's measured against a held-out split of
the *training* datasets (clean, largely studio-lit photos), never against real phone-camera scans
from this app. A model can look great here and still miss on a live scan frame with different
resolution, exposure, or framing than anything in TrashNet/Kaggle — see the debug frame capture
in `ScannerViewModel` for closing that gap with real on-device frames.

## Product-level granularity (current shipped model)

Telling a user *what to do* with an item ("flatten before recycling") builds less trust than
also telling them *what the model thinks the item is* ("looks like a plastic detergent
bottle") — so the shipped model was retrained at product-level granularity via
`train_granular_experiment.py` (despite the "experiment" name — see below) instead of
`train.py`'s coarse `MaterialType` remap. It trains on the Kaggle dataset alone (TrashNet has no
per-product folders, only coarse cardboard/glass/metal/paper/trash, so it can't contribute to
this taxonomy) at close to the Kaggle folders' native granularity, collapsing only:
- the 11 folders not on Madison's curbside-recyclable list into one `OTHER` catch-all (same
  reasoning as `train.py`'s `OTHER` bucket — the app's message is identical for all of them)
- `aluminum_food_cans`/`steel_food_cans` into one `food_can` (a food can's metal isn't visually
  distinguishable — run 1 below scored 43-59% precision on this pair, confused with each other
  ~85% of the time)
- `cardboard_boxes`/`cardboard_packaging` into plain `cardboard` (not a real product distinction,
  just how Kaggle happened to bucket its photos — 59-60% precision, ~35% cross-confused)

`aluminum_soda_cans` stayed split out from the food-can merge since it was already cleanly
learnable (77-81% precision/recall) — soda cans have a visually distinct shape/labeling from
food cans, unlike the food-can aluminum/steel distinction.

| Run | Classes | Overall accuracy | Notes |
|---|---|---|---|
| 1 | 20 (all Kaggle-native, no merges) | 82.8% | `aluminum_food_cans`/`steel_food_cans` (43-59% precision) and `cardboard_boxes`/`cardboard_packaging` (59-60%) dragged the average down |
| 2 (shipped) | 18 (food-can + cardboard merges) | 86.3% | `cardboard` 91.9%/91.0%, `food_can` 91.6%/87.5% — both fixed cleanly |

Run 2's remaining soft spot: the four paper subtypes (`magazines`/`newspaper`/`office_paper`/
`paper_cups`) got noisier than run 1 (e.g. `paper_cups` precision 67.2% → 61.5%), mostly `OTHER`
photos bleeding into `paper_cups` (46/1100). Likely cause: `food_can`/`cardboard` doubled to 200
training examples each post-merge, shifting class-weight balance away from the four
still-100-example paper classes. Not fixed yet — the app's `ProductCategory`-level confidence
gate (stricter than the `MaterialType`-level one, see `MaterialClassifierTierImpl`) means a shaky
paper-subtype guess just shows no product label rather than a wrong one, falling back to the
coarse `PAPER` rule either way.

The app's `com.ecomadison.app.domain.model.ProductCategory` enum must stay in sync with this
model's exact label set (`FOOD_CAN`, no `CARDBOARD_*` entries since plain `cardboard` falls back
to a bare `MaterialType` match rather than a `ProductCategory`) -- see `ClassifierLabel.kt`'s
two-tier parsing.

## Known limitations (current)

- `DRINK_CARTON` is the only `MaterialType` **not in the model's output space** — the Kaggle
  dataset has no drink-carton/tetra-pak category. It's still reachable via the OCR tier
  (brand-text keyword match) and, once implemented, the cloud vision backup tier.
- Small rigid plastic items that are neither jug-shaped nor film (straws, cutlery, cup lids)
  are mapped to `OTHER` rather than forced into `PLASTIC_JUG`/`PLASTIC_FILM` — correct for
  keeping those two classes visually coherent, but it means the model will never confidently
  call out e.g. a plastic straw as anything other than `OTHER`.
- The four paper product subtypes are the model's weakest area (see the granularity section
  above) — a paper item's coarse `MaterialType` is usually still right, but the specific
  `ProductCategory` guess (magazine vs. newspaper vs. office paper vs. paper cup) is the least
  reliable of the trained set.
- The Kaggle dataset leans toward clean, well-lit, mostly-uncluttered photos (its `real_world`
  subfolders help but are still a minority of each category). Real-world accuracy in an actual
  basement recycling room will likely be lower than the validation numbers above until the model
  is topped up with locally-collected Madison-specific images.
