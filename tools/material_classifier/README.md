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

Trained on 17,045 images (TrashNet's cardboard/metal/glass/paper/trash folders — its ambiguous
single "plastic" folder is deliberately excluded — plus all 30 Kaggle categories remapped per
`KAGGLE_FOLDER_TO_MATERIAL` in `train.py`). Overall validation accuracy: **93.1%** (up from
87.7% on TrashNet alone with the same fine-tuning recipe — the number to beat if you change
the recipe or data again).

| Class | Precision | Recall |
|---|---|---|
| CARDBOARD | 0.885 | 0.964 |
| METAL_CAN | 0.909 | 0.917 |
| OTHER | 0.960 | 0.929 |
| PLASTIC_FILM | 0.863 | 0.975 |
| PLASTIC_JUG | 0.886 | 0.910 |

`PLASTIC_FILM` is a genuine trained class for the first time (TrashNet had zero film examples,
so it was previously excluded from the model's output space entirely, not just weak).
`PLASTIC_JUG` precision moved 0.743 (baseline) → 0.857 (fine-tuning + crop augmentation, no new
data) → 0.886 (this run, real jug/bottle-only training images instead of TrashNet's mixed bag).

## Known limitations (current)

- `DRINK_CARTON` is the only `MaterialType` **not in the model's output space** — neither
  TrashNet nor the Kaggle dataset has a drink-carton/tetra-pak category. It's still reachable
  via the OCR tier (brand-text keyword match) and, once implemented, the cloud vision backup
  tier.
- Small rigid plastic items that are neither jug-shaped nor film (straws, cutlery, cup lids)
  are mapped to `OTHER` rather than forced into `PLASTIC_JUG`/`PLASTIC_FILM` — correct for
  keeping those two classes visually coherent, but it means the model will never confidently
  call out e.g. a plastic straw as anything other than `OTHER`.
- Both source datasets lean toward clean, well-lit, mostly-uncluttered photos (TrashNet
  entirely so; the Kaggle dataset's `real_world` subfolders help but are still a minority of
  each category). Real-world accuracy in an actual basement recycling room will likely be
  lower than the validation numbers above until the model is topped up with locally-collected
  Madison-specific images.
