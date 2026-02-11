package com.kumpali.storax

import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import androidx.core.net.toUri
import android.hardware.usb.UsbManager
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.core.graphics.scale


/**
 * Request code used for SAF (Storage Access Framework) folder picker.
 *
 * This must be a compile-time constant so it can be safely used
 * across Activity restarts and configuration changes.
 */
private const val SAF_REQUEST_CODE = 9091
private const val REQ_SAF_OPEN = 9001
/**
 * StoraxPlugin
 *
 * This plugin provides a complete file-management backend for Flutter,
 * supporting:
 *
 * - Native file system (internal storage, SD card, USB, adopted storage)
 * - SAF (Storage Access Framework) folders
 * - Directory listing and recursive traversal
 * - File filtering (size, date, extension, MIME)
 * - Permission diagnostics
 * - USB insert/remove detection
 *
 * The plugin is ActivityAware because:
 * - SAF requires an Activity to launch system UI
 * - Permission screens require an Activity
 */
class StoraxPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener
{

    /**
     * MethodChannel used to communicate with Flutter.
     * All file operations are triggered via this channel.
     */
    private lateinit var channel: MethodChannel

    /**
     * Application context.
     *
     * Safe to keep long-term. Used for:
     * - ContentResolver
     * - StorageManager
     * - BroadcastReceiver registration
     */
    private lateinit var context: Context

    /**
     * Reference to the current Activity.
     *
     * Required for:
     * - Opening SAF picker
     * - Opening Android settings screens
     */
    private var activity: Activity? = null

    /**
     * Single-thread executor used for all IO-heavy work.
     *
     * Prevents ANRs by ensuring filesystem access never
     * blocks the main UI thread.
     */
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // SAF pending state (single-flight)
    private var pendingResult: MethodChannel.Result? = null
    private var pendingMime: String? = null

    // SAF tree → resolved filesystem prefix
    private val safRootPathCache = ConcurrentHashMap<Uri, String>()
    // File path → resolved document URI
    private val safFileCache = ConcurrentHashMap<String, Uri>()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─────────────────────────────────────────────
    // Flutter engine lifecycle
    // ─────────────────────────────────────────────

