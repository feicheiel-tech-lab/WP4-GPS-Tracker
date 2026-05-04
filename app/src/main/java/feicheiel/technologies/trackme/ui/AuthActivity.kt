package feicheiel.technologies.trackme.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import feicheiel.technologies.trackme.MainActivity
import feicheiel.technologies.trackme.api.AuthApi
import feicheiel.technologies.trackme.api.LocationRecordResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import feicheiel.technologies.trackme.AppDatabase
import feicheiel.technologies.trackme.LocationEntity
import feicheiel.technologies.trackme.api.LoginRequest
import feicheiel.technologies.trackme.api.RegisterRequest
import feicheiel.technologies.trackme.api.RegisterResponse
import feicheiel.technologies.trackme.api.TokenResponse
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

import feicheiel.technologies.trackme.ui.theme.TrackMeTheme

class AuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPref = getSharedPreferences("auth", Context.MODE_PRIVATE)
        val userPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        
        if (sharedPref.getString("user_id", null) != null && 
            userPrefs.getString("current_user_id", null) != null &&
            !userPrefs.getString("auth_token", null).isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        setContent {
            TrackMeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AuthScreen()
                }
            }
        }
    }
}

@Composable
fun AuthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSignUp by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    val retrofit = remember {
        Retrofit.Builder()
            .baseUrl("http://192.168.8.118:8000/") // Updated to host machine IP
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    val api = remember { retrofit.create(AuthApi::class.java) }

    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestIdToken("601714027321-8s8997agrnc9r5rjdk0ruab58975nr35.apps.googleusercontent.com") // Replace with actual ID
        .build()
    val googleSignInClient = GoogleSignIn.getClient(context, gso)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            // Send account.idToken to server
            Toast.makeText(context, "Google Sign In Success: ${account?.email}", Toast.LENGTH_SHORT).show()
            // Here you would call api.googleLogin(GoogleAuthRequest(account.idToken!!))
            // For now, let's simulate success
            if (rememberMe) {
                val sharedPref = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                sharedPref.edit().putString("user_id", "google-user-id").apply()
            }
            context.startActivity(Intent(context, MainActivity::class.java))
            (context as AuthActivity).finish()
        } catch (e: Exception) {
            Toast.makeText(context, "Google Sign In Failed", Toast.LENGTH_SHORT).show()
        }
    }

    var isLoadingHistory by remember { mutableStateOf(false) }

    if (isLoadingHistory) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Syncing history...", fontWeight = FontWeight.Light)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSignUp) "Create Account" else "Welcome Back",
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username", fontWeight = FontWeight.Light) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Light)
        )
        
        if (isSignUp) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email", fontWeight = FontWeight.Light) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Light)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password", fontWeight = FontWeight.Light) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Light)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text("Remember me", fontWeight = FontWeight.Light)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    try {
                        val response = if (isSignUp) {
                            api.register(RegisterRequest(username, email, password))
                        } else {
                            api.login(LoginRequest(username, password))
                        }

                        if (response.isSuccessful) {
                            val body = response.body()
                            
                            val sharedPref = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                            val userPrefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            
                            val userId = when (body) {
                                is RegisterResponse -> {
                                    if (body.access != null && body.refresh != null) {
                                        userPrefs.edit()
                                            .putString("auth_token", body.access)
                                            .putString("refresh_token", body.refresh)
                                            .apply()
                                    }
                                    body.user_id
                                }
                                is TokenResponse -> {
                                    userPrefs.edit()
                                        .putString("auth_token", body.access)
                                        .putString("refresh_token", body.refresh)
                                        .apply()
                                    username 
                                }
                                else -> "user"
                            }

                            if (rememberMe) {
                                sharedPref.edit().putString("user_id", userId).apply()
                            }
                            userPrefs.edit().putString("current_user_id", userId).apply()

                            // Download History
                            isLoadingHistory = true
                            try {
                                val authToken = userPrefs.getString("auth_token", "") ?: ""
                                val historyResponse = api.getHistory("Bearer $authToken")
                                if (historyResponse.isSuccessful) {
                                    val history = historyResponse.body() ?: emptyList()
                                    val database = AppDatabase.getDatabase(context)
                                    val dao = database.locationDao()
                                    
                                    val remoteEntities = history.map { record ->
                                        // Parse Django ISO timestamp
                                        val timestamp = try {
                                            ZonedDateTime.parse(record.timestamp).toInstant().toEpochMilli()
                                        } catch (e: Exception) {
                                            System.currentTimeMillis()
                                        }

                                        LocationEntity(
                                            userId = userId,
                                            latitude = record.latitude,
                                            longitude = record.longitude,
                                            timestamp = timestamp,
                                            speed = record.speed?.toFloat(),
                                            accuracy = record.accuracy?.toFloat() ?: 0f,
                                            isSynced = true
                                        )
                                    }
                                    
                                    val localPoints = dao.getAllPoints(userId)
                                    val localTimestamps = localPoints.map { it.timestamp }.toSet()
                                    val newFromRemote = remoteEntities.filter { it.timestamp !in localTimestamps }

                                    if (newFromRemote.isNotEmpty()) {
                                        val allMerged = (localPoints + newFromRemote).sortedBy { it.timestamp }
                                        
                                        val updatedPoints = mutableListOf<LocationEntity>()
                                        var totalDist = 0f
                                        var lastPoint: LocationEntity? = null
                                        
                                        allMerged.forEach { point ->
                                            var distFromPrev = 0f
                                            if (lastPoint != null) {
                                                val result = FloatArray(1)
                                                android.location.Location.distanceBetween(
                                                    lastPoint.latitude, lastPoint.longitude,
                                                    point.latitude, point.longitude,
                                                    result
                                                )
                                                distFromPrev = result[0]
                                            }
                                            totalDist += distFromPrev
                                            updatedPoints.add(point.copy(
                                                id = 0, // Reset for re-insertion
                                                distanceFromPrevious = distFromPrev,
                                                totalDistance = totalDist
                                            ))
                                            lastPoint = updatedPoints.last()
                                        }
                                        
                                        dao.deleteAll(userId)
                                        dao.insertAll(updatedPoints)
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isLoadingHistory = false
                            }

                            context.startActivity(Intent(context, MainActivity::class.java))
                            (context as AuthActivity).finish()
                        } else {
                            Toast.makeText(context, "Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to connect: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(if (isSignUp) "Sign Up" else "Sign In", fontWeight = FontWeight.Light)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { launcher.launch(googleSignInClient.signInIntent) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Continue with Google", fontWeight = FontWeight.Light)
        }

        TextButton(onClick = { isSignUp = !isSignUp }) {
            Text(if (isSignUp) "Already have an account? Sign In" else "New here? Create account", fontWeight = FontWeight.Light)
        }
    }
}
