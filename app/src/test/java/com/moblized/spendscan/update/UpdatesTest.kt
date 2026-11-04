package com.moblized.spendscan.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    @Test
    fun versionCodes() {
        assertEquals(21, Updates.versionCodeOf("v1.0.21"))
        assertEquals(3, Updates.versionCodeOf(" v1.0.3 "))
        assertNull(Updates.versionCodeOf("screenshots"))
    }

    @Test
    fun readsARelease() {
        val r = Updates.parseRelease(
            """
            {"tag_name":"v1.0.12","draft":false,"prerelease":false,
             "body":"Better totals\n\nCo-Authored-By: Someone\nClaude-Session: x",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes","size":1},
                       {"name":"SpendScan-1.0.12.apk","browser_download_url":"https://x/a.apk","size":2048}]}
            """,
        )!!
        assertEquals(12, r.versionCode)
        assertEquals("1.0.12", r.versionName)
        assertEquals("Better totals", r.notes)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(2048L, r.apkBytes)
    }

    @Test
    fun skipsWhatIsntARelease() {
        assertNull(Updates.parseRelease("""{"tag_name":"v1.0.1","prerelease":true}"""))
        assertNull(Updates.parseRelease("""{"tag_name":"v1.0.1","draft":true}"""))
        assertNull(Updates.parseRelease("""{"tag_name":"screenshots"}"""))
        assertNull(Updates.parseRelease("not json"))
    }
}
