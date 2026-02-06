package com.kumpali.file_x

import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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


/**
 * Request code used for SAF (Storage Access Framework) folder picker.
 *
 * This must be a compile-time constant so it can be safely used
 * across Activity restarts and configuration changes.
 */
private const val SAF_REQUEST_CODE = 9091
private const val REQ_SAF_OPEN = 9001
/**
 * FilexPlugin
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
class FilexPlugin :
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
    private val safRootPathCache = mutableMapOf<Uri, String>()

    // File path → resolved document URI
    private val safFileCache = mutableMapOf<String, Uri>()

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
        channel = MethodChannel(binding.binaryMessenger, "file_x")
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
            Log.e("FileX", "addRoot", e)
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
                Log.e("FileX", "getNativeRoots", e)
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

        if (exts != null && exts.isNotEmpty()) {
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
                Log.e("FileX", "persistSafPermission", e)
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
            Log.d("FileX_USB", "USB intent: ${intent.action}")
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
            Log.e("FileX", "unregisterUsbReceiver", e)
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

}
