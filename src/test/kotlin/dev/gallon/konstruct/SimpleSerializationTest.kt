package dev.gallon.konstruct

import dev.gallon.konstruct.testdata.SimpleModel
import dev.gallon.konstruct.testdata.SimpleModelSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleSerializationTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `test simple model serialization`() {
        val model = SimpleModel(value = "Hello Konstruct")
        val serialized = json.encodeToString(SimpleModelSerializer, model)
        
        assertEquals("""{"value":"Hello Konstruct"}""", serialized)
        
        val deserialized = json.decodeFromString(SimpleModelSerializer, serialized)
        assertEquals(model, deserialized)
    }
}
