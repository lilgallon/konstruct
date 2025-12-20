package dev.gallon.konstruct

import dev.gallon.konstruct.testdata.*
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomSerializerTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `test nested serialization with custom int rules`() {
        val pos = Position("Junior", 1)
        // CustomIntSerializer adds 100 on encoding
        val serialized = json.encodeToString(PositionSerializer, pos)

        assertEquals("""{"name":"Junior","experience":101}""", serialized)

        val deserialized = json.decodeFromString(PositionSerializer, serialized)
        assertEquals(pos, deserialized)
    }

    @Test
    fun `test organization with multiple custom serializers`() {
        val orgId = UUID.randomUUID()
        val pos = Position("CEO", 20)
        // CustomIntSerializer adds 100 on encoding
        // Employee age is 50 -> encoded as 150
        // Position experience is 20 -> encoded as 120
        val employee = Employee("John", pos, 50)
        val org = Organization(id = orgId, name = "Acme Corp", employees = listOf(employee))

        val serialized = json.encodeToString(OrganizationSerializer, org)

        assertEquals("""{"id":"$orgId","name":"Acme Corp","employees":[{"name":"John","city":{"name":"CEO","experience":120},"age":150}]}""", serialized)

        val deserialized = json.decodeFromString(OrganizationSerializer, serialized)
        assertEquals(org, deserialized)
    }

    @Test
    fun `test organization with skip nulls list serializer`() {
        val orgId = UUID.randomUUID()
        // age 125 -> 25, age 130 -> 30
        val jsonString = """{"id":"$orgId","name":"Null City","employees":[{"name":"Alice","city":{"name":"Dev","experience":102},"age":125}, null, {"name":"Bob","city":{"name":"QA","experience":103},"age":130}]}"""

        val deserialized = json.decodeFromString(OrganizationSerializer, jsonString)

        assertEquals(2, deserialized.employees.size)
        assertEquals("Alice", deserialized.employees[0].name)
        assertEquals("Bob", deserialized.employees[1].name)
        assertEquals(25, deserialized.employees[0].age)
        assertEquals(2, deserialized.employees[0].city.experience) // 102 - 100
    }
}
