package wtf.mazy.peel.model

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

class WebAppDeserializer : JsonDeserializer<WebApp> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): WebApp {
        return Gson().fromJson(json, WebApp::class.java)
    }
}
