package wtf.mazy.peel.model.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.json.Json

class StringMapConverter {
    @TypeConverter
    fun fromStringMap(map: MutableMap<String, String>?): String? {
        return map?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringMap(json: String?): MutableMap<String, String>? {
        if (json == null) return null
        return Json.decodeFromString<Map<String, String>>(json).toMutableMap()
    }
}

@Database(
    entities = [WebAppEntity::class, SandboxSlotEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(StringMapConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao

    abstract fun sandboxSlotDao(): SandboxSlotDao

    companion object {
        private const val DATABASE_NAME = "peel.db"

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance
                ?: synchronized(this) { instance ?: buildDatabase(context).also { instance = it } }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .allowMainThreadQueries()
                .build()
        }
    }
}
