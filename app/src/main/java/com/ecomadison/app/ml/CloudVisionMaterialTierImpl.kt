package com.ecomadison.app.ml

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deliberate stub: no cloud vision vendor/API key has been chosen yet (SPEC.md §2). Always
 * returns null so the coordinator falls through to Tier 4 manual — this is intentional no-op
 * behavior, not a broken integration.
 *
 * TODO(vendor decision): once a provider is picked, send `bitmap` to its multimodal vision
 * endpoint prompted with this app's MaterialType taxonomy + Madison disposal rules, and parse
 * the structured response into a MaterialClassification. The interface, the network gating in
 * ScanPipelineCoordinator, and the INTERNET/ACCESS_NETWORK_STATE manifest permissions are
 * already wired up — only this method body and the DI binding's dependency need to change.
 */
@Singleton
class CloudVisionMaterialTierImpl @Inject constructor() : CloudVisionMaterialTier {
    override suspend fun classify(bitmap: Bitmap): MaterialClassification? = null
}
