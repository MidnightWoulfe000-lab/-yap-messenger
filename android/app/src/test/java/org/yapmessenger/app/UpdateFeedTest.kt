package org.yapmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateFeedTest {
    private val digest = "a".repeat(64)

    private fun release(vararg assets: String) = """{"assets":[${assets.joinToString(",")}]}"""
    private fun asset(name: String, url: String, digest: String? = null) =
        """{"name":"$name","browser_download_url":"$url","size":1234${digest?.let { ""","digest":"$it"""" } ?: ""}}"""

    @Test fun readsVersionCodeFromTheApkName() {
        val offer = UpdateFeed.parse(release(asset("yap-debug-1042.apk", "https://github.com/o/r/releases/download/debug-latest/yap-debug-1042.apk", "sha256:$digest")))!!
        assertEquals(1042L, offer.versionCode)
        assertEquals(digest, offer.sha256)
    }

    @Test fun ignoresAssetsThatAreNotTheDebugApk() {
        assertNull(UpdateFeed.parse(release(asset("notes.txt", "https://github.com/o/r/x"))))
        assertNull(UpdateFeed.parse("""{"assets":[]}"""))
        assertNull(UpdateFeed.parse("""{}"""))
    }

    @Test fun refusesDownloadsOutsideGitHub() {
        assertNull(UpdateFeed.parse(release(asset("yap-debug-5.apk", "https://example.com/yap-debug-5.apk"))))
        assertNull(UpdateFeed.parse(release(asset("yap-debug-5.apk", "http://github.com/yap-debug-5.apk"))))
    }

    @Test fun missingOrMalformedDigestMeansNoChecksum() {
        val url = "https://github.com/o/r/yap-debug-7.apk"
        assertNull(UpdateFeed.parse(release(asset("yap-debug-7.apk", url)))!!.sha256)
        assertNull(UpdateFeed.parse(release(asset("yap-debug-7.apk", url, "sha1:abc")))!!.sha256)
        assertNull(UpdateFeed.parse(release(asset("yap-debug-7.apk", url, "sha256:short")))!!.sha256)
    }
}
