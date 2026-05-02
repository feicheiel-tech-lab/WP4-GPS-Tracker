package feicheiel.technologies.trackme.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

data class RegisterResponse(
    val user_id: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access: String,
    val refresh: String
)

data class GoogleAuthRequest(
    val idToken: String
)

data class LocationRecordResponse(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String, // Django ISO format
    val speed: Double?,
    val accuracy: Double?,
    val device_id: String?
)

interface AuthApi {
    @POST("api/register/")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("api/token/")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("api/google-login/")
    suspend fun googleLogin(@Body request: GoogleAuthRequest): Response<RegisterResponse>

    @GET("api/history/")
    suspend fun getHistory(): Response<List<LocationRecordResponse>>
}
