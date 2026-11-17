package com.brightblu.parc.core.data.comms

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration/schema gate for the separate history database (T214). The exported v1 schema is the
 * contract; that is the closest a JVM unit test can get to opening Room's SQLite file. The
 * persistence-across-reopen contract is exercised by [CommsHistoryStoreTest].
 */
class CommsHistoryDatabaseTest {

    private val schema = Json
        .parseToJsonElement(
            File("schemas/com.brightblu.parc.core.data.comms.CommsHistoryDatabase/1.json").readText(),
        )
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

    private fun field(table: String, column: String) =
        entity(table).getValue("fields").jsonArray
            .first { it.jsonObject.getValue("columnName").jsonPrimitive.content == column }
            .jsonObject

    @Test
    fun `the history database is version 1 and has exactly one table`() {
        assertEquals(1, CommsHistoryDatabaseVersion)
        assertEquals(1, schema.getValue("version").jsonPrimitive.int)
        assertEquals(
            listOf("comms_messages"),
            schema.getValue("entities").jsonArray.map {
                it.jsonObject.getValue("tableName").jsonPrimitive.content
            },
        )
        assertEquals("comms-history.db", CommsHistoryStore.DatabaseName)
    }

    @Test
    fun `comms messages has exactly the card columns and no image or URI column`() {
        assertEquals(
            setOf(
                "id", "app", "conversation_key", "conversation_title", "sender",
                "notification_title", "text", "links", "time_ms", "recorded_at_ms",
                "notification_key", "identity", "live", "removed_at_ms",
            ),
            columns("comms_messages"),
        )
        val names = columns("comms_messages")
        assertFalse(names.any { it.contains("image") || it.contains("bitmap") || it.contains("uri") })
    }

    @Test
    fun `only removed at is nullable and the primary key autoincrements`() {
        val removed = field("comms_messages", "removed_at_ms")
        assertFalse(removed.getValue("notNull").jsonPrimitive.boolean)
        assertTrue(
            entity("comms_messages").getValue("fields").jsonArray
                .filterNot { it.jsonObject.getValue("columnName").jsonPrimitive.content == "removed_at_ms" }
                .all { it.jsonObject.getValue("notNull").jsonPrimitive.boolean },
        )
        val primary = entity("comms_messages").getValue("primaryKey").jsonObject
        assertEquals(
            listOf("id"),
            primary.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(primary.getValue("autoGenerate").jsonPrimitive.boolean)
    }

    @Test
    fun `the identity index is unique and all four card indexes exist`() {
        val indices = entity("comms_messages").getValue("indices").jsonArray.associate { index ->
            val objectIndex = index.jsonObject
            objectIndex.getValue("name").jsonPrimitive.content to objectIndex
        }
        assertEquals(
            setOf(
                "index_comms_messages_identity",
                "index_comms_messages_conversation",
                "index_comms_messages_time_ms",
                "index_comms_messages_live",
            ),
            indices.keys,
        )
        assertTrue(indices.getValue("index_comms_messages_identity").getValue("unique").jsonPrimitive.boolean)
        assertEquals(
            listOf("identity"),
            indices.getValue("index_comms_messages_identity")
                .getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("app", "conversation_key", "time_ms"),
            indices.getValue("index_comms_messages_conversation")
                .getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("time_ms"),
            indices.getValue("index_comms_messages_time_ms")
                .getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("live"),
            indices.getValue("index_comms_messages_live")
                .getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the card defaults are in the schema`() {
        assertEquals("''", field("comms_messages", "sender").getValue("defaultValue").jsonPrimitive.content)
        assertEquals("''", field("comms_messages", "notification_title").getValue("defaultValue").jsonPrimitive.content)
        assertEquals("''", field("comms_messages", "text").getValue("defaultValue").jsonPrimitive.content)
        assertEquals("'[]'", field("comms_messages", "links").getValue("defaultValue").jsonPrimitive.content)
        assertEquals("1", field("comms_messages", "live").getValue("defaultValue").jsonPrimitive.content)
    }

    @Test
    fun `there is no foreign key to the layout database`() {
        assertTrue(entity("comms_messages").getValue("foreignKeys").jsonArray.isEmpty())
        assertTrue(schema.getValue("views").jsonArray.isEmpty())
    }
}
