"""
Ad-hoc inference script: runs a real image through the currently-exported
material_classifier_v1.tflite using the exact preprocessing MaterialClassifierTierImpl.kt uses
on-device (resize 224x224 bilinear, MobileNetV2 [-1,1] normalization), so results here should
match what the Android app would actually show.

Usage:
    venv/Scripts/python.exe predict.py <image_path> [<image_path> ...]
"""
import pathlib
import sys

import numpy as np
import tensorflow as tf
from PIL import Image

ASSETS_DIR = pathlib.Path(__file__).parents[2] / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "material_classifier_v1.tflite"
LABELS_PATH = ASSETS_DIR / "material_classifier_labels.txt"
INPUT_SIZE = 224


def load_labels():
    return LABELS_PATH.read_text().strip().splitlines()


def preprocess(image_path):
    img = Image.open(image_path).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32)
    arr = (arr - 127.5) / 127.5  # matches NormalizeOp(127.5f, 127.5f) in the Kotlin ImageProcessor
    return np.expand_dims(arr, axis=0)


def predict(interpreter, labels, image_path):
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    interpreter.set_tensor(input_details[0]["index"], preprocess(image_path))
    interpreter.invoke()
    probs = interpreter.get_tensor(output_details[0]["index"])[0]
    ranked = sorted(zip(labels, probs), key=lambda x: -x[1])
    return ranked


def main():
    labels = load_labels()
    interpreter = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interpreter.allocate_tensors()

    for image_path in sys.argv[1:]:
        print(f"\n=== {image_path} ===")
        ranked = predict(interpreter, labels, image_path)
        for label, prob in ranked:
            marker = " <-- CONFIDENCE_THRESHOLD (0.6)" if prob >= 0.6 and label == ranked[0][0] else ""
            print(f"  {label:15s} {prob:.4f}{marker}")
        top_label, top_prob = ranked[0]
        verdict = f"{top_label} ({top_prob:.1%})" if top_prob >= 0.6 else "below 0.6 threshold -> classify() returns null -> falls through to next tier"
        print(f"  => on-device classifier would report: {verdict}")


if __name__ == "__main__":
    main()
