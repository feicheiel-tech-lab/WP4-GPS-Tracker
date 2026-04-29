package feicheiel.technologies.trackme

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_points")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String, // The Unique ID from server authentication
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val speed: Float?,
    val accuracy: Float,
    val distanceFromPrevious: Float = 0f,
    val totalDistance: Float = 0f,
    val isSynced: Boolean = false
)