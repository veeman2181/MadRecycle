package com.ecomadison.app.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import com.ecomadison.app.BuildConfig
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.ProductCategory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * On-device-free CV material classifier (§5.5 Tier 3.5, now the primary CV resolver --
 * see ScanPipelineCoordinator for why the on-device classifier is the fallback rather
 * than the other way around). Calls the vision proxy Lambda (backend/vision_proxy),
 * which holds the real Anthropic API key server-side; this class never sees it.
 *
 * Returns null (falls through to the on-device fallback) on any failure: no proxy URL
 * configured, no network, timeout, a non-2xx response, or a malformed body. The caller
 * is expected to wrap this call in its own timeout -- this class's own OkHttp
 * connect/read timeouts are a backstop, not the primary bound on how long the user waits.
 */
@Singleton
class CloudVisionMaterialTierImpl @Inject constructor() : CloudVisionMaterialTier {

    private val json = Json { ignoreUnknownKeys = true }
    private val api: VisionProxyApi? by lazy { buildApi() }

    override suspend fun classify(bitmap: Bitmap): MaterialClassification? {
        val api = api ?: return null
        return try {
            val requestJson = json.encodeToString(ClassifyRequest(imageBase64 = bitmap.toJpegBase64()))
            val responseBody = api.classify(
                secret = BuildConfig.CLOUD_VISION_PROXY_SECRET,
                request = requestJson.toRequestBody(JSON_MEDIA_TYPE)
            )
            val response = json.decodeFromString<ClassifyResponse>(responseBody.string())
            val materialType = MaterialType.valueOf(response.materialType)
            val productCategory = response.productCategory.takeIf { it != "NONE" }?.let(ProductCategory::valueOf)
            MaterialClassification(materialType, response.confidence, productCategory)
        } catch (e: IOException) {
            Log.w(TAG, "Cloud vision request failed", e)
            null
        } catch (e: HttpException) {
            Log.w(TAG, "Cloud vision request returned ${e.code()}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cloud vision returned an unparseable classification", e)
            null
        }
    }

    private fun buildApi(): VisionProxyApi? {
        val proxyUrl = BuildConfig.CLOUD_VISION_PROXY_URL
        if (proxyUrl.isBlank()) return null
        val baseUrl = if (proxyUrl.endsWith("/")) proxyUrl else "$proxyUrl/"

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        // No Retrofit converter factory needed -- RequestBody/ResponseBody are natively
        // supported @Body/return types, so JSON (de)serialization is done by hand above
        // with kotlinx.serialization directly.
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build()
            .create(VisionProxyApi::class.java)
    }

    private fun Bitmap.toJpegBase64(): String {
        val scaled = downscaleIfNeeded(this)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    /** Keeps upload size (and therefore latency and per-call cost) down -- classification doesn't need full camera resolution. */
    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE_PX) return bitmap
        val scale = MAX_LONG_EDGE_PX.toFloat() / longEdge
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val TAG = "CloudVisionMaterialTier"
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 4_000L
        const val MAX_LONG_EDGE_PX = 768
        const val JPEG_QUALITY = 80
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Top-level (not nested in the impl class) so Retrofit's dynamic proxy generation never
 * has to deal with a JVM-private nested interface -- a real pitfall, not just style.
 * Raw RequestBody/ResponseBody are natively-supported Retrofit types, so no converter
 * factory dependency is needed for a single simple JSON endpoint like this one.
 */
private interface VisionProxyApi {
    @POST(".")
    suspend fun classify(
        @Header("X-Proxy-Secret") secret: String,
        @Body request: RequestBody
    ): ResponseBody
}

@Serializable
private data class ClassifyRequest(@SerialName("image_base64") val imageBase64: String)

@Serializable
private data class ClassifyResponse(
    @SerialName("material_type") val materialType: String,
    @SerialName("product_category") val productCategory: String,
    val confidence: Float
)
