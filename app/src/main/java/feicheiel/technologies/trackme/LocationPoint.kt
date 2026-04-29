package feicheiel.technologies.trackme

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val speed: Float?,
    val accuracy: Float
)
