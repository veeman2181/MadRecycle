"""
Experiment: how well does MobileNetV2 do at the Kaggle dataset's *native* per-product
granularity, instead of the coarse MaterialType remap that train.py performs?

This does NOT touch the shipped app model/assets. It trains a separate classifier over:
- the 19 Kaggle folders that map to something on Madison's curbside-recyclable list, kept as
  their own individual classes (see RECYCLABLE_FOLDERS below) -- except for two pairs merged
  per MERGE_INTO below, based on run 1's confusion matrix
- the remaining 11 non-recyclable Kaggle folders, collapsed into one OTHER catch-all class,
  since the app's message for all of them is the same ("not accepted") regardless of which one
  it is -- no reason to spend model capacity distinguishing food waste from a shoe.

Run 1 (no merges) scored 82.8% overall but that average hid two structurally-unlearnable splits:
aluminum_food_cans vs. steel_food_cans (43%/59% precision, ~85% cross-confused -- a food can's
metal isn't a visible feature) and cardboard_boxes vs. cardboard_packaging (59%/60% precision --
not a real product distinction, just how Kaggle's photos happened to be bucketed). Both pairs
are merged into one label each this run; aluminum_soda_cans stays split out from food cans since
it was already cleanly learnable (77% precision, 81% recall in run 1).

TrashNet is excluded entirely: it only has coarse cardboard/glass/metal/paper/trash labels, so
its images can't be assigned to any of the fine-grained classes without guessing.

Same training recipe as train.py (two-phase transfer learning + fine-tuning, same augmentation)
so the numbers are comparable to the 7-class run documented in README.md. Writes results to
granular_experiment/ next to this script, not into app/src/main/assets/.

Usage:
    venv\\Scripts\\python.exe train_granular_experiment.py
"""
import pathlib
import random

import numpy as np
import tensorflow as tf

SEED = 1337
random.seed(SEED)
tf.random.set_seed(SEED)

IMG_SIZE = 224
BATCH_SIZE = 32
EPOCHS = 15
VAL_SPLIT = 0.2
FINE_TUNE_EPOCHS = 10
FINE_TUNE_UNFREEZE_LAYERS = 30

KAGGLE_DIR = pathlib.Path(__file__).parent / "kaggle-waste" / "images" / "images"
OUT_DIR = pathlib.Path(__file__).parent / "granular_experiment"

# Kept as individual fine-grained classes -- these are the Kaggle folders that correspond to
# something on Madison's curbside-recyclable list (see tools/madison_guide/), grouped in
# comments by the coarse MaterialType bucket they currently collapse into.
RECYCLABLE_FOLDERS = {
    # CARDBOARD
    "cardboard_boxes",
    "cardboard_packaging",
    # METAL_CAN
    "aluminum_food_cans",
    "aluminum_soda_cans",
    "steel_food_cans",
    "aerosol_cans",
    # PLASTIC_JUG
    "plastic_water_bottles",
    "plastic_soda_bottles",
    "plastic_detergent_bottles",
    "plastic_food_containers",
    # PLASTIC_FILM
    "plastic_shopping_bags",
    "plastic_trash_bags",
    # GLASS
    "glass_beverage_bottles",
    "glass_cosmetic_containers",
    "glass_food_jars",
    # PAPER
    "magazines",
    "newspaper",
    "office_paper",
    "paper_cups",
}

# Collapsed into one negative class -- not on Madison's curbside list, so the app's message is
# identical ("not accepted") no matter which of these it is.
OTHER_LABEL = "OTHER"

# Pairs whose split run 1 showed is either unlearnable from a photo alone (food can metal) or not
# a real product distinction (cardboard sub-type) -- merged into one label per pair this run.
MERGE_INTO = {
    "aluminum_food_cans": "food_can",
    "steel_food_cans": "food_can",
    "cardboard_boxes": "cardboard",
    "cardboard_packaging": "cardboard",
}


def folder_to_label(folder_name):
    if folder_name not in RECYCLABLE_FOLDERS:
        return OTHER_LABEL
    return MERGE_INTO.get(folder_name, folder_name)


def build_file_label_lists():
    paths, labels = [], []
    for folder_dir in sorted(KAGGLE_DIR.iterdir()):
        if not folder_dir.is_dir():
            continue
        label = folder_to_label(folder_dir.name)
        for f in sorted(folder_dir.glob("**/*.png")):
            paths.append(str(f))
            labels.append(label)
    return paths, labels


def stratified_split(paths, labels, class_names):
    train_paths, train_labels, val_paths, val_labels = [], [], [], []
    rng = random.Random(SEED)
    for cls in class_names:
        cls_indices = [i for i, l in enumerate(labels) if l == cls]
        rng.shuffle(cls_indices)
        n_val = max(1, int(len(cls_indices) * VAL_SPLIT))
        val_idx = set(cls_indices[:n_val])
        for i in cls_indices:
            if i in val_idx:
                val_paths.append(paths[i])
                val_labels.append(labels[i])
            else:
                train_paths.append(paths[i])
                train_labels.append(labels[i])
    return train_paths, train_labels, val_paths, val_labels


