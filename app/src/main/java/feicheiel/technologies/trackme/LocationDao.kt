package feicheiel.technologies.trackme

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(point: LocationEntity)

    @Query("SELECT * FROM location_points WHERE isSynced = 0 AND userId = :userId ORDER BY timestamp ASC")
    suspend fun getUnsyncedPoints(userId: String): List<LocationEntity>

    @Update
    suspend fun markAsSynced(points: List<LocationEntity>)

    @Query("DELETE FROM location_points WHERE isSynced = 1 AND timestamp < :beforeTimeStamp")
    suspend fun deleteSyncedPoints(beforeTimeStamp: Long)

    @Query("SELECT * FROM location_points WHERE userId = :userId ORDER BY timestamp ASC")
    fun getAllPointsFlow(userId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location_points WHERE userId = :userId ORDER BY timestamp ASC")
    suspend fun getAllPoints(userId: String): List<LocationEntity>
    @Query("SELECT COUNT(*) FROM location_points WHERE isSynced = 1 AND userId = :userId")
    fun getSyncedCountFlow(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_points WHERE isSynced = 0 AND userId = :userId")
    fun getUnsyncedCountFlow(userId: String): Flow<Int>

    @Query("SELECT * FROM location_points WHERE userId = :userId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(userId: String): LocationEntity?

    @Query("DELETE FROM location_points WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}