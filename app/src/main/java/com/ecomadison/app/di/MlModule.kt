package com.ecomadison.app.di

import com.ecomadison.app.ml.BarcodeTier
import com.ecomadison.app.ml.BarcodeTierImpl
import com.ecomadison.app.ml.CloudVisionMaterialTier
import com.ecomadison.app.ml.CloudVisionMaterialTierImpl
import com.ecomadison.app.ml.MaterialClassifierTier
import com.ecomadison.app.ml.MaterialClassifierTierImpl
import com.ecomadison.app.ml.ObjectDetectionTier
import com.ecomadison.app.ml.ObjectDetectionTierImpl
import com.ecomadison.app.ml.OcrTier
import com.ecomadison.app.ml.OcrTierImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MlModule {

    @Binds
    @Singleton
    abstract fun bindBarcodeTier(impl: BarcodeTierImpl): BarcodeTier

    @Binds
    @Singleton
    abstract fun bindObjectDetectionTier(impl: ObjectDetectionTierImpl): ObjectDetectionTier

    @Binds
    @Singleton
    abstract fun bindOcrTier(impl: OcrTierImpl): OcrTier

    @Binds
    @Singleton
    abstract fun bindMaterialClassifierTier(impl: MaterialClassifierTierImpl): MaterialClassifierTier

    @Binds
    @Singleton
    abstract fun bindCloudVisionMaterialTier(impl: CloudVisionMaterialTierImpl): CloudVisionMaterialTier
}
