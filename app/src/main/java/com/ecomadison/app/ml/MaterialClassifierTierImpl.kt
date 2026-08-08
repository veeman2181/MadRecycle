package com.ecomadison.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.ecomadison.app.domain.model.MaterialType
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

    /** tools/material_classifier/train.py writes one MaterialType name per line, in output-index order. */
    private val labels: List<MaterialType> by lazy {
        FileUtil.loadLabels(context, LABELS_ASSET_NAME).map { MaterialType.valueOf(it.trim()) }
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
        if (confidence >= CONFIDENCE_THRESHOLD) {
            MaterialClassification(labels[bestIndex], confidence)
        } else {
            null
        }
    }

    private companion object {
        const val MODEL_ASSET_NAME = "material_classifier_v1.tflite"
        const val LABELS_ASSET_NAME = "material_classifier_labels.txt"
        const val INPUT_SIZE = 224
        const val CONFIDENCE_THRESHOLD = 0.6f
    }
}
