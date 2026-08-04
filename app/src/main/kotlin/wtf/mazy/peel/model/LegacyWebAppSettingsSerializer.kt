package wtf.mazy.peel.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

object LegacyWebAppSettingsSerializer :
    JsonTransformingSerializer<WebAppSettings>(WebAppSettings.serializer()) {

    private const val LEGACY_KEY = "isShowNotification"
    private const val KEY = "browserControlsMode"

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject || LEGACY_KEY !in element) return element
        val migrated = element.toMutableMap()
        val legacy = migrated.remove(LEGACY_KEY)
        if (KEY !in migrated) {
            legacy?.jsonPrimitive?.booleanOrNull?.let { shown ->
                migrated[KEY] = JsonPrimitive(
                    if (shown) WebAppSettings.BROWSER_CONTROLS_BUTTON
                    else WebAppSettings.BROWSER_CONTROLS_OFF
                )
            }
        }
        return JsonObject(migrated)
    }
}
