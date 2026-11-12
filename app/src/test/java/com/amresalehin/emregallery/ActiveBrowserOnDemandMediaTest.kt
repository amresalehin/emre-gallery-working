package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserTabSession
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.DualMediaExtractor
import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.PocketDeepScanEngine
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.data.TargetMediaExpectation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that active browser normal browsing has automatic background
 * media discovery disabled, media discovery is strictly on-demand, extraction features
 * remain fully intact, and Pocket implementation remains intact.
 */
class ActiveBrowserOnDemandMediaTest {

    @Test
    fun testActiveBrowserNormalBrowsingDoesNotAutoDiscoverMedia() {
        val tabSession = BrowserTabSession(tabId = "active_browser_test")

        // 1. Initial state: 0 items detected
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.detectedItems.value.isEmpty())

        // 2. Normal browsing navigation: Navigating between pages should NOT automatically discover media
        tabSession.updateNavigation("https://en.wikipedia.org/wiki/Android_(operating_system)", "Android - Wikipedia")
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.detectedItems.value.isEmpty())

        tabSession.updateNavigation("https://duckduckgo.com/?q=jetpack+compose", "DuckDuckGo")
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.detectedItems.value.isEmpty())

        // DownloaderSession is updated with page info for potential on-demand requests, but has 0 discovered items
        DownloaderSession.updateBrowserPage("https://duckduckgo.com/?q=jetpack+compose")
        assertEquals(0, DownloaderSession.discoveredItems.value.size)
        assertFalse(DownloaderSession.isExtracting.value)
    }

    @Test
    fun testOnDemandMediaDiscoveryExplicitlyPopulatesSession() {
        val tabSession = BrowserTabSession(tabId = "on_demand_test")
        tabSession.updateNavigation("https://example.com/gallery", "Example Gallery")
        assertEquals(0, tabSession.detectedCount.value)

        // Simulate user explicitly triggering On-Demand media discovery
        val discoveredItems = listOf(
            ScrapedMediaItem(
                id = "item_img_1",
                url = "https://example.com/photos/landscape.jpg",
                title = "Landscape Photo",
                isVideo = false,
                source = ExtractionSource.DOM_SCRAPE.label
            ),
            ScrapedMediaItem(
                id = "item_vid_1",
                url = "https://example.com/videos/stream.mp4",
                title = "Stream Video",
                isVideo = true,
                source = ExtractionSource.DOM_SCRAPE.label
            )
        )

        // On-demand batch registration
        tabSession.registerMediaBatch(discoveredItems, "Example Gallery")

        // Discovered count is now populated strictly because user triggered on-demand scan
        assertEquals(2, tabSession.detectedCount.value)
        assertEquals(2, tabSession.getDetectedMedia().size)
        assertTrue(tabSession.getDetectedMedia().any { it.url == "https://example.com/photos/landscape.jpg" })
        assertTrue(tabSession.getDetectedMedia().any { it.url == "https://example.com/videos/stream.mp4" })

        // When navigating to a new URL, session resets to 0 until the user explicitly requests on-demand scan again
        tabSession.updateNavigation("https://example.com/new-article", "New Article")
        assertEquals(0, tabSession.detectedCount.value)
        assertTrue(tabSession.detectedItems.value.isEmpty())
    }

    @Test
    fun testExtractionFeaturesRemainIntact() {
        // 1. TargetMediaExpectation routing works
        val ytExpectation = ExtractionCoordinator.determineExpectation("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(TargetMediaExpectation.VIDEO_OR_STREAM, ytExpectation)

        val redditExpectation = ExtractionCoordinator.determineExpectation("https://www.reddit.com/r/pics/comments/xyz/photo")
        assertEquals(TargetMediaExpectation.GALLERY_OR_ALBUM, redditExpectation)

        val genericExpectation = ExtractionCoordinator.determineExpectation("https://en.wikipedia.org/wiki/Kotlin")
        assertEquals(TargetMediaExpectation.GENERIC_ANY, genericExpectation)

        // 2. MediaCaptureEngine DOM script generation is valid and functional
        val domScript = MediaCaptureEngine.getJsDomScanScript()
        assertTrue(domScript.isNotBlank())
        assertTrue(domScript.contains("document.querySelectorAll"))
        assertTrue(domScript.contains("img, picture source"))
        assertTrue(domScript.contains("video"))
        assertTrue(domScript.contains("audio"))
        assertTrue(domScript.contains("application/ld+json"))

        // 3. MediaCaptureEngine parsing is intact
        val sampleJson = """
            [
                {"u": "https://example.com/image1.jpg", "s": "DOM", "r": "1080w"},
                {"u": "https://example.com/video1.mp4", "s": "DOM", "r": "video", "p": "https://example.com/poster.jpg"}
            ]
        """.trimIndent()

        val parsed = MediaCaptureEngine.parseJsDomScanResult(sampleJson, "https://example.com/page")
        assertEquals(2, parsed.size)
        assertEquals("https://example.com/image1.jpg", parsed[0].url)
        assertFalse(parsed[0].isVideo)
        assertEquals("https://example.com/video1.mp4", parsed[1].url)
        assertTrue(parsed[1].isVideo)
        assertEquals("https://example.com/poster.jpg", parsed[1].thumbnailUrl)

        // 4. Extraction sufficiency checker remains intact
        assertTrue(ExtractionCoordinator.isExtractionSufficient(TargetMediaExpectation.VIDEO_OR_STREAM, parsed))
    }

    @Test
    fun testPocketImplementationRemainsIntact() {
        // 1. PocketDeepScanEngine is strictly on-demand
        assertFalse("Deep scan should not be active by default", PocketDeepScanEngine.isScanning())
        assertNull(PocketDeepScanEngine.getActiveBookmarkId())

        // Stop scan on idle should be safe no-op
        PocketDeepScanEngine.stopScan()
        assertFalse(PocketDeepScanEngine.isScanning())

        // 2. DownloaderSession state model remains intact
        DownloaderSession.clear()
        assertEquals("", DownloaderSession.currentUrl.value)
        assertEquals(0, DownloaderSession.discoveredItems.value.size)
        assertEquals(0, DownloaderSession.selectedItemIds.value.size)
        assertFalse(DownloaderSession.isExtracting.value)
    }

    @Test
    fun testPayloadWrapperWithExtendedVideoExtraction() {
        val domScript = MediaCaptureEngine.getJsDomScanScript()
        assertTrue(domScript.contains("og:video"))
        assertTrue(domScript.contains("twitter:player:stream"))
        assertTrue(domScript.contains("data-video-url"))

        val payloadJson = """
            {
                "pageUrl": "https://m.youtube.com/shorts/sample123",
                "pageTitle": "Sample Short",
                "items": [
                    {"u": "https://example.com/video_stream.mp4", "s": "DOM", "r": "video", "p": "https://example.com/thumb.jpg"},
                    {"u": "https://example.com/og_video.mp4", "s": "DOM", "r": "og:video"},
                    {"u": "https://example.com/poster.jpg", "s": "DOM", "r": "1080w"}
                ]
            }
        """.trimIndent()

        val payload = MediaCaptureEngine.parseJsDomScanPayload(payloadJson, "https://m.youtube.com/")
        assertEquals("https://m.youtube.com/shorts/sample123", payload.pageUrl)
        assertEquals("Sample Short", payload.pageTitle)
        assertEquals(3, payload.items.size)
        assertTrue(payload.items[0].isVideo)
        assertEquals("https://example.com/thumb.jpg", payload.items[0].thumbnailUrl)
        assertTrue(payload.items[1].isVideo)
        assertFalse(payload.items[2].isVideo)

        // Backward compatibility parseJsDomScanResult returns items directly
        val itemsList = MediaCaptureEngine.parseJsDomScanResult(payloadJson, "https://m.youtube.com/")
        assertEquals(3, itemsList.size)
    }
}
