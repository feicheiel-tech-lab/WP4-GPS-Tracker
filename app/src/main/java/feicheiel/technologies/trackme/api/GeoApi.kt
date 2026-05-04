package feicheiel.technologies.trackme.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class RefreshRequest(val refresh: String)
data class RefreshResponse(val access: String)

data class GeoPointRequest(
    val device: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String
)

interface GeoApi {

    @POST("api/token/refresh/")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("api/upload/")
    suspend fun uploadPoints(
        @Header("Authorization") authHeader: String,
        @Body points: List<GeoPointRequest>
    ): Response<Unit>
}