    /**
     * Called when the Flutter engine attaches this plugin.
     *
     * This is where we:
     * - Save application context
     * - Create the MethodChannel
     * - Register USB insert/remove listeners
     */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "storax")
        channel.setMethodCallHandler(this)
        registerUsbReceiver()
    }

    /**
     * Called when the Flutter engine detaches this plugin.
     *
     * Cleanup is critical here to avoid:
     * - Memory leaks
     * - Zombie broadcast receivers
     * - Thread leaks
     */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unregisterUsbReceiver()
        executor.shutdown()
        channel.setMethodCallHandler(null)
    }

    // ─────────────────────────────────────────────
    // Activity lifecycle (SAF + permissions)
    // ─────────────────────────────────────────────

    /**
     * Called when the plugin is attached to an Activity.
     *
     * We:
     * - Capture the Activity reference
     * - Register for SAF picker results
     */
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }


    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { activity = binding.activity
        binding.addActivityResultListener(this)
    }
    override fun onDetachedFromActivity() { activity = null }

    // ─────────────────────────────────────────────
    // MethodChannel entry point (public API)
    // ─────────────────────────────────────────────

    /**
     * Receives method calls from Flutter.
     *
     * Each method corresponds to a single backend capability:
     * - Storage discovery
     * - Directory listing
     * - Traversal
     * - Permissions
     * - Diagnostics
     */
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "gPV" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
            }

            "gSDKV" -> {
                result.success(Build.VERSION.SDK_INT)
            }
            /**
             * Returns all native filesystem roots:
             * - Internal storage
             * - SD card
             * - USB
             * - Adopted storage
             */
            "getNativeRoots" ->
                result.success(getNativeRoots())

            /**
             * Returns a unified list of:
             * - Native roots
             * - SAF roots (user-picked folders)
             */
            "getAllRoots" ->
                result.success(getUnifiedRoots())

            /**
             * Lists immediate children of a directory.
             *
             * This is non-recursive and optimized for UI browsing.
             */
            "listDirectory" -> {
                val target = call.argument<String>("target") ?: return
                val isSaf = call.argument<Boolean>("isSaf") ?: false
                val filters = call.argument<Map<String, Any>>("filters")

                executor.execute {
                    val data =
                        if (isSaf) listSafDirectory(target.toUri(), filters)
                        else listNativeDirectory(File(target), filters)

                    mainHandler.post {
                        result.success(data)   // ✅ SAFE
                    }
                }

            }

            /**
             * Recursively traverses a directory tree.
             *
             * Used for:
             * - Search
             * - Indexing
             * - Analytics
             */
            "traverseDirectory" -> {
                val target = call.argument<String>("target") ?: return
                val isSaf = call.argument<Boolean>("isSaf") ?: false
                val maxDepth = call.argument<Int>("maxDepth") ?: 10
                val filters = call.argument<Map<String, Any>>("filters")

                executor.execute {
                    val out = mutableListOf<Map<String, Any?>>()
                    if (isSaf) {
                        traverseSaf(
                            DocumentFile.fromTreeUri(context, target.toUri()),
                            0, maxDepth, filters, out
                        )
                    } else {
                        traverseNative(File(target), 0, maxDepth, filters, out)
                    }
                    mainHandler.post {
                        result.success(out)   // ✅ SAFE
                    }
                }
            }

            /**
             * Opens the system SAF folder picker.
             *
             * Used when:
             * - Native paths are restricted
             * - USB / SD is SAF-only
             * - Play Store compliance requires scoped access
             */
            "openSafFolderPicker" -> {
                openSafPicker()
                result.success(true)
            }

            /**
             * Checks if MANAGE_EXTERNAL_STORAGE is granted.
             */
            "hasAllFilesAccess" ->
                result.success(hasAllFilesAccess())

            /**
             * Opens system settings screen to grant file manager access.
             */
            "requestAllFilesAccess" -> {
                requestAllFilesAccess()
                result.success(true)
            }

            /**
             * Returns OEM information.
             *
             * Useful for debugging OEM-specific restrictions.
             */
            "detectOEM" ->
                result.success(detectOEM())

            /**
             * High-level permission & environment diagnostics.
             */
            "permissionHealthCheck" ->
                result.success(permissionHealthCheck())
            "openFile" -> {
                val path = call.argument<String>("path")
                val uriStr = call.argument<String>("uri")
                val mime = call.argument<String>("mime") ?: "*/*"

                // ─────────────────────────────────────────────
                // Guard: single in-flight SAF picker only
                // ─────────────────────────────────────────────
                if (pendingResult != null) {
                    result.error("BUSY", "Another file operation in progress", null)
                    return
                }

                // ─────────────────────────────────────────────
                // Guard: mutually exclusive inputs
                // ─────────────────────────────────────────────
                if (path != null && uriStr != null) {
                    result.error(
                        "INVALID_ARGS",
                        "Provide either 'path' or 'uri', not both",
                        null
                    )
                    return
                }

                // ─────────────────────────────────────────────
                // MODE 1: PATH IS AUTHORITATIVE
                // ─────────────────────────────────────────────
                if (path != null) {
                    val file = File(path)

                    if (!file.exists()) {
                        result.error("NOT_FOUND", "File does not exist", null)
                        return
                    }

                    // 1️⃣ If file belongs to a persisted SAF tree, use SAF directly
                    val safUri = resolveFileInSafTree(file)
                    if (safUri != null) {
                        openUri(safUri, mime, result)
                        return
                    }

                    // 2️⃣ Otherwise, path-based open MUST be attempted
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        openUri(uri, mime, result)
                        return
                    } catch (e: Exception) {
                        result.error(
                            "CANNOT_OPEN",
                            "Failed to open file via path",
                            e.message
                        )
                        return
                    }
                }

                // ─────────────────────────────────────────────
                // MODE 2: DIRECT URI (already trusted / SAF)
                // ─────────────────────────────────────────────
                if (uriStr != null) {
                    openUri(uriStr.toUri(), mime, result)
                    return
                }

                // ─────────────────────────────────────────────
                // MODE 3: EXPLICIT USER-MEDIATED PICKER
                // (only reached if Flutter provided neither)
                // ─────────────────────────────────────────────
                launchSaf(mime, result)

            }
            "createFolder" -> {
                val parent = call.argument<String>("parent") ?: run {
                    result.error("INVALID", "Missing parent", null)
                    return
                }
                val name = call.argument<String>("name") ?: run {
                    result.error("INVALID", "Missing name", null)
                    return
                }
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                executor.execute {
                    try {
                        if (isSaf) {
                            createSafFolder(parent.toUri(), name)
                        } else {
                            createNativeFolder(parent, name)
                        }
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("CREATE_FOLDER_FAILED", e.message, null)
                        }
                    }
                }
            }

            "createFile" -> {
                val parent = call.argument<String>("parent") ?: run {
                    result.error("INVALID", "Missing parent", null)
                    return
                }
                val name = call.argument<String>("name") ?: run {
                    result.error("INVALID", "Missing name", null)
                    return
                }
                val mime = call.argument<String>("mime") // optional
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                executor.execute {
                    try {
                        if (isSaf) {
                            createSafFile(parent.toUri(), name, mime)
                        } else {
                            createNativeFile(parent, name)
                        }
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("CREATE_FILE_FAILED", e.message, null)
                        }
                    }
                }
            }

            "copy" -> {
                val source = call.argument<String>("source") ?: run {
                    result.error("INVALID", "Missing source", null)
                    return
                }
                val destination = call.argument<String>("destination") ?: run {
                    result.error("INVALID", "Missing destination", null)
                    return
                }
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                val jobId = newJobId()
                result.success(jobId) // 🔥 return immediately

                executor.execute {
                    try {
                        if (isSaf) {
                            val srcUri = source.toUri()
                            val dstDir = DocumentFile.fromTreeUri(context, destination.toUri())
                                ?: throw IllegalStateException("Invalid SAF destination")
                            val srcDoc = DocumentFile.fromSingleUri(context, srcUri)
                            val name = srcDoc?.name ?: "file_${System.currentTimeMillis()}"
                            copySafFile(srcUri, dstDir, name, jobId)
                        } else {
                            copyNativeFile(
                                File(source),
                                File(destination),
                                jobId
                            )
                        }

                    } catch (e: Exception) {
                        emitProgress(
                            mapOf("jobId" to jobId, "error" to e.message)
                        )
                    }
                }
            }
            "rename" -> {
                val target = call.argument<String>("target") ?: run {
                    result.error("INVALID", "Missing target", null)
                    return
                }
                val newName = call.argument<String>("newName") ?: run {
                    result.error("INVALID", "Missing newName", null)
                    return
                }
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                executor.execute {
                    try {
                        if (isSaf) {
                            renameSafWithFallback(
                                target.toUri(),
                                newName
                            )
                        } else {
                            renameNative(target, newName)
                        }

                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("RENAME_FAILED", e.message, null)
                        }
                    }
                }

            }

            "delete" -> {
                val target = call.argument<String>("target") ?: return
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                executor.execute {
                    try {
                        if (isSaf) {
                            deleteSaf(target.toUri())
                        } else {
                            deleteNative(target)
                        }
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("DELETE_FAILED", e.message, null)
                        }
                    }
                }
            }
            "move" -> {
                val source = call.argument<String>("source") ?: return
                val destination = call.argument<String>("destination") ?: return
                val isSaf = call.argument<Boolean>("isSaf") ?: false

                val jobId = newJobId()
                result.success(jobId)

                executor.execute {
                    try {
                        if (isSaf) {
                            val srcUri = source.toUri()
                            val dstDir = DocumentFile.fromTreeUri(context, destination.toUri())
                                ?: throw IllegalStateException("Invalid SAF destination")

                            copySafFile(srcUri, dstDir, "moved_${System.currentTimeMillis()}", jobId)
                            deleteSaf(srcUri)
                        } else {
                            copyNativeFile(File(source), File(destination), jobId)
                            File(source).delete()
                        }
                    } catch (e: Exception) {
                        emitProgress(
                            mapOf("jobId" to jobId, "error" to e.message)
                        )
                    }
                }
            }
            "moveToTrash" -> {
                val target = call.argument<String>("target") ?: run {
                    result.error("INVALID", "Missing target", null)
                    return
                }
                val isSaf = call.argument<Boolean>("isSaf") ?: false
                val safRootUri = call.argument<String>("safRootUri")

                executor.execute {
                    try {
                        if (isSaf) {
                            if (safRootUri == null) {
                                throw IllegalArgumentException("Missing safRootUri")
                            }
                            moveSafToTrash(target.toUri(), safRootUri.toUri())
                        } else {
                            moveNativeToTrash(target)
                        }

                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("TRASH_FAILED", e.message, null)
                        }
                    }
                }
            }
            "listTrash" -> {
                executor.execute {
                    try {
                        val list =(
                            readNativeTrashEntries() +
                                    readSafTrashEntries()
                        )
                                        .map { it.toMap() }

                        mainHandler.post { result.success(list) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("LIST_TRASH_FAILED", e.message, null)
                        }
                    }
                }
            }
            "restoreFromTrash" -> {
                val entryMap = call.argument<Map<String, Any?>>("entry") ?: run {
                    result.error("INVALID", "Missing entry", null)
                    return
                }

                executor.execute {
                    try {
                        val entry = trashEntryFromMap(entryMap)

                        if (entry.isSaf) {
                            restoreSaf(entry)
                        } else {
                            restoreNative(entry)
                        }

                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("RESTORE_FAILED", e.message, null)
                        }
                    }
                }
            }
            "emptyTrash" -> {
                val isSaf = call.argument<Boolean>("isSaf") ?: false
                val safRootUri = call.argument<String>("safRootUri")

                executor.execute {
                    try {
                        if (isSaf) {
                            if (safRootUri == null) {
                                throw IllegalArgumentException("Missing safRootUri")
                            }
                            emptySafTrash(safRootUri.toUri())
                            emptySafTrashMetadata()
                        } else {
                            emptyNativeTrash()
                        }

                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("EMPTY_TRASH_FAILED", e.message, null)
                        }
                    }
                }
            }
            "emptySafTrashMetadata" -> {
                executor.execute {
                    try {
                        emptySafTrashMetadata()
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post {
                            result.error("EMPTY_SAF_METADATA_FAILED", e.message, null)
                        }
                    }
                }
            }
            "generateGifThumbnail" -> handleGifThumbnail(call, result)
            "generateUniqueFrame" -> handleUniqueFrameSequence(call, result)
            else -> result.notImplemented()
        }
    }
    private fun resolveSafRootPath(treeUri: Uri): String? {
        safRootPathCache[treeUri]?.let { return it }

        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val name = doc.name ?: return null

        // Heuristic: SAF root name must exist in filesystem path
        val candidates = listOf(
            File("/storage"),
            Environment.getExternalStorageDirectory()
        )

        for (base in candidates) {
            base.walkTopDown()
                .maxDepth(3)
                .firstOrNull { it.name == name }
                ?.let {
                    val path = it.absolutePath
                    safRootPathCache[treeUri] = path
                    return path
                }
        }

        return null
    }

    private fun resolveFileInSafTree(file: File): Uri? {
        val path = file.absolutePath

        // 🚀 Fast path: cache hit
        safFileCache[path]?.let { return it }

        val trees = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

        for (tree in trees) {
            val rootPath = resolveSafRootPath(tree) ?: continue
            if (!path.startsWith(rootPath)) continue

            val relativePath = path.removePrefix(rootPath)
                .trimStart(File.separatorChar)

            val docUri = resolveRelativeDocument(tree, relativePath)
            if (docUri != null) {
                safFileCache[path] = docUri
                return docUri
            }
        }

        return null
    }

    private fun resolveRelativeDocument(
        treeUri: Uri,
        relativePath: String
    ): Uri? {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (relativePath.isEmpty()) return current.uri

        val parts = relativePath.split(File.separatorChar)

        for (segment in parts) {
            current = current.findFile(segment) ?: return null
        }

        return current.uri.takeIf { current.isFile }
    }


    // Native storage roots
    // ─────────────────────────────────────────────
    private fun addRoot(
        roots: MutableList<Map<String, Any?>>,
        name: String,
        dir: File
    ) {
        try {
            val stat = StatFs(dir.absolutePath)
            roots.add(
                mapOf(
                    "type" to "native",
                    "name" to name,
                    "path" to dir.absolutePath,
                    "total" to stat.totalBytes,
                    "free" to stat.availableBytes,
                    "used" to stat.totalBytes - stat.availableBytes,
                    "writable" to dir.canWrite()
                )
            )
        } catch (e: Exception) {
            Log.e("Storax", "addRoot", e)
        }
    }

    private fun getNativeRoots(): List<Map<String, Any?>> {
        val roots = mutableListOf<Map<String, Any?>>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+
            sm.storageVolumes.forEach { vol ->
                val dir = vol.directory ?: return@forEach
                addRoot(roots, vol.getDescription(context), dir)
            }
        } else {
            // API 24–29

            val primary: File? = try {
                Environment.getExternalStorageDirectory()
            } catch (e: Exception) {
                Log.e("Storax", "getNativeRoots", e)
                null
            }

            if (primary != null) {
                addRoot(roots, "Internal storage", primary)
            }

            val secondaryRoots = arrayOf(
                File("/storage"),
                File("/mnt")
            )

            secondaryRoots.forEach { base ->
                base.listFiles()?.forEach { f ->
                    if (
                        f.exists() &&
                        f.isDirectory &&
                        f.canRead() &&
                        (primary == null || f.absolutePath != primary.absolutePath) &&
                        !f.absolutePath.contains("emulated")
                    ) {
                        addRoot(roots, "External storage", f)
                    }
                }
            }
        }
        return roots
    }


    private fun getUnifiedRoots(): List<Map<String, Any?>> =
        getNativeRoots() + getSafRoots()

    // ─────────────────────────────────────────────
    // Filters
    // ─────────────────────────────────────────────

    private fun matchesFilters(
        size: Long,
        modified: Long,
        name: String,
        mime: String?,
        filters: Map<String, Any>?
    ): Boolean {
        if (filters == null) return true

        val minSize = (filters["minSize"] as? Number)?.toLong()
        val maxSize = (filters["maxSize"] as? Number)?.toLong()
        val after = (filters["modifiedAfter"] as? Number)?.toLong()
        val before = (filters["modifiedBefore"] as? Number)?.toLong()
        val exts = filters["extensions"] as? List<*>
        val mimes = filters["mimeTypes"] as? List<*>

        if (minSize != null && size < minSize) return false
        if (maxSize != null && size > maxSize) return false
        if (after != null && modified < after) return false
        if (before != null && modified > before) return false

        if (!exts.isNullOrEmpty()) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext !in exts.map { it.toString().lowercase(Locale.US) }) return false
        }

        if (mimes != null && mime != null) {
            val ok = mimes.any {
                val m = it.toString()
                m == mime || (m.endsWith("/*") && mime.startsWith(m.dropLast(1)))
            }
            if (!ok) return false
        }
        return true
    }

    // ─────────────────────────────────────────────
    // Directory listing + traversal
    // ─────────────────────────────────────────────

    private fun listNativeDirectory(
        dir: File,
        filters: Map<String, Any>?
    ): List<Map<String, Any?>> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.mapNotNull { f ->
            val size = if (f.isFile) f.length() else 0L
            val modified = f.lastModified()
            val mime = if (f.isFile) {
                context.contentResolver.getType(Uri.fromFile(f))
                    ?: android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(
                            f.extension.lowercase(Locale.US)
                        )
            } else null


            if (!matchesFilters(size, modified, f.name, mime, filters)) return@mapNotNull null

            mapOf(
                "name" to f.name,
                "path" to f.absolutePath,
                "uri" to null,
                "isDirectory" to f.isDirectory,
                "size" to size,
                "lastModified" to modified,
                "mime" to mime,
                "storageType" to "native"
            )
        } ?: emptyList()
    }

    private fun traverseNative(
        file: File,
        depth: Int,
        maxDepth: Int,
        filters: Map<String, Any>?,
        out: MutableList<Map<String, Any?>>
    ) {
        if (!file.exists() || depth > maxDepth) return

        val size = if (file.isFile) file.length() else 0L
        val modified = file.lastModified()
        val mime = if (file.isFile) {
            context.contentResolver.getType(Uri.fromFile(file))
                ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(
                        file.extension.lowercase(Locale.US)
                    )
        } else null


        if (matchesFilters(size, modified, file.name, mime, filters)) {
            out.add(
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "uri" to null,
                    "isDirectory" to file.isDirectory,
                    "size" to size,
                    "lastModified" to modified,
                    "mime" to mime,
                    "storageType" to "native"
                )
            )
        }

        if (file.isDirectory) {
            file.listFiles()?.forEach {
                traverseNative(it, depth + 1, maxDepth, filters, out)
            }
        }
    }

    private fun listSafDirectory(
        uri: Uri,
        filters: Map<String, Any>?
    ): List<Map<String, Any?>> {
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()

        return root.listFiles().mapNotNull { doc ->
            val size = doc.length()
            val modified = doc.lastModified()
            val mime = doc.type

            if (!matchesFilters(size, modified, doc.name ?: "", mime, filters))
                return@mapNotNull null

            mapOf(
                "name" to (doc.name ?: ""),
                "path" to null,
                "uri" to doc.uri.toString(),
                "isDirectory" to doc.isDirectory,
                "size" to size,
                "lastModified" to modified,
                "mime" to mime,
                "storageType" to "saf"
            )
        }
    }

    private fun traverseSaf(
        doc: DocumentFile?,
        depth: Int,
        maxDepth: Int,
        filters: Map<String, Any>?,
        out: MutableList<Map<String, Any?>>
    ) {
        if (doc == null || depth > maxDepth) return

        val size = doc.length()
        val modified = doc.lastModified()
        val mime = doc.type

        if (matchesFilters(size, modified, doc.name ?: "", mime, filters)) {
            out.add(
                mapOf(
                    "name" to (doc.name ?: ""),
                    "path" to null,
                    "uri" to doc.uri.toString(),
                    "isDirectory" to doc.isDirectory,
                    "size" to size,
                    "lastModified" to modified,
                    "mime" to mime,
                    "storageType" to "saf"
                )
            )
        }

        if (doc.isDirectory) {
            doc.listFiles().forEach {
                traverseSaf(it, depth + 1, maxDepth, filters, out)
            }
        }
    }

    // ─────────────────────────────────────────────
    // SAF helpers
    // ─────────────────────────────────────────────

    private fun openSafPicker() {
        activity?.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            SAF_REQUEST_CODE
        )
    }

    private fun persistSafPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Android 7–9 OEM SAF often rejects write persistence
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }catch (e: Exception) {
                Log.e("Storax", "persistSafPermission", e)
            }
        }
    }


    private fun getSafRoots(): List<Map<String, Any?>> {
        val roots = mutableListOf<Map<String, Any?>>()

        context.contentResolver.persistedUriPermissions.forEach { perm ->
            val uri = perm.uri

            // 🚨 Only tree URIs are valid SAF roots
            if (!DocumentsContract.isTreeUri(uri)) return@forEach

            val doc = DocumentFile.fromTreeUri(context, uri) ?: return@forEach

            roots.add(
                mapOf(
                    "type" to "saf",
                    "name" to (doc.name ?: "SAF Folder"),
                    "uri" to uri.toString(),
                    "writable" to perm.isWritePermission
                )
            )
        }

        return roots
    }


    // ─────────────────────────────────────────────
    // Permissions + diagnostics
    // ─────────────────────────────────────────────

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // legacy model, but still path-based
        }


    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        activity?.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${activity?.packageName}".toUri()
            )
        )
    }

    private fun detectOEM(): Map<String, String> =
        mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT.toString()
        )

    private fun permissionHealthCheck(): Map<String, Any> =
        mapOf(
            "allFilesAccess" to hasAllFilesAccess(),
            "sdk" to Build.VERSION.SDK_INT,
            "oem" to Build.MANUFACTURER
        )

    // ─────────────────────────────────────────────
    // USB listener
    // ────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            Log.d("Storax_USB", "USB intent: ${intent.action}")
            when (intent.action) {

                // Filesystem-based USB (some devices)
                Intent.ACTION_MEDIA_MOUNTED ->
                    channel.invokeMethod("onUsbAttached", null)

                Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_UNMOUNTED ->
                    channel.invokeMethod("onUsbDetached", null)

                // Device-based USB (MOST devices)
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    channel.invokeMethod("onUsbAttached", null)

                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    channel.invokeMethod("onUsbDetached", null)
            }
        }
    }


    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addDataScheme("file")
        }
        context.registerReceiver(usbReceiver, filter)
    }

    private fun unregisterUsbReceiver() {
        try { context.unregisterReceiver(usbReceiver) } catch (e: Exception) {
            Log.e("Storax", "unregisterUsbReceiver", e)
        }
    }

