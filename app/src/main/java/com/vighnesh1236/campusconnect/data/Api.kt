package com.vighnesh1236.campusconnect.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val rollNumber: String?,
    val department: String?,
    val role: String,
    val profilePhoto: String? = null
)

data class AuthResponse(val token: String, val user: User)
data class AuthRequest(val email: String, val password: String)
data class RegisterRequest(
    val name: String, val email: String, val password: String,
    val rollNumber: String, val department: String
)
data class ProfileRequest(val name: String, val rollNumber: String, val department: String)
data class ProfilePhotoRequest(val profilePhoto: String?)
data class Announcement(
    val id: Int, val title: String, val description: String,
    val category: String, val createdAt: String
)
data class CampusEvent(
    val id: Int, val title: String, val description: String,
    val eventDate: String, val eventTime: String?, val venue: String,
    val registered: Boolean = false
)
data class Assignment(
    val id: Int, val title: String, val subject: String,
    val description: String, val deadline: String,
    val status: String = "pending"
)
data class CreateAnnouncement(val title: String, val description: String, val category: String)
data class CreateEvent(
    val title: String, val description: String, val eventDate: String,
    val eventTime: String, val venue: String
)
data class CreateAssignment(
    val title: String, val subject: String, val description: String, val deadline: String
)
data class ApiError(val message: String?)
data class EventRegistration(
    val id: Int,
    val registeredAt: String,
    val eventTitle: String,
    val studentName: String,
    val rollNumber: String,
    val department: String
)

interface CampusApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: AuthRequest): AuthResponse

    @GET("api/students/profile")
    suspend fun profile(): User

    @PUT("api/students/profile")
    suspend fun updateProfile(@Body body: ProfileRequest): User

    @PUT("api/students/profile/photo")
    suspend fun updateProfilePhoto(@Body body: ProfilePhotoRequest): User

    @GET("api/announcements")
    suspend fun announcements(): List<Announcement>

    @POST("api/announcements")
    suspend fun createAnnouncement(@Body body: CreateAnnouncement): Announcement

    @PUT("api/announcements/{id}")
    suspend fun updateAnnouncement(@Path("id") id: Int, @Body body: CreateAnnouncement): Announcement

    @DELETE("api/announcements/{id}")
    suspend fun deleteAnnouncement(@Path("id") id: Int)

    @GET("api/events")
    suspend fun events(): List<CampusEvent>

    @POST("api/events")
    suspend fun createEvent(@Body body: CreateEvent): CampusEvent

    @PUT("api/events/{id}")
    suspend fun updateEvent(@Path("id") id: Int, @Body body: CreateEvent): CampusEvent

    @DELETE("api/events/{id}")
    suspend fun deleteEvent(@Path("id") id: Int)

    @POST("api/events/{id}/register")
    suspend fun registerForEvent(@Path("id") id: Int)

    @DELETE("api/events/{id}/register")
    suspend fun cancelEventRegistration(@Path("id") id: Int)

    @GET("api/assignments")
    suspend fun assignments(): List<Assignment>

    @POST("api/assignments")
    suspend fun createAssignment(@Body body: CreateAssignment): Assignment

    @PUT("api/assignments/{id}")
    suspend fun updateAssignment(@Path("id") id: Int, @Body body: CreateAssignment): Assignment

    @DELETE("api/assignments/{id}")
    suspend fun deleteAssignment(@Path("id") id: Int)

    @PUT("api/assignments/{id}/status")
    suspend fun updateAssignmentStatus(@Path("id") id: Int, @Body body: Map<String, String>)

    @GET("api/admin/students")
    suspend fun students(): List<User>

    @GET("api/admin/events/registrations")
    suspend fun eventRegistrations(): List<EventRegistration>
}

object SessionStore {
    private const val PREFS = "campusconnect_session"
    private const val TOKEN = "jwt"
    private const val USER_ID = "user_id"
    private const val USER_NAME = "user_name"
    private const val USER_ROLE = "user_role"

    private lateinit var preferences: android.content.SharedPreferences

    fun initialize(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        preferences = EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun token(): String? = preferences.getString(TOKEN, null)
    fun save(auth: AuthResponse) {
        preferences.edit().putString(TOKEN, auth.token)
            .putInt(USER_ID, auth.user.id)
            .putString(USER_NAME, auth.user.name)
            .putString(USER_ROLE, auth.user.role).apply()
    }
    fun clear() = preferences.edit().clear().apply()
    fun userName(): String = preferences.getString(USER_NAME, "").orEmpty()
    fun userRole(): String = preferences.getString(USER_ROLE, "student").orEmpty()
    fun userId(): Int = preferences.getInt(USER_ID, 0)
}

object ApiClient {
    fun create(): CampusApi {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder().apply {
                SessionStore.token()?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
        return Retrofit.Builder()
            .baseUrl(com.vighnesh1236.campusconnect.BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CampusApi::class.java)
    }
}
