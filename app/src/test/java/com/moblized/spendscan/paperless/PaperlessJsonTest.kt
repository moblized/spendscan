package com.moblized.spendscan.paperless

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaperlessJsonTest {
    @Test
    fun readsTheUploadJob() {
        assertEquals("b8d0f3c2-5a1e-4f3b-9c55-2d7a1e0c9f11", PaperlessJson.taskId("\"b8d0f3c2-5a1e-4f3b-9c55-2d7a1e0c9f11\""))
        assertEquals("abc123", PaperlessJson.taskId("""{"task_id": "abc123"}"""))
        assertNull(PaperlessJson.taskId("<!doctype html><html><body>Sign in</body></html>"))
        assertNull(PaperlessJson.taskId(""))
    }

    @Test
    fun readsWhereAJobIsInBothShapes() {
        assertEquals(TaskState.Made(42), PaperlessJson.task("""[{"task_id":"t","status":"SUCCESS","related_document":"42","result":"Success."}]"""))
        // Newer versions page their lists and may spell things differently.
        assertEquals(TaskState.Made(12), PaperlessJson.task("""{"count":1,"next":null,"results":[{"status":"success","related_document":12}]}"""))
        assertEquals(TaskState.Made(7), PaperlessJson.task("""[{"status":"SUCCESS","related_document":null,"result":"Success. New document id 7 created"}]"""))
        assertEquals(TaskState.Waiting, PaperlessJson.task("""[{"status":"PENDING"}]"""))
        assertEquals(TaskState.Waiting, PaperlessJson.task("""[{"status":"STARTED"}]"""))
        assertEquals(TaskState.Missing, PaperlessJson.task("[]"))
        assertEquals(TaskState.Missing, PaperlessJson.task("<html></html>"))
    }

    @Test
    fun aDuplicateIsTheDocumentPaperlessAlreadyHas() {
        val answer = """[{"status":"FAILURE","related_document":null,
            "result":"a.pdf: Not consuming a.pdf: It is a duplicate of Store #5 (#17). Note: existing document is in the trash."}]"""
        assertEquals(TaskState.AlreadyThere(17), PaperlessJson.task(answer))
        assertEquals(
            TaskState.Failed("Unsupported mime type"),
            PaperlessJson.task("""[{"status":"FAILURE","result":"Unsupported mime type"}]"""),
        )
    }

    @Test
    fun readsLabels() {
        val page = PaperlessJson.labels(
            """{"count":3,"next":"http://x/api/tags/?page=2","results":[{"id":3,"name":"Groceries"},{"id":"4","name":"Fuel","data_type":"monetary"}]}""",
        )!!
        assertTrue(page.hasMore)
        assertEquals(listOf(Label(3, "Groceries", null), Label(4, "Fuel", "monetary")), page.labels)
        assertFalse(PaperlessJson.labels("""{"count":0,"next":null,"results":[]}""")!!.hasMore)
        assertNull(PaperlessJson.labels("<html>"))
        assertEquals(9L, PaperlessJson.createdId("""{"id":9,"name":"Kroger"}"""))
    }

    @Test
    fun newLabelsDontMatchOtherDocuments() {
        val tag = JSONObject(PaperlessJson.newLabel("Groceries"))
        assertEquals("Groceries", tag.getString("name"))
        assertEquals(0, tag.getInt("matching_algorithm"))
        val field = JSONObject(PaperlessJson.newLabel("Total", "monetary"))
        assertEquals("monetary", field.getString("data_type"))
        assertFalse(field.has("matching_algorithm"))
    }

    @Test
    fun countsDocumentsOnlyFromPaperless() {
        assertEquals(312, PaperlessJson.documentCount("""{"count":312,"next":null,"results":[{"id":1}]}"""))
        assertNull(PaperlessJson.documentCount("""{"detail":"x"}"""))
        assertNull(PaperlessJson.documentCount("<!doctype html>"))
    }

    @Test
    fun readsAndWritesADocument() {
        val now = PaperlessJson.document("""{"id":5,"tags":[1,2],"custom_fields":[{"field":3,"value":"USD1.00"},{"field":8,"value":null}]}""")!!
        assertEquals(listOf(1L, 2L), now.tags)
        assertEquals(listOf<Pair<Long, Any?>>(3L to "USD1.00", 8L to null), now.customFields)

        val json = JSONObject(PaperlessJson.update("Kroger $6.93", LocalDate.of(2026, 11, 2), 4, null, listOf(1, 2, 2), now.customFields))
        assertEquals("Kroger $6.93", json.getString("title"))
        assertEquals("2026-11-02", json.getString("created"))
        assertEquals(4, json.getInt("correspondent"))
        assertFalse(json.has("document_type"))
        assertEquals(2, json.getJSONArray("tags").length())
        val fields = json.getJSONArray("custom_fields")
        assertEquals(3, fields.getJSONObject(0).getInt("field"))
        assertTrue(fields.getJSONObject(1).isNull("value"))
    }

    @Test
    fun picksOutWhyPaperlessSaidNo() {
        assertEquals("File type text/plain not supported", PaperlessJson.complaint("""{"document":["File type text/plain not supported"]}"""))
        assertNull(PaperlessJson.complaint("<html>"))
    }
}
