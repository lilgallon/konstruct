package dev.gallon.konstruct

import dev.gallon.konstruct.generated.advancedSerializersModule
import dev.gallon.konstruct.generated.simpleSerializersModule
import dev.gallon.konstruct.testdata.SimpleModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSerializationTest {

    @Test
    fun `test simple module contains basic models`() {
        val json = Json { serializersModule = simpleSerializersModule }
        val model = SimpleModel("test")

        val serialized = json.encodeToString(model)
        assertEquals("""{"value":"test"}""", serialized)

        val deserialized = json.decodeFromString<SimpleModel>(serialized)
        assertEquals(model, deserialized)
    }

    @Test
    fun `test combining modules`() {
        val combinedModule = simpleSerializersModule + advancedSerializersModule
        val json = Json { serializersModule = combinedModule }

        val model = SimpleModel("combined")
        val serialized = json.encodeToString(model)
        assertEquals("""{"value":"combined"}""", serialized)
    }
}
