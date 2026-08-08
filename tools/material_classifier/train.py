"""
Fine-tunes MobileNetV2 (ImageNet transfer learning) on TrashNet + a Kaggle waste-classification
dataset, remapped to this app's MaterialType taxonomy, and exports a quantized TFLite classifier
+ label file straight into the Android app's assets directory.

Usage:
    venv/Scripts/python.exe train.py

Expects:
- ./dataset-resized/dataset-resized/{cardboard,glass,metal,paper,plastic,trash}/*.jpg
  (download via: curl -L -o dataset-resized.zip \
    https://huggingface.co/datasets/garythung/trashnet/resolve/main/dataset-resized.zip
    && unzip dataset-resized.zip -d dataset-resized)
- ./kaggle-waste/images/images/<category>/{default,real_world}/*.png
  (download via: kaggle datasets download -d alistairking/recyclable-and-household-waste-classification
    && unzip recyclable-and-household-waste-classification.zip -d kaggle-waste
    -- requires a Kaggle account + API token at ~/.kaggle/kaggle.json)
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
FINE_TUNE_UNFREEZE_LAYERS = 30  # last ~30 of MobileNetV2's 154 layers (roughly its last block)

TRASHNET_DIR = pathlib.Path(__file__).parent / "dataset-resized" / "dataset-resized"
KAGGLE_DIR = pathlib.Path(__file__).parent / "kaggle-waste" / "images" / "images"
ASSETS_DIR = pathlib.Path(__file__).parents[2] / "app" / "src" / "main" / "assets"

# TrashNet's folders -> this app's MaterialType enum (domain/model/MaterialType.kt).
# TrashNet's "plastic" folder is deliberately NOT included: it mixes bottles/jugs/bags/misc
# with no way to separate rigid containers from film, and the Kaggle dataset below has
# cleanly-labeled subcategories for exactly that distinction — mixing the ambiguous TrashNet
# folder back in would reintroduce label noise into PLASTIC_JUG/PLASTIC_FILM.
TRASHNET_FOLDER_TO_MATERIAL = {
    "cardboard": "CARDBOARD",
    "metal": "METAL_CAN",
    "glass": "OTHER",
    "paper": "OTHER",
    "trash": "OTHER",
}

# Kaggle's 30 categories -> MaterialType. This is what makes PLASTIC_FILM a real trained class
# for the first time (TrashNet had zero film examples) and gives PLASTIC_JUG dedicated
# bottle/container images instead of TrashNet's mixed "plastic" bucket. Small rigid plastic
# items that are neither jug-shaped nor film (straws, cutlery, cup lids) fall to OTHER rather
# than distorting either class's visual identity.
KAGGLE_FOLDER_TO_MATERIAL = {
    "cardboard_boxes": "CARDBOARD",
    "cardboard_packaging": "CARDBOARD",
    "aluminum_food_cans": "METAL_CAN",
    "aluminum_soda_cans": "METAL_CAN",
    "steel_food_cans": "METAL_CAN",
    "aerosol_cans": "METAL_CAN",
    "plastic_water_bottles": "PLASTIC_JUG",
    "plastic_soda_bottles": "PLASTIC_JUG",
    "plastic_detergent_bottles": "PLASTIC_JUG",
    "plastic_food_containers": "PLASTIC_JUG",
    "plastic_shopping_bags": "PLASTIC_FILM",
    "plastic_trash_bags": "PLASTIC_FILM",
    "glass_beverage_bottles": "OTHER",
    "glass_cosmetic_containers": "OTHER",
    "glass_food_jars": "OTHER",
    "magazines": "OTHER",
    "newspaper": "OTHER",
    "office_paper": "OTHER",
    "paper_cups": "OTHER",
    "clothing": "OTHER",
    "coffee_grounds": "OTHER",
    "eggshells": "OTHER",
    "food_waste": "OTHER",
    "shoes": "OTHER",
    "styrofoam_cups": "OTHER",
    "styrofoam_food_containers": "OTHER",
    "tea_bags": "OTHER",
    "plastic_straws": "OTHER",
    "plastic_cup_lids": "OTHER",
    "disposable_plastic_cutlery": "OTHER",
}


def build_file_label_lists():
    paths, labels = [], []
    for folder, material in TRASHNET_FOLDER_TO_MATERIAL.items():
        for f in sorted((TRASHNET_DIR / folder).glob("*.jpg")):
            paths.append(str(f))
            labels.append(material)
    for folder, material in KAGGLE_FOLDER_TO_MATERIAL.items():
        for f in sorted((KAGGLE_DIR / folder).glob("**/*.png")):
            paths.append(str(f))
            labels.append(material)
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
        # TrashNet is JPEG, the Kaggle dataset is PNG — decode_image handles both uniformly.
        img = tf.io.decode_image(raw, channels=3, expand_animations=False)
        img.set_shape([None, None, 3])
        if training:
            # TrashNet is clean studio photography (centered object, plain background) — a
            # real basement scan won't be framed that tightly. Resize larger than the model
            # input then randomly crop back down to approximate variable framing/distance,
            # on top of flip/brightness/contrast, so the model sees more than one exact crop
            # of each of the ~2000 training images.
            img = tf.image.resize(img, [int(IMG_SIZE * 1.2), int(IMG_SIZE * 1.2)])
            img = tf.image.random_crop(img, [IMG_SIZE, IMG_SIZE, 3])
            img = tf.image.random_flip_left_right(img)
            img = tf.image.random_brightness(img, max_delta=0.15)
            img = tf.image.random_contrast(img, lower=0.85, upper=1.15)
            img = tf.clip_by_value(img, 0.0, 255.0)
        else:
            img = tf.image.resize(img, [IMG_SIZE, IMG_SIZE])
        # Bake MobileNetV2's own preprocessing (scale to [-1, 1]) into the pipeline so the
        # exported graph and Kotlin's TensorImage normalization agree on the same input contract.
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
    class_names = sorted(set(labels))  # deterministic label<->index order
    label_to_index = {c: i for i, c in enumerate(class_names)}
    print(f"Classes (index order): {class_names}")
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

    # Extend base_model's own input/output tensors directly (rather than calling base_model(...)
    # as a nested layer) so the exported graph is flat. TF 2.16/Keras 3's TFLite converter has a
    # known MLIR bug ("missing attribute 'value'" on MobileNetV2's Conv1 ReadVariableOp) when
    # converting a functional model that wraps another Model as a nested sub-model.
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

    # Phase 2: unfreeze the top of MobileNetV2 and fine-tune at a much lower LR so the
    # backbone's own features adapt to this dataset instead of only the classifier head.
    # BatchNorm layers stay frozen throughout — letting their running statistics drift on
    # ~2000 training images is a well-known way to destabilize fine-tuning on a small dataset.
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

    # Fine-tuning a large backbone on ~2000 images can overfit and regress val accuracy —
    # export whichever phase actually measured better rather than assuming the later phase won.
    if phase2_acc >= phase1_acc:
        print(f"\nExporting Phase 2 model ({phase2_acc:.4f} >= {phase1_acc:.4f})")
    else:
        print(f"\nPhase 2 regressed ({phase2_acc:.4f} < {phase1_acc:.4f}) — exporting Phase 1 model instead")
        model.set_weights(phase1_weights)

    # Augmentation lived only in the tf.data pipeline (never in the model graph), and
    # preprocess_input is already baked into that same pipeline for train *and* val, so
    # `model` itself expects a [-1, 1]-normalized 224x224x3 tensor — exactly what Kotlin's
    # TensorImage/ImageProcessor will produce. No second model or weight-copy needed.
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    tflite_path = ASSETS_DIR / "material_classifier_v1.tflite"
    labels_path = ASSETS_DIR / "material_classifier_labels.txt"
    tflite_path.write_bytes(tflite_model)
    labels_path.write_text("\n".join(class_names) + "\n")

    print(f"\nWrote {tflite_path} ({len(tflite_model) / 1024:.1f} KB)")
    print(f"Wrote {labels_path}: {class_names}")


if __name__ == "__main__":
    main()
