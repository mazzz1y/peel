package wtf.mazy.peel.model.db

import androidx.room.TypeConverter
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
