package dev.gallon.konstruct.testdata

import dev.gallon.konstruct.annotations.CustomClassSerializer
import dev.gallon.konstruct.annotations.CustomFieldSerializer
import dev.gallon.konstruct.annotations.FieldSerializer
import dev.gallon.konstruct.annotations.GenerateSerializers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import java.util.*

// --- Custom Serializers for Testing ---

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object CustomIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CustomInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value + 100)
    override fun deserialize(decoder: Decoder): Int = decoder.decodeInt() - 100
}

object OrganizationEmployeesSerializer : KSerializer<List<Employee>> {
    override val descriptor: SerialDescriptor = ListSerializer(EmployeeSerializer).descriptor
    override fun serialize(encoder: Encoder, value: List<Employee>) {
        encoder.encodeSerializableValue(ListSerializer(EmployeeSerializer), value)
    }

    override fun deserialize(decoder: Decoder): List<Employee> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(EmployeeSerializer))
        return when (val jsonElement = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> {
                jsonElement
                    .filterNot { it is JsonNull }
                    .map { element ->
                        jsonDecoder.json.decodeFromJsonElement(EmployeeSerializer, element)
                    }
            }

            else -> throw SerializationException("Expected array for List")
        }
    }
}

// --- Test Models ---

data class Organization(
    val id: UUID,
    val name: String,
    val employees: List<Employee>
)

data class Employee(
    val name: String,
    val city: Position,
    val age: Int? = null
)

data class Position(
    val name: String,
    val experience: Int
)

data class SimpleModel(val value: String)

// --- Generated Serializers ---

@GenerateSerializers(classes = [SimpleModel::class])
class SimpleSerializers

@GenerateSerializers(
    classes = [
        Position::class,
        Employee::class,
        Organization::class,
    ],
    customClassSerializers = [
        CustomClassSerializer(targetClass = UUID::class, serializer = UUIDSerializer::class),
        CustomClassSerializer(targetClass = Int::class, serializer = CustomIntSerializer::class)
    ],
    excludedSerializersFromModule = [
        // Use custom serializer for Int class
        Int::class
    ],
    customFieldSerializers = [
        CustomFieldSerializer(
            targetClass = Organization::class,
            fieldSerializer = [
                FieldSerializer(name = "employees", serializer = OrganizationEmployeesSerializer::class)
            ]
        )
    ]
)
class AdvancedSerializers
