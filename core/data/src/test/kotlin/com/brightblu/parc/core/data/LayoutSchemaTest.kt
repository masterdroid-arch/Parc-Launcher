package com.brightblu.parc.core.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration gate for the layout database. There is one version (v1), so the test is on the exported
 * schema Room generates: the tables, the keys and the declared columns, plus the fact that the code
 * constant still matches the artefact. A second version would need a Migration and its own test.
 * Gradle runs unit tests with the module directory as the working directory.
 */
class LayoutSchemaTest {

    private val schema = Json
        .parseToJsonElement(File("schemas/com.brightblu.parc.core.data.ParcDatabase/1.json").readText())
        .jsonObject
        .getValue("database")
        .jsonObject

    private fun entity(table: String) =
        schema.getValue("entities").jsonArray
            .first { it.jsonObject.getValue("tableName").jsonPrimitive.content == table }
            .jsonObject

    private fun columns(table: String): Set<String> =
        entity(table).getValue("fields").jsonArray
            .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            .toSet()

    private fun primaryKey(table: String): List<String> =
        entity(table).getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
            .map { it.jsonPrimitive.content }

    private fun nullableColumns(table: String): Set<String> =
        entity(table).getValue("fields").jsonArray
            .filterNot { it.jsonObject.getValue("notNull").jsonPrimitive.boolean }
            .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            .toSet()

    @Test
    fun `the exported schema is version 1 and matches the code constant`() {
        assertEquals(1, ParcDatabaseVersion)
        assertEquals(ParcDatabaseVersion, schema.getValue("version").jsonPrimitive.int)
        assertTrue(schema.getValue("identityHash").jsonPrimitive.content.isNotBlank())
        assertTrue(schema.getValue("setupQueries").jsonArray.isNotEmpty())
    }

    @Test
    fun `all five tables of PROJECT section 6 3 exist`() {
        val tables = schema.getValue("entities").jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()

        assertEquals(setOf("slot_assignment", "folder", "folder_app", "app_meta", "launch_log"), tables)
    }

    @Test
    fun `slot_assignment keeps sparse slots with one payload column per kind`() {
        assertEquals(
            setOf("arcId", "slotIndex", "kind", "appId", "folderId", "action", "uri", "label", "pinned"),
            columns("slot_assignment"),
        )
        assertEquals(listOf("arcId", "slotIndex"), primaryKey("slot_assignment"))
        // Only the payload columns are nullable, so a row cannot lose its arc, index or kind.
        assertEquals(setOf("appId", "folderId", "action", "uri", "label"), nullableColumns("slot_assignment"))
    }

    @Test
    fun `folder and folder_app keep the stored order`() {
        assertEquals(setOf("id", "name", "tags"), columns("folder"))
        assertEquals(listOf("id"), primaryKey("folder"))
        assertEquals(emptySet<String>(), nullableColumns("folder"))
        assertEquals(setOf("folderId", "appId", "position"), columns("folder_app"))
        assertEquals(listOf("folderId", "appId"), primaryKey("folder_app"))
        assertEquals(emptySet<String>(), nullableColumns("folder_app"))
    }

    @Test
    fun `app_meta has the hidden flag and the reserved label override`() {
        assertEquals(setOf("appId", "hidden", "labelOverride"), columns("app_meta"))
        assertEquals(listOf("appId"), primaryKey("app_meta"))
        assertEquals(setOf("labelOverride"), nullableColumns("app_meta"))
    }

    @Test
    fun `launch_log exists for the usage work with its timestamp index`() {
        assertEquals(setOf("id", "appId", "ts"), columns("launch_log"))
        val indices = entity("launch_log").getValue("indices").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue(indices.any { it.contains("ts") })
    }

    @Test
    fun `no table declares a foreign key, so a restore cannot be blocked by one`() {
        schema.getValue("entities").jsonArray.forEach { element ->
            assertTrue(element.jsonObject.getValue("foreignKeys").jsonArray.isEmpty())
        }
        assertTrue(schema.getValue("views").jsonArray.isEmpty())
    }
}
