package com.vr2sbs.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vr2sbs.R
import com.vr2sbs.databinding.ActivitySmbBrowserBinding
import com.vr2sbs.smb.MediaEntry
import com.vr2sbs.smb.SmbBookmark
import com.vr2sbs.smb.SmbMediaLoader
import com.vr2sbs.smb.SmbProfile
import com.vr2sbs.smb.SmbSortOrder
import com.vr2sbs.smb.SmbVideoMetadata
import com.vr2sbs.smb.isSmbImage
import com.vr2sbs.smb.normalizeSmbBookmarkUri
import com.vr2sbs.smb.smbFolderAncestors
import com.vr2sbs.smb.sortSmbEntries
import com.vr2sbs.source.SourceDescriptor
import com.vr2sbs.source.SourceMediaKind
import com.vr2sbs.source.SourcePlaylistItem
import com.vr2sbs.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class SmbBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmbBrowserBinding
    private val app by lazy { application as Vr2sbsApplication }
    private val manager by lazy { app.smbClientManager }
    private val profileStore by lazy { app.smbProfileStore }
    private val bookmarkStore by lazy { app.smbBookmarkStore }
    private val trackingManager by lazy { app.trackingSessionManager }
    private val browserPreferences by lazy {
        getSharedPreferences(SMB_BROWSER_PREFERENCES, MODE_PRIVATE)
    }

    private var activeProfile: SmbProfile? = null
    private var currentUri: String? = null
    private val parentUris = ArrayDeque<String>()
    private var currentEntries = emptyList<MediaEntry>()
    private var renderedEntries = emptyList<MediaEntry>()
    private var sortOrder = SmbSortOrder.NEWEST_FIRST
    private var networkJob: Job? = null
    private var metadataScanJob: Job? = null
    private var requestGeneration = 0L
    private var mediaLoader: SmbMediaLoader? = null
    private var pendingScrollAnchor: ScrollAnchor? = null
    private var lastPlayedUri: String? = null

    // Metadata read for sorting, kept independently of the loader's LRU cache
    // so a folder larger than that cache does not trigger another scan after
    // the first pass completes.
    private val scannedMetadata = mutableMapOf<String, SmbVideoMetadata?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sortOrder = SmbSortOrder.fromStoredValue(
            browserPreferences.getString(KEY_SORT_ORDER, null)
        )

        binding.smbConnectButton.setOnClickListener { connectFromForm() }
        binding.smbBookmarkButton.setOnClickListener { toggleBookmarkForCurrentFolder() }
        binding.smbSortButton.setOnClickListener { showSortDialog() }
        binding.smbEntriesList.layoutManager = LinearLayoutManager(this)
        onBackPressedDispatcher.addCallback(this) { navigateUpOrFinish() }
        renderBookmarks()
        renderSavedProfiles()

        if (savedInstanceState == null) {
            autoConnectProfile(intent)?.let { profile ->
                fillForm(profile)
                connect(profile, intent.getBooleanExtra(EXTRA_REMEMBER, false))
            }
        }
    }

    override fun onDestroy() {
        requestGeneration += 1
        networkJob?.cancel()
        metadataScanJob?.cancel()
        mediaLoader?.close()
        mediaLoader = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (activeProfile != null && mediaLoader == null) {
            renderDirectory(revealHighlight = true)
        }
    }

    override fun onStop() {
        pendingScrollAnchor = captureScrollAnchor()
        metadataScanJob?.cancel()
        metadataScanJob = null
        mediaLoader?.close()
        mediaLoader = null
        binding.smbEntriesList.adapter = null
        app.smbMediaCache.flush()
        super.onStop()
    }

    private fun connectFromForm() {
        val profile = runCatching {
            SmbProfile.create(
                host = binding.smbHostInput.text?.toString().orEmpty(),
                share = binding.smbShareInput.text?.toString().orEmpty(),
                domain = binding.smbDomainInput.text?.toString().orEmpty(),
                username = binding.smbUsernameInput.text?.toString().orEmpty(),
                password = binding.smbPasswordInput.text?.toString().orEmpty()
            ).also {
                com.vr2sbs.smb.SmbStorage.validateTargetPart(it.host, "host")
                if (it.share.isNotBlank()) {
                    com.vr2sbs.smb.SmbStorage.validateTargetPart(it.share, "share")
                }
            }
        }.getOrElse {
            showStatus(it.message ?: getString(R.string.smb_error_invalid_profile))
            return
        }
        connect(profile, binding.smbRememberAccount.isChecked)
    }

    private fun connect(
        profile: SmbProfile,
        remember: Boolean,
        openFolderUri: String? = null
    ) {
        val targetUri = openFolderUri
            ?.let(::normalizeSmbBookmarkUri)
            ?.takeUnless { it.equals(profile.rootUri, ignoreCase = true) }
        if (targetUri != null && !targetUri.startsWith(profile.rootUri, ignoreCase = true)) {
            showStatus(
                getString(
                    R.string.smb_error_open_bookmark,
                    getString(R.string.smb_bookmark_outside_share)
                )
            )
            return
        }
        val generation = beginRequest(getString(R.string.smb_connecting))
        networkJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootEntries = manager.connect(profile, remember)
                    if (targetUri == null) rootEntries else manager.list(profile.id, targetUri)
                }
            }
            if (generation != requestGeneration) return@launch
            setBusy(false)
            result.onSuccess { entries ->
                activeProfile = profile
                currentUri = targetUri ?: profile.rootUri
                parentUris.clear()
                if (targetUri != null) {
                    parentUris.addAll(smbFolderAncestors(profile.rootUri, targetUri))
                }
                currentEntries = entries
                pendingScrollAnchor = null
                lastPlayedUri = null
                scannedMetadata.clear()
                renderSavedProfiles()
                renderDirectory()
            }.onFailure { error ->
                showStatus(
                    if (targetUri == null) {
                        getString(R.string.smb_error_connect_failed, safeErrorMessage(error))
                    } else {
                        getString(R.string.smb_error_open_bookmark, safeErrorMessage(error))
                    }
                )
            }
        }
    }

    private fun loadDirectory(targetUri: String, onSuccess: () -> Unit) {
        val profile = activeProfile ?: return
        val generation = beginRequest(getString(R.string.smb_loading))
        networkJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { manager.list(profile.id, targetUri) }
            }
            if (generation != requestGeneration) return@launch
            setBusy(false)
            result.onSuccess { entries ->
                onSuccess()
                currentUri = targetUri
                currentEntries = entries
                pendingScrollAnchor = null
                scannedMetadata.clear()
                renderDirectory()
            }.onFailure { error ->
                showStatus(getString(R.string.smb_error_list_failed, safeErrorMessage(error)))
            }
        }
    }

    private fun openDirectory(entry: MediaEntry) {
        val previous = currentUri ?: return
        loadDirectory(entry.uri) { parentUris.addLast(previous) }
    }

    private fun navigateUpOrFinish() {
        val parent = parentUris.lastOrNull()
        if (parent == null) {
            finish()
            return
        }
        loadDirectory(parent) { parentUris.removeLast() }
    }

    private fun renderDirectory(
        preserveScroll: Boolean = false,
        revealHighlight: Boolean = false
    ) {
        val profile = activeProfile ?: return
        if (preserveScroll) {
            pendingScrollAnchor = captureScrollAnchor()
        }
        val loader = mediaLoader ?: SmbMediaLoader(
            manager = manager,
            profileId = profile.id,
            thumbnailSizePx = resources.getDimensionPixelSize(R.dimen.smb_thumbnail_size),
            mediaCache = app.smbMediaCache
        ).also { mediaLoader = it }
        val atDeviceRoot = profile.share.isBlank() && currentUri == profile.rootUri
        val visibleEntries = if (atDeviceRoot) {
            // Windows hides administrative shares (IPC$, C$, ADMIN$, ...) from Network.
            currentEntries.filterNot { it.name.endsWith("$") }
        } else {
            currentEntries
        }
        val entries = sortSmbEntries(visibleEntries, sortOrder) { entry ->
            scannedMetadata[entry.uri] ?: loader.cachedMetadata(entry)
        }
        val scrollAnchor = pendingScrollAnchor?.takeIf { it.folderUri == currentUri }
        pendingScrollAnchor = null
        binding.smbConnectionPanel.visibility = View.GONE
        binding.smbNavigationRow.visibility = View.VISIBLE
        binding.smbCurrentFolderText.visibility = View.VISIBLE
        binding.smbEntriesList.visibility = View.VISIBLE
        binding.smbCurrentFolderText.text = getString(
            R.string.smb_current_folder,
            currentUri.orEmpty().removePrefix("smb://")
        )
        updateBookmarkButton()
        binding.smbSortButton.text = getString(sortOrderLabelRes(sortOrder))
        showStatus(
            if (entries.isEmpty()) {
                getString(if (atDeviceRoot) R.string.smb_no_shares else R.string.smb_empty_folder)
            } else {
                getString(R.string.smb_entries_count, entries.size)
            }
        )
        val metadataTargets = entries.filter {
            !it.directory && !scannedMetadata.containsKey(it.uri) && !loader.hasMetadata(it)
        }
        loader.thumbnailsEnabled = metadataTargets.isEmpty()
        renderedEntries = entries
        binding.smbEntriesList.adapter = SmbEntryAdapter(
            entries = entries,
            mediaLoader = loader,
            highlightedUri = lastPlayedUri
        ) { entry ->
            if (entry.directory) openDirectory(entry) else openMedia(entry)
        }
        restoreScroll(entries, scrollAnchor)
        if (revealHighlight) ensureHighlightedEntryVisible(entries)
        if (metadataTargets.isNotEmpty()) startMetadataScan(metadataTargets)
    }

    /**
     * Reads every missing video's details with several parallel SMB sessions,
     * updating each row as soon as its information arrives. Thumbnails stay
     * disabled until this pass ends, so the folder's information is visible
     * before any preview frames are fetched.
     */
    private fun startMetadataScan(targets: List<MediaEntry>) {
        val loader = mediaLoader ?: return
        metadataScanJob?.cancel()
        metadataScanJob = lifecycleScope.launch {
            val total = targets.size
            var completed = 0
            var lastProgressUpdate = 0
            showStatus(getString(R.string.smb_reading_video_info, 0, total))
            val results = Channel<MetadataResult>(Channel.UNLIMITED)
            val nextIndex = AtomicInteger(0)
            repeat(minOf(METADATA_PROBE_PARALLELISM, total)) {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= targets.size) break
                        val entry = targets[index]
                        val metadata = runCatching { loader.probeMetadata(entry) }.getOrNull()
                        results.send(MetadataResult(entry.uri, metadata))
                    }
                }
            }
            repeat(total) {
                val result = results.receive()
                if (!isActive) return@launch
                scannedMetadata[result.uri] = result.metadata
                (binding.smbEntriesList.adapter as? SmbEntryAdapter)
                    ?.refreshMetadata(result.uri)
                completed += 1
                if (completed == total ||
                    completed - lastProgressUpdate >= METADATA_PROGRESS_STEP
                ) {
                    showStatus(
                        getString(R.string.smb_reading_video_info, completed, total)
                    )
                    lastProgressUpdate = completed
                }
            }
            if (!isActive) return@launch
            metadataScanJob = null
            val activeLoader = mediaLoader ?: return@launch
            showStatus(getString(R.string.smb_entries_count, renderedEntries.size))
            if (sortOrder.requiresVideoMetadata) {
                // Details are now known; apply the chosen resolution or
                // length order. This also starts the thumbnail phase.
                renderDirectory(preserveScroll = true)
            } else {
                activeLoader.thumbnailsEnabled = true
                (binding.smbEntriesList.adapter as? SmbEntryAdapter)?.refreshThumbnails()
            }
        }
    }

    private fun captureScrollAnchor(): ScrollAnchor? {
        val folderUri = currentUri ?: return null
        val layoutManager = binding.smbEntriesList.layoutManager as? LinearLayoutManager
            ?: return null
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        val firstView = layoutManager.findViewByPosition(firstPosition)
        val offset = firstView?.top?.minus(binding.smbEntriesList.paddingTop) ?: 0
        return ScrollAnchor(
            folderUri = folderUri,
            anchorUri = renderedEntries.getOrNull(firstPosition)?.uri,
            fallbackPosition = firstPosition,
            offset = offset
        )
    }

    private fun restoreScroll(entries: List<MediaEntry>, anchor: ScrollAnchor?) {
        val layoutManager = binding.smbEntriesList.layoutManager as? LinearLayoutManager ?: return
        if (anchor == null || anchor.folderUri != currentUri || entries.isEmpty()) {
            layoutManager.scrollToPosition(0)
            return
        }
        val anchoredPosition = anchor.anchorUri
            ?.let { uri -> entries.indexOfFirst { it.uri == uri } }
            ?.takeIf { it >= 0 }
            ?: anchor.fallbackPosition
        layoutManager.scrollToPositionWithOffset(
            anchoredPosition.coerceIn(0, entries.lastIndex),
            anchor.offset
        )
    }

    private fun ensureHighlightedEntryVisible(entries: List<MediaEntry>) {
        val highlightedUri = lastPlayedUri ?: return
        val index = entries.indexOfFirst { it.uri == highlightedUri }
        if (index < 0) return
        binding.smbEntriesList.post {
            val layoutManager = binding.smbEntriesList.layoutManager as? LinearLayoutManager
                ?: return@post
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            if (firstVisible == RecyclerView.NO_POSITION || index < firstVisible || index > lastVisible) {
                layoutManager.scrollToPositionWithOffset(
                    index,
                    resources.getDimensionPixelSize(R.dimen.launcher_section_spacing) / 2
                )
            }
        }
    }

    private fun renderSavedProfiles() {
        binding.smbSavedProfilesContainer.removeAllViews()
        profileStore.loadAll().forEach { profile ->
            addButton(
                binding.smbSavedProfilesContainer,
                getString(R.string.smb_saved_profile, profile.displayName)
            ) {
                fillForm(profile)
                connect(profile, remember = true)
            }.setOnLongClickListener {
                confirmForget(profile)
                true
            }
        }
    }

    private fun renderBookmarks() {
        binding.smbBookmarksContainer.removeAllViews()
        val bookmarks = bookmarkStore.loadAll()
        binding.smbBookmarksSection.visibility =
            if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
        bookmarks.forEach { bookmark ->
            addButton(binding.smbBookmarksContainer, bookmark.displayPath) {
                openBookmark(bookmark)
            }.setOnLongClickListener {
                confirmRemoveBookmark(bookmark)
                true
            }
        }
    }

    private fun openBookmark(bookmark: SmbBookmark) {
        val profile = profileStore.find(bookmark.profileId)
        if (profile == null) {
            showStatus(getString(R.string.smb_bookmark_account_missing))
            return
        }
        fillForm(profile)
        connect(profile, remember = true, openFolderUri = bookmark.uri)
    }

    private fun toggleBookmarkForCurrentFolder() {
        val profile = activeProfile ?: return
        val uri = currentUri ?: return
        val existing = bookmarkStore.find(uri)
        if (existing != null) {
            bookmarkStore.remove(existing.uri)
            renderBookmarks()
            updateBookmarkButton()
            showStatus(getString(R.string.smb_bookmark_removed, existing.displayPath))
            return
        }
        val saveProfile = runCatching { profileStore.save(profile) }
        if (saveProfile.isFailure) {
            showStatus(bookmarkSaveFailure(saveProfile.exceptionOrNull()))
            return
        }
        val bookmark = SmbBookmark(
            uri = normalizeSmbBookmarkUri(uri),
            profileId = profile.id,
            createdAt = System.currentTimeMillis()
        )
        val saveBookmark = runCatching { bookmarkStore.save(bookmark) }
        if (saveBookmark.isFailure) {
            showStatus(bookmarkSaveFailure(saveBookmark.exceptionOrNull()))
            return
        }
        renderBookmarks()
        updateBookmarkButton()
        showStatus(getString(R.string.smb_bookmark_added, bookmark.displayPath))
    }

    private fun updateBookmarkButton() {
        val uri = currentUri.orEmpty()
        binding.smbBookmarkButton.text = getString(
            if (uri.isNotBlank() && bookmarkStore.isBookmarked(uri)) {
                R.string.smb_bookmarked
            } else {
                R.string.smb_bookmark
            }
        )
    }

    private fun confirmRemoveBookmark(bookmark: SmbBookmark) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smb_remove_bookmark_title)
            .setMessage(getString(R.string.smb_remove_bookmark_message, bookmark.displayPath))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_remove_bookmark) { _, _ ->
                bookmarkStore.remove(bookmark.uri)
                renderBookmarks()
                updateBookmarkButton()
            }
            .show()
    }

    private fun bookmarkSaveFailure(error: Throwable?): String = getString(
        R.string.smb_bookmark_save_failed,
        safeErrorMessage(error ?: IllegalStateException(getString(R.string.error_unknown)))
    )

    private fun fillForm(profile: SmbProfile) {
        binding.smbHostInput.setText(profile.host)
        binding.smbShareInput.setText(profile.share)
        binding.smbDomainInput.setText(profile.domain)
        binding.smbUsernameInput.setText(profile.username)
        binding.smbPasswordInput.setText(profile.password)
        binding.smbRememberAccount.isChecked = true
    }

    private fun confirmForget(profile: SmbProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smb_forget_title)
            .setMessage(getString(R.string.smb_forget_message, profile.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_forget) { _, _ ->
                profileStore.remove(profile.id)
                bookmarkStore.removeForProfile(profile.id)
                renderBookmarks()
                renderSavedProfiles()
            }
            .show()
    }

    private fun showSortDialog() {
        val options = SmbSortOrder.entries.toTypedArray()
        val labels = options.map { getString(sortOrderLabelRes(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smb_sort_title)
            .setSingleChoiceItems(labels, options.indexOf(sortOrder)) { dialog, which ->
                dialog.dismiss()
                selectSortOrder(options[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectSortOrder(order: SmbSortOrder) {
        if (sortOrder == order) return
        sortOrder = order
        browserPreferences.edit().putString(KEY_SORT_ORDER, order.name).apply()
        metadataScanJob?.cancel()
        metadataScanJob = null
        renderDirectory(preserveScroll = true)
    }

    private fun sortOrderLabelRes(order: SmbSortOrder): Int = when (order) {
        SmbSortOrder.NEWEST_FIRST -> R.string.smb_sort_newest_first
        SmbSortOrder.OLDEST_FIRST -> R.string.smb_sort_oldest_first
        SmbSortOrder.RESOLUTION_DESC -> R.string.smb_sort_resolution
        SmbSortOrder.DURATION_DESC -> R.string.smb_sort_duration
        SmbSortOrder.SIZE_DESC -> R.string.smb_sort_size
    }

    private fun openMedia(entry: MediaEntry) {
        val profile = activeProfile ?: return
        lastPlayedUri = entry.uri
        pendingScrollAnchor = captureScrollAnchor()
        val isImage = isSmbImage(entry)
        val playlist = if (isImage) {
            renderedEntries
                .filter(::isSmbImage)
                .map { image -> SourcePlaylistItem(uri = image.uri, displayName = image.name) }
        } else {
            emptyList()
        }
        val source = SourceDescriptor(
            original = entry.uri,
            normalized = entry.uri,
            type = SourceType.SMB_URI,
            displayName = entry.name,
            profileId = profile.id,
            mediaKind = if (isImage) SourceMediaKind.IMAGE else SourceMediaKind.VIDEO,
            playlist = playlist
        )
        lifecycleScope.launch {
            val probe = trackingManager.probeConnection()
            if (!probe.connected) {
                Toast.makeText(
                    this@SmbBrowserActivity,
                    R.string.toast_glasses_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val target = if (
                shouldLaunchTrackingSetupForSource(
                    trackingManager.sessionState.value,
                    imuTrackingEnabled = ImuTrackingSettings.isEnabled(this@SmbBrowserActivity)
                )
            ) {
                TrackingSetupActivity::class.java
            } else {
                TrackingReadyActivity::class.java
            }
            startActivity(
                Intent(this@SmbBrowserActivity, target)
                    .putExtra(PlayerActivity.EXTRA_SOURCE, source)
            )
        }
    }

    private fun beginRequest(status: String): Long {
        requestGeneration += 1
        networkJob?.cancel()
        metadataScanJob?.cancel()
        metadataScanJob = null
        setBusy(true)
        showStatus(status)
        return requestGeneration
    }

    private fun setBusy(busy: Boolean) {
        binding.smbConnectButton.isEnabled = !busy
        binding.smbBookmarkButton.isEnabled = !busy
        binding.smbSortButton.isEnabled = !busy
    }

    private fun showStatus(message: String) {
        binding.smbStatusText.text = message
    }

    private fun safeErrorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_unknown)

    private fun addButton(
        container: LinearLayout,
        label: String,
        onClick: (View) -> Unit
    ): MaterialButton {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
        }
        container.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.launcher_section_spacing) / 2 }
        )
        return button
    }

    private fun autoConnectProfile(intent: Intent): SmbProfile? {
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        if (host.isBlank()) return null
        return SmbProfile.create(
            host = host,
            share = intent.getStringExtra(EXTRA_SHARE).orEmpty(),
            domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty(),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        )
    }

    private data class ScrollAnchor(
        val folderUri: String,
        val anchorUri: String?,
        val fallbackPosition: Int,
        val offset: Int
    )

    private data class MetadataResult(
        val uri: String,
        val metadata: SmbVideoMetadata?
    )

    companion object {
        private const val METADATA_PROBE_PARALLELISM = 4
        private const val METADATA_PROGRESS_STEP = 8
        private const val EXTRA_HOST = "com.vr2sbs.app.extra.SMB_HOST"
        private const val EXTRA_SHARE = "com.vr2sbs.app.extra.SMB_SHARE"
        private const val EXTRA_DOMAIN = "com.vr2sbs.app.extra.SMB_DOMAIN"
        private const val EXTRA_USERNAME = "com.vr2sbs.app.extra.SMB_USERNAME"
        private const val EXTRA_PASSWORD = "com.vr2sbs.app.extra.SMB_PASSWORD"
        private const val EXTRA_REMEMBER = "com.vr2sbs.app.extra.SMB_REMEMBER"
        private const val SMB_BROWSER_PREFERENCES = "smb_browser"
        private const val KEY_SORT_ORDER = "sort_order"

        fun createIntent(context: Context, profile: SmbProfile, remember: Boolean): Intent =
            Intent(context, SmbBrowserActivity::class.java).apply {
                putExtra(EXTRA_HOST, profile.host)
                putExtra(EXTRA_SHARE, profile.share)
                putExtra(EXTRA_DOMAIN, profile.domain)
                putExtra(EXTRA_USERNAME, profile.username)
                putExtra(EXTRA_PASSWORD, profile.password)
                putExtra(EXTRA_REMEMBER, remember)
            }
    }
}
