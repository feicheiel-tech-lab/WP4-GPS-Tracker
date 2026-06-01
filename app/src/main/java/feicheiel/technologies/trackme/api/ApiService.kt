package feicheiel.technologies.trackme.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// ── Request / Response models ─────────────────────────────────────────────────

data class TokenRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access: String,
    val refresh: String
)

data class RefreshRequest(
    val refresh: String
)

data class RefreshResponse(
    val access: String,
    val refresh: String  // SimpleJWT rotates the refresh token on every use — must be saved
)

data class GeoPointRequest(
    val device: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,  // ISO-8601 UTC: "2026-04-07T12:00:00Z"
    val accuracy: Float? = null
)

data class DownloadRequest(
    val device: String
)

// ── Retrofit interface ────────────────────────────────────────────────────────

interface ApiService {

    /** Step 1 – obtain access + refresh tokens */
    @POST("api/token/")
    suspend fun getToken(@Body request: TokenRequest): Response<TokenResponse>

    /** Step 2 – upload a batch of location points */
    @POST("api/upload/")
    suspend fun uploadPoints(
        @Header("Authorization") bearer: String,
        @Body points: List<GeoPointRequest>
    ): Response<Unit>

    /** Step 3 – refresh an expired access token */
    @POST("api/token/refresh/")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    /** Download all points for this device from the server */
    @POST("api/download/")
    suspend fun downloadPoints(
        @Header("Authorization") bearer: String,
        @Body request: DownloadRequest
    ): Response<List<GeoPointRequest>>
}
