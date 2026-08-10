package com.ecomadison.app.ml

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaterialClassifierTierImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MaterialClassifierTier {

    private val interpreter: Interpreter by lazy {
        Interpreter(FileUtil.loadMappedFile(context, MODEL_ASSET_NAME))
    }

    /**
     * tools/material_classifier/train.py writes one label per line, in output-index order --
     * either a plain MaterialType name (current shipped model) or a ProductCategory name (see
     * [parseClassifierLabel]) once a finer-grained model is trained and swapped in.
     */
    private val labels: List<ClassifierLabel> by lazy {
        FileUtil.loadLabels(context, LABELS_ASSET_NAME).map(::parseClassifierLabel)
    }

    private val imageProcessor: ImageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            // Mirrors tf.keras.applications.mobilenet_v2.preprocess_input: [0,255] -> [-1,1].
            .add(NormalizeOp(127.5f, 127.5f))
            .build()
    }

    override suspend fun classify(bitmap: Bitmap): MaterialClassification? = withContext(Dispatchers.Default) {
        val tensorImage = imageProcessor.process(TensorImage(DataType.FLOAT32).apply { load(bitmap) })
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(tensorImage.buffer, output)

        val probabilities = output[0]
        val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return@withContext null
        val confidence = probabilities[bestIndex]
        if (confidence < MATERIAL_CONFIDENCE_THRESHOLD) return@withContext null

        val label = labels[bestIndex]
        // A finer product-level guess is inherently harder to get right than the coarse material
        // bucket (see tools/material_classifier/README.md's confusion pairs), so it's gated on a
        // stricter bar -- a confident-enough MaterialType guess can still surface with no product
        // label rather than a shaky one.
        val product = (label as? ClassifierLabel.Product)?.category?.takeIf { confidence >= PRODUCT_CONFIDENCE_THRESHOLD }
        MaterialClassification(label.materialType, confidence, product)
    }

    private companion object {
        const val MODEL_ASSET_NAME = "material_classifier_v1.tflite"
        const val LABELS_ASSET_NAME = "material_classifier_labels.txt"
        const val INPUT_SIZE = 224
        const val MATERIAL_CONFIDENCE_THRESHOLD = 0.6f
        const val PRODUCT_CONFIDENCE_THRESHOLD = 0.8f
    }
}
