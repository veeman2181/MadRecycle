package com.ecomadison.app.ml

import com.ecomadison.app.domain.model.MaterialType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrTierImpl @Inject constructor(
    private val keywordDictionary: OcrKeywordDictionary
) : OcrTier {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeMaterial(image: InputImage): MaterialType? {
        val result = recognizer.process(image).await()
        return keywordDictionary.match(result.text)
    }
}