//    Function for Opening File

    private fun clearPending() {
        pendingResult = null
        pendingMime = null
    }

    private fun finishCancelled() {
        pendingResult?.error("CANCELLED", "User cancelled picker", null)
        clearPending()
    }


    // ---------------- Core ----------------
    private fun isDebug(): Boolean {
        return (context.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun openUri(
        uri: Uri,
        mime: String,
        result: MethodChannel.Result
    ) {
        val finalMime =
            if (mime.isNotBlank() && mime != "*/*")
                mime
            else
                context.contentResolver.getType(uri) ?: "*/*"

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, finalMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Optional: allow new task if activity is null
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Attach ClipData so permission is propagated reliably to chooser/targets
            clipData = ClipData.newRawUri("file", uri)
        }

        val chooser = Intent.createChooser(viewIntent, "Open with")

        try {
            // Pre-grant URI permission to all apps that can handle the intent
            val resInfoList = context.packageManager
                .queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)

            for (info in resInfoList) {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            // Start chooser from the Activity if available, otherwise from context
            if (activity != null) {
                activity!!.startActivity(chooser)
            } else {
                context.startActivity(chooser)
            }

            result.success(mapOf("ok" to true, "uri" to uri.toString()))
        } catch (e: ActivityNotFoundException) {
            Log.e("NO_APP", "No app found to open file", e)
            result.error("NO_APP", "No app found to open file", null)
        } catch (e: Exception) {
            Log.e("Failed", "Failed to open file", e)
            result.error("FAILED", "Failed to open file", null)
        }
    }

    // ---------------- SAF ----------------

    private fun launchSaf(mime: String, result: MethodChannel.Result) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                )
            } else {
                type = "*/*"
            }

        }

        pendingResult = result
        pendingMime = mime
        activity?.startActivityForResult(intent, REQ_SAF_OPEN)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {

        // ───────────────── SAF FOLDER PICKER ─────────────────
        if (requestCode == SAF_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                persistSafPermission(uri)
                channel.invokeMethod("onSafPicked", uri.toString())
            }
            return true
        }

        // ───────────────── SAF FILE PICKER (openFile) ─────────────────
        if (requestCode == REQ_SAF_OPEN) {
            val result = pendingResult ?: return true

            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                finishCancelled()
                return true
            }

            val uri = data.data!!

            val grantFlags =
                data.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            try {
                when (grantFlags) {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION -> {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION -> {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION) -> {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    else -> {
                        // No persistable permissions granted
                    }
                }
            } catch (_: SecurityException) {
                // Old Android / OEM SAF bug – ignore
            }

            val mime = pendingMime ?: context.contentResolver.getType(uri) ?: "*/*"

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(viewIntent, "Open with")

            try {
                val resInfoList = context.packageManager
                    .queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)

                for (info in resInfoList) {
                    context.grantUriPermission(
                        info.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                activity?.startActivity(chooser)
                result.success(
                    mapOf(
                        "ok" to true,
                        "uri" to uri.toString()
                    )
                )
            } catch (e: ActivityNotFoundException) {
                result.error("NO_APP", "No app found to open file", null)
            } catch (e: Exception) {
                result.error("FAILED", "Failed to open file", null)
            } finally {
                clearPending()
            }

            return true
        }

        return false
    }

    private fun createNativeFolder(parentPath: String, name: String) {
        val parent = File(parentPath)
        if (!parent.exists() || !parent.isDirectory) {
            throw IllegalStateException("Invalid parent directory")
        }

        val folder = File(parent, name)
        if (folder.exists()) {
            throw IllegalStateException("Folder already exists")
        }

        if (!folder.mkdirs()) {
            throw IllegalStateException("Failed to create folder")
        }
    }

    private fun createNativeFile(parentPath: String, name: String) {
        val parent = File(parentPath)
        if (!parent.exists() || !parent.isDirectory) {
            throw IllegalStateException("Invalid parent directory")
        }

        val file = File(parent, name)
        if (file.exists()) {
            throw IllegalStateException("File already exists")
        }

        file.parentFile?.mkdirs()
        if (!file.createNewFile()) {
            throw IllegalStateException("Failed to create file")
        }
    }

    private fun createSafFolder(parentUri: Uri, name: String) {
        val parent = DocumentFile.fromTreeUri(context, parentUri)
            ?: throw IllegalStateException("Invalid SAF parent")

        if (parent.findFile(name) != null) {
            throw IllegalStateException("Folder already exists")
        }

        parent.createDirectory(name)
            ?: throw IllegalStateException("Failed to create SAF folder")
    }

    private fun createSafFile(
        parentUri: Uri,
        name: String,
        mime: String?
    ) {
        val parent = DocumentFile.fromTreeUri(context, parentUri)
            ?: throw IllegalStateException("Invalid SAF parent")

        if (parent.findFile(name) != null) {
            throw IllegalStateException("File already exists")
        }

        // If MIME not provided, infer from extension
        val finalMime = mime ?: run {
            val ext = name.substringAfterLast('.', "")
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext.lowercase())
                ?: "application/octet-stream"
        }

        parent.createFile(finalMime, name)
            ?: throw IllegalStateException("Failed to create SAF file")
    }


    private fun copyNativeFile(
        src: File,
        dst: File,
        jobId: String
    ) {
        val total = src.length()
        var copied = 0L
        var lastEmitted = 0L
        val buffer = ByteArray(64 * 1024)

        src.inputStream().use { input ->
            val finalDst =
                if (dst.isDirectory)
                    File(dst, src.name)
                else
                    dst

            finalDst.parentFile?.mkdirs()
            finalDst.outputStream().use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copied += read

                    if (copied - lastEmitted >= 512 * 1024 || copied == total) {
                        emitProgress(
                            mapOf(
                                "jobId" to jobId,
                                "type" to 0, // copy
                                "source" to src.absolutePath,
                                "destination" to finalDst.absolutePath,
                                "processed" to copied,
                                "total" to total
                            )
                        )
                        lastEmitted = copied
                    }
                }
            }
        }
        emitProgress(mapOf(
            "jobId" to jobId,
            "processed" to total,
            "total" to total,
            "done" to true
        ))
    }


    private fun newJobId(): String =
        System.currentTimeMillis().toString() + "_" + (Math.random() * 100000).toInt()

    private fun copySafFile(
        srcUri: Uri,
        dstDir: DocumentFile,
        name: String,
        jobId: String
    ) {
        val resolver = context.contentResolver

        val srcDoc = DocumentFile.fromSingleUri(context, srcUri)
            ?: throw IllegalStateException("Invalid source SAF file")

        val fileName = srcDoc.name ?: name
        val mimeType = srcDoc.type ?: "*/*"

        val dstDoc = dstDir.createFile(mimeType, fileName)
            ?: throw IllegalStateException("Failed to create destination SAF file")

        val total = srcDoc.length()
        var copied = 0L
        var lastEmitted = 0L
        val buffer = ByteArray(64 * 1024)

        resolver.openInputStream(srcDoc.uri)?.use { input ->
            resolver.openOutputStream(dstDoc.uri)?.use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copied += read

                    // 🔁 Throttled progress update (every ~512 KB)
                    if (copied - lastEmitted >= 512 * 1024 || copied == total) {
                        emitProgress(mapOf(
                            "jobId" to jobId,
                            "type" to 0, // copy
                            "source" to srcDoc.uri.toString(),
                            "destination" to dstDoc.uri.toString(),
                            "processed" to copied,
                            "total" to total
                        ))
                        lastEmitted = copied
                    }
                }
            }
        } ?: throw IllegalStateException("Failed to open SAF input stream")
        emitProgress(mapOf(
            "jobId" to jobId,
            "processed" to total,
            "total" to total,
            "done" to true
        ))
    }


    private fun renameNative(path: String, newName: String) {
        val src = File(path)
        if (!src.exists()) {
            throw IllegalStateException("Source does not exist")
        }

        val dst = File(src.parentFile, newName)

        if (dst.exists()) {
            throw IllegalStateException("Target name already exists")
        }

        val ok = src.renameTo(dst)
        if (!ok) {
            throw IllegalStateException("Native rename failed")
        }
    }

    private fun renameSaf(uri: Uri, newName: String): Boolean {
        val resolver = context.contentResolver

        val doc = DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
            ?: return false

        return try {
            DocumentsContract.renameDocument(
                resolver,
                doc.uri,
                newName
            ) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun renameSafWithFallback(
        uri: Uri,
        newName: String
    ) {
        // 1️⃣ Try native SAF rename
        if (renameSaf(uri, newName)) return

        // 2️⃣ Fallback ONLY for files
        val src = DocumentFile.fromSingleUri(context, uri)
            ?: throw IllegalStateException("Invalid SAF source")

        if (src.isDirectory) {
            throw IllegalStateException("SAF directory rename fallback not supported")
        }

        // 3️⃣ Parent via SAF (NOT rootUri)
        val parent = src.parentFile
            ?: throw IllegalStateException("SAF parent not found")

        // 4️⃣ Create sibling with new name
        val dst = parent.createFile(
            src.type ?: "*/*",
            newName
        ) ?: throw IllegalStateException("Fallback create failed")

        // 5️⃣ Copy + delete
        copySafStreams(src, dst)
        deleteSafRecursive(src)
    }


    private fun deleteNative(path: String) {
        val file = File(path)
        if (!file.exists()) return

        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }


    private fun deleteSaf(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
            ?: return

        deleteSafRecursive(doc)
    }

    private fun deleteSafRecursive(doc: DocumentFile) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { child ->
                deleteSafRecursive(child)
            }
        }
        try {
            doc.delete()
        } catch (e: Exception) {
            Log.e("Storax", "Failed to delete SAF document: ${doc.uri}", e)
        }
    }

    private fun nativeTrashDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), ".storax_trash")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safTrashDir(root: DocumentFile): DocumentFile {
        root.findFile(".storax_trash")?.let { return it }

        return root.createDirectory(".storax_trash")
            ?: throw IllegalStateException("Failed to create SAF trash")
    }

    private fun nativeTrashFile() =
        File(nativeTrashDir(), "native_trash.json")

    private fun safTrashFile() =
        File(context.filesDir, "saf_trash.json")


    private fun readTrashFile(file: File): List<TrashEntry> {
        if (!file.exists()) return emptyList()

        val arr = org.json.JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull { index ->
            try {
                val obj = arr.getJSONObject(index)
                trashEntryFromMap(
                    mapOf(
                        "id" to obj.getString("id"),
                        "name" to obj.getString("name"),
                        "isSaf" to obj.getBoolean("isSaf"),
                        "trashedAt" to obj.getLong("trashedAt"),
                        "originalPath" to obj.optString("originalPath").takeIf { it.isNotEmpty() },
                        "trashedPath" to obj.optString("trashedPath").takeIf { it.isNotEmpty() },
                        "originalUri" to obj.optString("originalUri").takeIf { it.isNotEmpty() },
                        "trashedUri" to obj.optString("trashedUri").takeIf { it.isNotEmpty() },
                        "safRootUri" to obj.optString("safRootUri").takeIf { it.isNotEmpty() }
                    )
                )
            } catch (e: Exception) {
                Log.e("Storax", "Failed to parse trash entry", e)
                null
            }
        }
    }

    @Synchronized
    private fun writeTrashFile(file: File, entry: TrashEntry) {
        val list = readTrashFile(file).toMutableList()
        list.add(entry)

        file.writeText(
            org.json.JSONArray(list.map { it.toMap() }).toString()
        )
    }

    @Synchronized
    private fun removeTrashEntry(file: File, id: String) {
        if (!file.exists()) return

        val list = readTrashFile(file)
            .filterNot { it.id == id }

        file.writeText(
            org.json.JSONArray(list.map { it.toMap() }).toString()
        )
    }


    private fun moveNativeToTrash(path: String) {
        val src = File(path)
        if (!src.exists()) return

        val trashDir = nativeTrashDir()
        val trashed = File(
            trashDir,
            "${System.currentTimeMillis()}_${src.hashCode()}_${src.name}"
        )

        if (!src.renameTo(trashed)) {
            src.copyRecursively(trashed, overwrite = true)
            src.deleteRecursively()
        }

        writeNativeTrashEntry(
            TrashEntry(
                id = System.currentTimeMillis().toString(),
                name = src.name,
                isSaf = false,
                trashedAt = System.currentTimeMillis(),

                // Native
                originalPath = src.absolutePath,
                trashedPath = trashed.absolutePath,

                // SAF (not applicable)
                originalUri = null,
                trashedUri = null,
                safRootUri = null
            )

        )
    }
    private fun moveSafToTrash(srcUri: Uri, rootUri: Uri) {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("Invalid SAF root")

        val src = DocumentFile.fromSingleUri(context, srcUri)
            ?: throw IllegalStateException("Invalid source")

        val trash = safTrashDir(root)

        val dst = trash.createFile(src.type ?: "*/*", src.name!!)
            ?: throw IllegalStateException("Trash create failed")

        copySafStreams(src, dst)
        deleteSafRecursive(src)

        writeSafTrashEntry(
            TrashEntry(
                id = System.currentTimeMillis().toString(),
                name = src.name!!,
                isSaf = true,
                trashedAt = System.currentTimeMillis(),

                // SAF
                originalUri = src.uri.toString(),
                trashedUri = dst.uri.toString(),
                safRootUri = root.uri.toString()
            )

        )
    }
    private fun restoreNative(entry: TrashEntry) {
        val src = File(entry.trashedPath!!)
        val dst = File(entry.originalPath!!)
        dst.parentFile?.mkdirs()

        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }

        removeNativeTrashEntry(entry.id)
    }

    private fun emptySafTrashMetadata() {
        safTrashFile().delete()
    }


    private fun restoreSaf(entry: TrashEntry) {
        val trashedUri = entry.trashedUri
            ?: throw IllegalStateException("Missing trashedUri for SAF restore")

        val src = DocumentFile.fromSingleUri(context, trashedUri.toUri())
            ?: return

        val root = findSafRootFromOriginal(entry.originalUri!!)
        val dstDir = resolveSafParent(root, entry.originalUri)

        val dst = dstDir.createFile(src.type ?: "*/*", entry.name)
            ?: return

        copySafStreams(src, dst)
        deleteSafRecursive(src)
        removeSafTrashEntry(entry.id)
    }


    private fun copySafStreams(src: DocumentFile, dst: DocumentFile) {
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            context.contentResolver.openOutputStream(dst.uri)?.use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun findSafRootFromOriginal(originalUri: String): Uri {
        val uri = originalUri.toUri()
        context.contentResolver.persistedUriPermissions.forEach {
            if (uri.toString().startsWith(it.uri.toString())) {
                return it.uri
            }
        }
        throw IllegalStateException("SAF root not found")
    }

    private fun resolveSafParent(rootUri: Uri, originalUri: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("Invalid SAF root")

        val src = originalUri.toUri()
        val docId = DocumentsContract.getDocumentId(src)
        val path = docId.substringAfter(":")

        var current = root
        path.split("/").dropLast(1).forEach { segment ->
            current = current.findFile(segment)
                ?: current.createDirectory(segment)
                        ?: throw IllegalStateException("Failed to recreate SAF folder")
        }
        return current
    }

    private fun writeNativeTrashEntry(entry: TrashEntry) {
        writeTrashFile(nativeTrashFile(), entry)
    }

    private fun writeSafTrashEntry(entry: TrashEntry) {
        writeTrashFile(safTrashFile(), entry)
    }

    private fun readNativeTrashEntries(): List<TrashEntry> =
        readTrashFile(nativeTrashFile())

    private fun readSafTrashEntries(): List<TrashEntry> =
        readTrashFile(safTrashFile())

    private fun removeNativeTrashEntry(id: String) {
        removeTrashEntry(nativeTrashFile(), id)
    }

    private fun removeSafTrashEntry(id: String) {
        removeTrashEntry(safTrashFile(), id)
    }

    private fun emptyNativeTrash() {
        nativeTrashFile().delete()
    }

    private fun emptySafTrash(rootUri: Uri) {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return
        val trash = root.findFile(".storax_trash") ?: return

        trash.listFiles().forEach {
            deleteSafRecursive(it)
        }
    }

    private fun emitProgress(data: Map<String, Any?>) {
        mainHandler.post {
            channel.invokeMethod("onTransferProgress", data)
        }
    }


    private fun handleGifThumbnail(call: MethodCall, result: MethodChannel.Result) {
        val videoPath = call.argument<String>("videoPath")
        val width = call.argument<Int>("width")
        val height = call.argument<Int>("height")
        val frameCount = call.argument<Int>("frameCount") ?: 10

        coroutineScope.launch {
            val frames = withContext(Dispatchers.IO) {
                generateFrameSequence(videoPath!!, width, height, frameCount)
            }
            withContext(Dispatchers.Main) { 
                result.success(frames) // This returns a List<ByteArray> to Flutter
            }
        }
    }

    private fun handleUniqueFrameSequence(call: MethodCall, result: MethodChannel.Result) {
        val videoPath = call.argument<String>("videoPath")
        val width = call.argument<Int>("width")
        val height = call.argument<Int>("height")

        coroutineScope.launch {
            val frames = withContext(Dispatchers.IO) {
                generateUniqueFrameSequence(videoPath!!, width, height)
            }
            withContext(Dispatchers.Main) {
                result.success(frames) // This returns a List<ByteArray> to Flutter
            }
        }
    }


    private fun generateUniqueFrameSequence(videoPath: String, width: Int?, height: Int?): List<ByteArray>? {
        val retriever = MediaMetadataRetriever()
        val frameList = mutableListOf<ByteArray>()

        try {
            setDataSource(retriever, videoPath)

            // 1. Get total duration in Milliseconds
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L

            // 2. Divide by 10 to get segments. We extract from the middle of each segment
            // to avoid potential black frames at the very start/end.
            val intervalUs = (durationMs * 1000) / 10

            for (i in 0 until 10) {
                val timeUs = i * intervalUs

                // 3. CRITICAL: Use OPTION_CLOSEST instead of OPTION_CLOSEST_SYNC
                // This ensures uniqueness by decoding delta frames if necessary.
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                if (bitmap != null) {
                    val resized = resizeBitmap(bitmap, width, height)
                    val stream = java.io.ByteArrayOutputStream()

                    // Use 70-80% quality to keep memory footprint low for 50 videos
                    resized.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                    frameList.add(stream.toByteArray())

                    // 4. Memory Hygiene: Recycle the heavy raw bitmap immediately
                    bitmap.recycle()
                }
            }
            return frameList
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            retriever.release()
        }
    }

    private fun generateFrameSequence(videoPath: String, width: Int?, height: Int?, frameCount: Int): List<ByteArray>? {
        val retriever = MediaMetadataRetriever()
        val frameList = mutableListOf<ByteArray>()
        try {
            setDataSource(retriever, videoPath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val interval = (duration / frameCount) * 1000 // In microseconds

            for (i in 0 until frameCount) {
                val time = i * interval
                var bitmap = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    bitmap = resizeBitmap(bitmap, width, height)
                    val stream = java.io.ByteArrayOutputStream()
                    // Use WEBP for better compression/speed or JPEG
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    frameList.add(stream.toByteArray())
                }
            }
            return frameList
        } catch (e: Exception) {
            return null
        } finally {
            retriever.release()
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, width: Int?, height: Int?): Bitmap {
        // If no width or height is provided, return the original bitmap
        if (width == null && height == null) return bitmap

        val srcWidth = bitmap.width
        val srcHeight = bitmap.height

        // Calculate the target width and height while maintaining aspect ratio
        val targetWidth: Int
        val targetHeight: Int

        when {
            width != null && height != null -> {
                targetWidth = width
                targetHeight = height
            }
            width != null -> {
                targetWidth = width
                targetHeight = (width * srcHeight) / srcWidth
            }
            height != null -> {
                targetHeight = height
                targetWidth = (height * srcWidth) / srcHeight
            }
            else -> return bitmap
        }

        // createScaledBitmap handles the actual resizing.
        // The 'filter = true' parameter uses bilinear filtering for smoother edges.
        return bitmap.scale(targetWidth, targetHeight)
    }
    private fun setDataSource(retriever: MediaMetadataRetriever, videoPath: String) {
        try {
            when {
                videoPath.startsWith("content://") -> {
                    val uri = videoPath.toUri()
                    retriever.setDataSource(context, uri)
                }
                else -> {
                    // Local file path
                    retriever.setDataSource(videoPath)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

}