def make_dataset(paths, label_indices, training):
    path_ds = tf.data.Dataset.from_tensor_slices(paths)
    label_ds = tf.data.Dataset.from_tensor_slices(label_indices)

    def load_image(path):
        raw = tf.io.read_file(path)
        img = tf.io.decode_image(raw, channels=3, expand_animations=False)
        img.set_shape([None, None, 3])
        if training:
            img = tf.image.resize(img, [int(IMG_SIZE * 1.2), int(IMG_SIZE * 1.2)])
            img = tf.image.random_crop(img, [IMG_SIZE, IMG_SIZE, 3])
            img = tf.image.random_flip_left_right(img)
            img = tf.image.random_brightness(img, max_delta=0.15)
            img = tf.image.random_contrast(img, lower=0.85, upper=1.15)
            img = tf.image.random_hue(img, max_delta=0.08)
            img = tf.image.random_saturation(img, lower=0.6, upper=1.4)
            img = tf.clip_by_value(img, 0.0, 255.0)
        else:
            img = tf.image.resize(img, [IMG_SIZE, IMG_SIZE])
        img = tf.keras.applications.mobilenet_v2.preprocess_input(img)
        return img

    image_ds = path_ds.map(load_image, num_parallel_calls=tf.data.AUTOTUNE)
    ds = tf.data.Dataset.zip((image_ds, label_ds))
    if training:
        ds = ds.shuffle(buffer_size=len(paths), seed=SEED)
    ds = ds.batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)
    return ds


def evaluate(model, val_ds, val_indices, class_names, phase_label):
    val_probs = model.predict(val_ds)
    val_preds = np.argmax(val_probs, axis=1)
    val_true = np.array(val_indices)

    overall_acc = float(np.mean(val_preds == val_true))
    print(f"\n=== [{phase_label}] Validation accuracy: {overall_acc:.4f} ({int(np.sum(val_preds == val_true))}/{len(val_true)}) ===")

    n = len(class_names)
    confusion = np.zeros((n, n), dtype=int)
    for t, p in zip(val_true, val_preds):
        confusion[t, p] += 1
    print("=== Confusion matrix (rows=true, cols=predicted) ===")
    header = "        " + "".join(f"{c[:10]:>12}" for c in class_names)
    print(header)
    for i, c in enumerate(class_names):
        row = "".join(f"{confusion[i, j]:>12}" for j in range(n))
        print(f"{c[:10]:>8}{row}")

    print("=== Per-class precision/recall ===")
    for i, c in enumerate(class_names):
        tp = confusion[i, i]
        fn = confusion[i, :].sum() - tp
        fp = confusion[:, i].sum() - tp
        precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        print(f"  {c}: precision={precision:.3f} recall={recall:.3f} support={confusion[i, :].sum()}")

    return overall_acc


def main():
    paths, labels = build_file_label_lists()
    class_names = sorted(set(labels))
    label_to_index = {c: i for i, c in enumerate(class_names)}
    print(f"Classes (index order): {class_names}")
    print(f"Total classes: {len(class_names)}")
    print(f"Total images: {len(paths)}")
    for c in class_names:
        print(f"  {c}: {labels.count(c)}")

    train_paths, train_labels, val_paths, val_labels = stratified_split(paths, labels, class_names)
    train_indices = [label_to_index[l] for l in train_labels]
    val_indices = [label_to_index[l] for l in val_labels]
    print(f"Train: {len(train_paths)}  Val: {len(val_paths)}")

    train_ds = make_dataset(train_paths, train_indices, training=True)
    val_ds = make_dataset(val_paths, val_indices, training=False)

    class_counts = {c: labels.count(c) for c in class_names}
    total = len(labels)
    class_weight = {
        label_to_index[c]: total / (len(class_names) * count) for c, count in class_counts.items()
    }
    print(f"Class weights (imbalance correction): {class_weight}")

    base_model = tf.keras.applications.MobileNetV2(
        input_shape=(IMG_SIZE, IMG_SIZE, 3), include_top=False, weights="imagenet"
    )
    base_model.trainable = False

    x = base_model.output
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(len(class_names), activation="softmax")(x)
    model = tf.keras.Model(base_model.input, outputs)

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.summary()

    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_accuracy", patience=4, restore_best_weights=True
    )
    model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=EPOCHS,
        class_weight=class_weight,
        callbacks=[early_stop],
    )

    phase1_acc = evaluate(model, val_ds, val_indices, class_names, "Phase 1: frozen backbone")
    phase1_weights = model.get_weights()

    base_model.trainable = True
    for layer in base_model.layers[:-FINE_TUNE_UNFREEZE_LAYERS]:
        layer.trainable = False
    for layer in base_model.layers:
        if isinstance(layer, tf.keras.layers.BatchNormalization):
            layer.trainable = False

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    fine_tune_early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_accuracy", patience=3, restore_best_weights=True
    )
    model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=FINE_TUNE_EPOCHS,
        class_weight=class_weight,
        callbacks=[fine_tune_early_stop],
    )

    phase2_acc = evaluate(model, val_ds, val_indices, class_names, "Phase 2: fine-tuned top layers")

    if phase2_acc >= phase1_acc:
        print(f"\nExporting Phase 2 model ({phase2_acc:.4f} >= {phase1_acc:.4f})")
    else:
        print(f"\nPhase 2 regressed ({phase2_acc:.4f} < {phase1_acc:.4f}) -- exporting Phase 1 model instead")
        model.set_weights(phase1_weights)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tflite_path = OUT_DIR / "granular_experiment_v1.tflite"
    labels_path = OUT_DIR / "granular_experiment_labels.txt"
    tflite_path.write_bytes(tflite_model)
    labels_path.write_text("\n".join(class_names) + "\n")

    print(f"\nWrote {tflite_path} ({len(tflite_model) / 1024:.1f} KB)")
    print(f"Wrote {labels_path}: {class_names}")


if __name__ == "__main__":
    main()
