package dev.gallon.konstruct.serialization

import konstruct.serialization.mapped
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MappedSerializerTest {

    // Domain class (not @Serializable)
    data class User(val id: Int, val name: String)

    // Surrogate class (@Serializable)
    @Serializable
    data class UserSurrogate(val id: Int, val name: String)

    // Manual Serializer using 'mapped'
    object UserSerializer : KSerializer<User> by UserSurrogate.serializer().mapped(
        convertForEncoding = { UserSurrogate(it.id, it.name) },
        convertForDecoding = { User(it.id, it.name) }
    )

    @Test
    fun `test bidirectional mapping`() {
        val user = User(1, "Konstruct")
        val json = Json

        // Target -> Surrogate -> JSON
        val encoded = json.encodeToString(UserSerializer, user)
        assertEquals("""{"id":1,"name":"Konstruct"}""", encoded)

        // JSON -> Surrogate -> Target
        val decoded = json.decodeFromString(UserSerializer, encoded)
        assertEquals(user, decoded)
    }
}
