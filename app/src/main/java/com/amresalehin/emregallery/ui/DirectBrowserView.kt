package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.os.Build
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BotDetectionMitigator
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserShortcut
import com.amresalehin.emregallery.data.BrowserTabItem
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.DualMediaExtractor
import com.amresalehin.emregallery.data.ExtractionCoordinator
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.data.SafeWebViewCleanup
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.data.TargetMediaExpectation
import kotlinx.coroutines.launch
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    onClose: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit = {},
    onOpenDownloads: (() -> Unit)? = null,
    onOpenDiscoveredMedia: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabDetectedCount by tabSession.detectedCount.collectAsState()
    val sessionDiscoveredItems by DownloaderSession.discoveredItems.collectAsState()

    val prefsRepo = remember { PreferencesRepository(context) }
    val themePref by prefsRepo.theme.collectAsState(initial = "system")
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> systemInDark
    }

    val shortcutsList by historyManager.shortcuts.collectAsState(initial = historyManager.defaultShortcuts)
    val allHistorySites by historyManager.allSites.collectAsState(initial = historyManager.defaultRecentSites)
    val tabsList by historyManager.openTabs.collectAsState(initial = historyManager.defaultTabs)

    val startUrl = remember(initialUrl) {
        val trimmed = initialUrl.trim()
        if (trimmed.isNotBlank()) {
            MediaClassifier.formatQueryOrUrl(trimmed)
        } else {
            "" // Clean monochrome home page by default
        }
    }

    var launchedWithInitialUrl by remember(initialUrl) {
        mutableStateOf(initialUrl.trim().isNotBlank())
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    val totalDetectedCount = remember(currentUrl, tabDetectedCount, sessionDiscoveredItems.size) {
        if (currentUrl.isBlank() || currentUrl == "about:blank") 0
        else maxOf(tabDetectedCount, sessionDiscoveredItems.size)
    }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var isScanningMedia by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewSessionKey by remember { mutableIntStateOf(0) }
    val capturedStreamUrls = remember { java.util.Collections.synchronizedSet(LinkedHashSet<String>()) }

    // Dialog & Sheet States
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var selectedShortcutForEdit by remember { mutableStateOf<BrowserShortcut?>(null) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tabsList) {
        if (tabsList.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val initialTab = BrowserTabItem(
                id = newId,
                title = if (pageTitle.isNotBlank()) pageTitle else if (currentUrl.isNotBlank()) MediaClassifier.cleanDomainFromUrl(currentUrl) else "Home",
                url = currentUrl
            )
            activeTabId = newId
            historyManager.saveTabs(listOf(initialTab))
        } else if (activeTabId == null || tabsList.none { it.id == activeTabId }) {
            val matching = tabsList.firstOrNull { it.url.isNotBlank() && it.url.equals(currentUrl, ignoreCase = true) }
                ?: tabsList.first()
            activeTabId = matching.id
        }
    }

    fun syncCurrentTabWithPage(targetUrl: String, targetTitle: String) {
        val tabId = activeTabId ?: return
        val currentTabs = tabsList
        val existing = currentTabs.firstOrNull { it.id == tabId }
        val displayTitle = if (targetTitle.isNotBlank() && targetTitle != "about:blank") {
            targetTitle
        } else if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            MediaClassifier.cleanDomainFromUrl(targetUrl)
        } else {
            "Home"
        }
        val cleanUrl = if (targetUrl == "about:blank") "" else targetUrl
        if (existing == null || existing.url != cleanUrl || existing.title != displayTitle) {
            val updatedTabs = if (existing == null) {
                currentTabs + BrowserTabItem(id = tabId, title = displayTitle, url = cleanUrl)
            } else {
                currentTabs.map {
                    if (it.id == tabId) it.copy(title = displayTitle, url = cleanUrl, timestamp = System.currentTimeMillis())
                    else it
                }
            }
            coroutineScope.launch {
                historyManager.saveTabs(updatedTabs)
            }
        }
    }

    val currentView = LocalView.current
    DisposableEffect(isDark) {
        val window = (currentView.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, currentView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    val scrollHandler = remember { BrowserToolbarScrollHandler() }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isBlank()) {
            scrollHandler.show()
        }
    }

    LaunchedEffect(Unit) {
        if (startUrl.isBlank()) {
            tabSession.clearMedia()
            tabSession.updateNavigation("")
            DownloaderSession.clear()
        }
    }

    fun canWebViewGoBack(wv: WebView?): Boolean {
        if (wv == null) return false
        if (!wv.canGoBack()) return false
        val list = wv.copyBackForwardList()
        val currentIndex = list.currentIndex
        if (currentIndex <= 0) return false
        val prevItem = list.getItemAtIndex(currentIndex - 1)
        val prevUrl = prevItem?.url.orEmpty().trim().lowercase()
        return prevUrl.isNotBlank() && prevUrl != "about:blank" && !prevUrl.startsWith("data:")
    }

    fun returnToBrowserHome() {
        launchedWithInitialUrl = false
        currentUrl = ""
        addressInput = ""
        pageTitle = ""
        canGoBack = false
        canGoForward = false
        capturedStreamUrls.clear()
        onUrlChange?.invoke("")
        tabSession.clearMedia()
        tabSession.updateNavigation("")
        DownloaderSession.clear()
        scrollHandler.show()
        webViewInstance?.clearHistory()
        webViewInstance?.loadUrl("about:blank")
    }

    // Synchronized back navigation for DirectBrowserView (shared by native BackHandler and search bar back button)
    fun handleBrowserBackNavigation() {
        when {
            showHistorySheet -> showHistorySheet = false
            showTabsSheet -> showTabsSheet = false
            showSettingsSheet -> showSettingsSheet = false
            showAddShortcutDialog -> showAddShortcutDialog = false
            selectedShortcutForEdit != null -> selectedShortcutForEdit = null
            addressInput.isNotBlank() && currentUrl.isBlank() -> {
                addressInput = ""
            }
            canWebViewGoBack(webViewInstance) -> {
                webViewInstance?.goBack()
            }
            launchedWithInitialUrl && onClose != null -> {
                onClose()
            }
            currentUrl.isNotBlank() -> {
                returnToBrowserHome()
            }
            onClose != null -> onClose()
        }
    }

    // Intercept back button when browser can navigate back, return to home, dismiss a sheet, or exit the browser
    BackHandler(
        enabled = showHistorySheet || showTabsSheet || showSettingsSheet ||
                showAddShortcutDialog || selectedShortcutForEdit != null ||
                (addressInput.isNotBlank() && currentUrl.isBlank()) ||
                canWebViewGoBack(webViewInstance) ||
                currentUrl.isNotBlank() || onClose != null
    ) {
        handleBrowserBackNavigation()
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        if (currentUrl.isBlank()) {
            launchedWithInitialUrl = false
        }
        scrollHandler.show()
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
        tabSession.clearMedia()
        DownloaderSession.clear()
        capturedStreamUrls.clear()
        addressInput = formatted
        currentUrl = formatted
        onUrlChange?.invoke(formatted)
        tabSession.updateNavigation(formatted, pageTitle)
        DownloaderSession.updateBrowserPage(formatted)
        syncCurrentTabWithPage(formatted, pageTitle)
        coroutineScope.launch {
            historyManager.recordVisit(formatted)
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    LaunchedEffect(initialUrl) {
        val trimmed = initialUrl.trim()
        if (trimmed.isNotBlank()) {
            val formatted = MediaClassifier.formatQueryOrUrl(trimmed)
            launchedWithInitialUrl = true
            if (formatted != currentUrl) {
                navigateToUrl(formatted)
            }
        }
    }

    fun triggerOnDemandDiscovery(openWhenDone: Boolean = true) {
        val wv = webViewInstance ?: return
        val current = wv.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: currentUrl
        if (current.isBlank() || current == "about:blank") {
            return
        }

        if (isScanningMedia) {
            return
        }

        isScanningMedia = true

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            val payload = MediaCaptureEngine.parseJsDomScanPayload(jsonRaw, current)
            val effectiveUrl = payload.pageUrl.ifBlank { current }
            val effectiveTitle = payload.pageTitle.ifBlank { pageTitle }
            if (effectiveUrl != currentUrl && effectiveUrl != "about:blank") {
                currentUrl = effectiveUrl
                addressInput = effectiveUrl
                onUrlChange?.invoke(effectiveUrl)
            }
            if (effectiveTitle.isNotBlank() && effectiveTitle != pageTitle) {
                pageTitle = effectiveTitle
                onPageTitleChanged?.invoke(effectiveTitle)
            }

            // Convert captured active streaming URLs (e.g. .m3u8, .mpd, /videoplayback) into ScrapedMediaItem
            val capturedItems = synchronized(capturedStreamUrls) {
                capturedStreamUrls.mapNotNull { streamUrl ->
                    MediaCaptureEngine.buildMediaItem(
                        rawUrl = streamUrl,
                        source = ExtractionSource.NETWORK_INTERCEPT,
                        baseUrl = effectiveUrl,
                        mimeHint = if (streamUrl.contains("/videoplayback")) "video/mp4" else null
                    )
                }
            }

            val combinedDiscovered = payload.items + capturedItems
            val domain = runCatching { URL(effectiveUrl).host }.getOrDefault("")
            if (combinedDiscovered.isNotEmpty()) {
                tabSession.registerMediaBatch(combinedDiscovered, effectiveTitle)
                onBatchExtracted(combinedDiscovered, effectiveTitle, domain)
                DownloaderSession.addDiscoveredItems(combinedDiscovered, effectiveTitle)
            }

            val expectation = ExtractionCoordinator.determineExpectation(effectiveUrl)
            if (expectation != TargetMediaExpectation.GENERIC_ANY || combinedDiscovered.isEmpty()) {
                coroutineScope.launch {
                    try {
                        val extractor = DualMediaExtractor(context)
                        extractor.extract(effectiveUrl, this)
                    } catch (e: Exception) {
                        Log.w("DirectBrowserView", "On-demand extraction error", e)
                    } finally {
                        isScanningMedia = false
                        if (openWhenDone) {
                            onOpenDiscoveredMedia?.invoke() ?: onSwitchToMediaTab()
                        }
                    }
                }
            } else {
                isScanningMedia = false
                if (openWhenDone) {
                    onOpenDiscoveredMedia?.invoke() ?: onSwitchToMediaTab()
                }
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct_browser_view"),
        color = BrowserThemeColors.background(isDark)
    ) {
        // The toolbar is a fixed layout section; webpage content begins below it.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(BrowserThemeColors.background(isDark))
                    .testTag("browser_pinned_top_controls")
            ) {
            // 1. Minimalist Monochrome Top Bar (always visible)
            BrowserTopBar(
                addressInput = addressInput,
                onAddressChange = { addressInput = it },
                onNavigate = { navigateToUrl(it) },
                onHamburgerClick = {
                    if (onMenuClick != null) {
                        onMenuClick.invoke()
                    } else {
                        showTabsSheet = true
                    }
                },
                onDiscoveredMediaClick = {
                    if (totalDetectedCount == 0 && currentUrl.isNotBlank() && currentUrl != "about:blank") {
                        triggerOnDemandDiscovery(openWhenDone = true)
                    } else {
                        onOpenDiscoveredMedia?.invoke() ?: onSwitchToMediaTab()
                    }
                },
                onScanPageMedia = {
                    triggerOnDemandDiscovery(openWhenDone = false)
                },
                isScanningMedia = isScanningMedia,
                discoveredCount = totalDetectedCount,
                onNewTabClick = {
                    val newId = "tab_${System.currentTimeMillis()}"
                    val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                    activeTabId = newId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(tabsList + newTab)
                    }
                },
                onReloadClick = {
                    if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                },
                onPinCurrentSite = {
                    if (currentUrl.isNotBlank()) {
                        coroutineScope.launch {
                            historyManager.addShortcut(pageTitle.ifBlank { currentUrl }, currentUrl)
                        }
                    }
                },
                onSaveToPocket = {
                    if (currentUrl.isNotBlank()) {
                        val pocketRepo = com.amresalehin.emregallery.data.PocketRepository.getInstance(context)
                        val effectiveTitle = pageTitle.ifBlank { webViewInstance?.title ?: currentUrl }
                        
                        webViewInstance?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var ogImg = document.querySelector('meta[property="og:image"]')?.content ||
                                                document.querySelector('meta[name="og:image"]')?.content ||
                                                document.querySelector('meta[name="twitter:image"]')?.content ||
                                                document.querySelector('link[rel="apple-touch-icon"]')?.href || '';
                                    var ogDesc = document.querySelector('meta[property="og:description"]')?.content ||
                                                 document.querySelector('meta[name="description"]')?.content || '';
                                    return JSON.stringify({ image: ogImg, desc: ogDesc });
                                } catch(e) {
                                    return JSON.stringify({ image: '', desc: '' });
                                }
                            })();
                            """.trimIndent()
                        ) { resultJson ->
                            var previewImg = ""
                            var pageDesc = ""
                            if (!resultJson.isNullOrBlank() && resultJson != "null") {
                                try {
                                    val unquoted = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        org.json.JSONTokener(resultJson).nextValue().toString()
                                    } else {
                                        resultJson
                                    }
                                    val json = org.json.JSONObject(unquoted)
                                    previewImg = json.optString("image", "")
                                    pageDesc = json.optString("desc", "")
                                } catch (_: Exception) {}
                            }

                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                description = pageDesc,
                                previewImageUrl = previewImg,
                                autoFetchMetadata = true
                            )
                        } ?: run {
                            pocketRepo.addBookmark(
                                title = effectiveTitle,
                                url = currentUrl,
                                autoFetchMetadata = true
                            )
                        }
                        Toast.makeText(context, "Saved to Pocket", Toast.LENGTH_SHORT).show()
                    }
                },
                onShareClick = {
                    if (currentUrl.isNotBlank()) {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                    }
                },
                onDesktopToggle = { desktop ->
                    isDesktopMode = desktop
                    webViewInstance?.settings?.apply {
                        userAgentString = if (desktop) {
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } else {
                            BotDetectionMitigator.STEALTH_USER_AGENT
                        }
                        useWideViewPort = desktop
                        loadWithOverviewMode = desktop
                    }
                    webViewInstance?.reload()
                },
                isDesktopMode = isDesktopMode,
                onClearHistoryClick = {
                    coroutineScope.launch {
                        historyManager.clearHistory()
                    }
                },
                onSettingsClick = {
                    showSettingsSheet = true
                },
                isWebPage = currentUrl.isNotBlank(),
                onBackClick = {
                    handleBrowserBackNavigation()
                },
                onHomeClick = {
                    returnToBrowserHome()
                },
                onCloseBrowser = onClose,
                tabCount = tabsList.size,
                onOpenDownloads = {
                    onOpenDownloads?.invoke() ?: onSwitchToMediaTab()
                },
                onOpenHistory = {
                    showHistorySheet = true
                },
                onOpenTabs = {
                    showTabsSheet = true
                },
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Loading Progress Bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = BrowserThemeColors.textPrimary(isDark),
                    trackColor = BrowserThemeColors.divider(isDark)
                )
            }

            }

            val isChallengeActive = remember(pageTitle, currentUrl) {
                BotDetectionMitigator.isBotChallenge(pageTitle, currentUrl)
            }
            AnimatedVisibility(
                visible = isChallengeActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browser_bot_challenge_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Security Verification: Tap captcha checkbox if prompted to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // The browser viewport uses the remaining height beneath the toolbar.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Interactive WebView (always instantiated for high-speed responsiveness)
                key(webViewSessionKey) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                                }
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(false)
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    userAgentString = BotDetectionMitigator.STEALTH_USER_AGENT
                                }

                            addJavascriptInterface(
                                BrowserSession.EmreMediaBridge { rawUrl ->
                                    val item = MediaCaptureEngine.buildMediaItem(
                                        rawUrl = rawUrl,
                                        source = ExtractionSource.DOM_SCRAPE,
                                        baseUrl = currentUrl
                                    )
                                    if (item != null) {
                                        tabSession.registerMedia(item)
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                    onPageTitleChanged?.invoke(pageTitle)
                                    tabSession.updateNavigation(currentUrl, pageTitle)
                                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        syncCurrentTabWithPage(currentUrl, pageTitle)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    val lower = urlString.trim().lowercase()
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                val pm = context.packageManager
                                                val resolveInfo = intent.resolveActivity(pm)
                                                if (resolveInfo != null) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (marketIntent.resolveActivity(pm) != null) {
                                                        context.startActivity(marketIntent)
                                                        return true
                                                    } else {
                                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$packageName")
                                                        return true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DirectBrowserView", "Failed to resolve intent scheme: $urlString", e)
                                        }
                                        return true
                                    }

                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        if (intent.resolveActivity(pm) != null) {
                                            context.startActivity(intent)
                                            return true
                                        } else if (lower.startsWith("market://details?id=")) {
                                            val pkg = uri.getQueryParameter("id")
                                            if (!pkg.isNullOrBlank()) {
                                                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                                                return true
                                            }
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w("DirectBrowserView", "No app available to handle scheme: $urlString", e)
                                        return true
                                    } catch (e: Exception) {
                                        Log.w("DirectBrowserView", "Failed to handle external URI: $urlString", e)
                                        return true
                                    }

                                    return true
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return handleExternalNavigation(view, reqUrl)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleExternalNavigation(view, url)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    scrollHandler.show()
                                    capturedStreamUrls.clear()
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.clearMedia()
                                        tabSession.updateNavigation("")
                                        DownloaderSession.clear()
                                        isLoading = false
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    tabSession.clearMedia()
                                    DownloaderSession.clear()
                                    isLoading = true
                                    BotDetectionMitigator.injectStealth(view)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, pageTitle)
                                    DownloaderSession.updateBrowserPage(url)
                                    syncCurrentTabWithPage(url, pageTitle)
                                    canGoBack = canWebViewGoBack(view)
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (url.isNullOrBlank() || url == "about:blank") return
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    val finalTitle = view?.title.orEmpty().ifBlank { pageTitle }
                                    pageTitle = finalTitle
                                    tabSession.updateNavigation(url, finalTitle)
                                    DownloaderSession.updateBrowserPage(url)
                                    syncCurrentTabWithPage(url, finalTitle)
                                    canGoBack = canWebViewGoBack(view)
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        tabSession.updateNavigation("")
                                        canGoBack = false
                                        canGoForward = false
                                        syncCurrentTabWithPage("", "Home")
                                        return
                                    }
                                    BotDetectionMitigator.injectStealth(view)
                                    BotDetectionMitigator.syncCookiesFromWebView(url)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    val finalTitle = view?.title.orEmpty().ifBlank { pageTitle }
                                    pageTitle = finalTitle
                                    tabSession.updateNavigation(url, finalTitle)
                                    syncCurrentTabWithPage(url, finalTitle)
                                    coroutineScope.launch {
                                        historyManager.recordVisit(url, view?.title)
                                    }
                                    // Automatic background media discovery disabled for active browser normal browsing.
                                    // Media discovery is strictly on-demand.
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (reqUrl.startsWith("about:")) return null

                                    // Block ad and tracker networks
                                    if (AdBlockEngine.isAdOrTracker(reqUrl)) {
                                        return AdBlockEngine.createEmptyResponse()
                                    }

                                    // Passively buffer active media streams for on-demand discovery without background downloading
                                    val path = try { java.net.URI.create(reqUrl).path?.lowercase() ?: "" } catch (_: Exception) { "" }
                                    val isStreamOrVideo = path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                                            path.contains("chunklist") || path.endsWith(".mp4") || path.endsWith(".webm") ||
                                            path.endsWith(".mov") || path.endsWith(".mkv") ||
                                            path.contains("/videoplayback") || path.contains("googlevideo.com")

                                    if (isStreamOrVideo) {
                                        capturedStreamUrls.add(reqUrl)
                                    }

                                    return null
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    val didCrash = detail?.didCrash() ?: false
                                    Log.w("DirectBrowserView", "Chromium renderer process gone (didCrash=$didCrash). Cleanly recovering...")
                                    SafeWebViewCleanup.safeDestroy(view)
                                    if (webViewInstance === view) {
                                        webViewInstance = null
                                    }
                                    webViewSessionKey++
                                    return true
                                }
                            }

                            val targetUrl = currentUrl.ifBlank { startUrl }
                            if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
                                loadUrl(targetUrl)
                            }
                        }
                    },
                    update = { wv ->
                        webViewInstance = wv
                        wv.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#111111") else android.graphics.Color.WHITE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            wv.settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    },
                    onRelease = { wv ->
                        SafeWebViewCleanup.safeDestroy(wv)
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )
            }

                // 2. Minimalist Monochrome Mobile Browser Home Layout (blank when empty)
                if (currentUrl.isBlank()) {
                    BrowserHomeContent(
                        shortcuts = shortcutsList,
                        recentSites = allHistorySites,
                        onNavigate = { navigateToUrl(it) },
                        onAddShortcutClick = { showAddShortcutDialog = true },
                        onEditShortcutClick = { selectedShortcutForEdit = it },
                        onSeeAllHistoryClick = { showHistorySheet = true },
                        isDark = isDark,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_empty_home_page")
                    )
                }
            }
        }
    }

    // Modal Dialogs & Sheets
    if (showAddShortcutDialog) {
        AddShortcutDialog(
            onDismiss = { showAddShortcutDialog = false },
            onAdd = { title, url ->
                coroutineScope.launch {
                    historyManager.addShortcut(title, url)
                }
            },
            isDark = isDark
        )
    }

    selectedShortcutForEdit?.let { sc ->
        EditShortcutDialog(
            shortcut = sc,
            onDismiss = { selectedShortcutForEdit = null },
            onUpdate = { title, url ->
                coroutineScope.launch {
                    historyManager.updateShortcut(sc.id, title, url)
                }
            },
            onDelete = {
                coroutineScope.launch {
                    historyManager.removeShortcut(sc.id)
                }
            },
            isDark = isDark
        )
    }

    if (showHistorySheet) {
        BrowserHistoryBottomSheet(
            history = allHistorySites,
            onNavigate = { navigateToUrl(it) },
            onDeleteSite = { url ->
                coroutineScope.launch {
                    historyManager.deleteSite(url)
                }
            },
            onClearAll = {
                coroutineScope.launch {
                    historyManager.clearHistory()
                }
            },
            onDismiss = { showHistorySheet = false },
            isDark = isDark
        )
    }

    if (showTabsSheet) {
        BrowserTabsBottomSheet(
            tabs = tabsList,
            currentUrl = currentUrl,
            activeTabId = activeTabId,
            onSelectTab = { tab ->
                activeTabId = tab.id
                currentUrl = tab.url
                addressInput = tab.url
                pageTitle = tab.title
                onUrlChange?.invoke(tab.url)
                tabSession.clearMedia()
                tabSession.updateNavigation(tab.url, tab.title)
                DownloaderSession.clear()
                scrollHandler.show()
                if (tab.url.isNotBlank()) {
                    webViewInstance?.loadUrl(tab.url)
                } else {
                    webViewInstance?.loadUrl("about:blank")
                }
            },
            onCloseTab = { tabId ->
                val remaining = tabsList.filter { it.id != tabId }
                if (remaining.isEmpty()) {
                    val freshId = "tab_${System.currentTimeMillis()}"
                    val freshTab = BrowserTabItem(id = freshId, title = "New Tab", url = "")
                    activeTabId = freshId
                    currentUrl = ""
                    addressInput = ""
                    pageTitle = ""
                    onUrlChange?.invoke("")
                    tabSession.clearMedia()
                    tabSession.updateNavigation("")
                    DownloaderSession.clear()
                    scrollHandler.show()
                    webViewInstance?.loadUrl("about:blank")
                    coroutineScope.launch {
                        historyManager.saveTabs(listOf(freshTab))
                    }
                } else {
                    if (activeTabId == tabId) {
                        val nextTab = remaining.last()
                        activeTabId = nextTab.id
                        currentUrl = nextTab.url
                        addressInput = nextTab.url
                        pageTitle = nextTab.title
                        onUrlChange?.invoke(nextTab.url)
                        tabSession.clearMedia()
                        tabSession.updateNavigation(nextTab.url, nextTab.title)
                        DownloaderSession.clear()
                        scrollHandler.show()
                        if (nextTab.url.isNotBlank()) {
                            webViewInstance?.loadUrl(nextTab.url)
                        } else {
                            webViewInstance?.loadUrl("about:blank")
                        }
                    }
                    coroutineScope.launch {
                        historyManager.saveTabs(remaining)
                    }
                }
            },
            onNewTab = {
                val newId = "tab_${System.currentTimeMillis()}"
                val newTab = BrowserTabItem(id = newId, title = "New Tab", url = "")
                activeTabId = newId
                currentUrl = ""
                addressInput = ""
                pageTitle = ""
                onUrlChange?.invoke("")
                tabSession.clearMedia()
                tabSession.updateNavigation("")
                DownloaderSession.clear()
                scrollHandler.show()
                webViewInstance?.loadUrl("about:blank")
                coroutineScope.launch {
                    historyManager.saveTabs(tabsList + newTab)
                }
            },
            onDismiss = { showTabsSheet = false },
            isDark = isDark
        )
    }

    if (showSettingsSheet) {
        BrowserSettingsBottomSheet(
            isDesktopMode = isDesktopMode,
            onDesktopModeChange = { desktop ->
                isDesktopMode = desktop
                webViewInstance?.settings?.apply {
                    userAgentString = if (desktop) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        BotDetectionMitigator.STEALTH_USER_AGENT
                    }
                    useWideViewPort = desktop
                    loadWithOverviewMode = desktop
                }
                webViewInstance?.reload()
            },
            onClearCacheClick = {
                webViewInstance?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            },
            onDismiss = { showSettingsSheet = false },
            isDark = isDark
        )
    }
}
