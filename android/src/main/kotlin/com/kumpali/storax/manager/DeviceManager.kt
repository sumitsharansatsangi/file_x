package com.kumpali.storax.manager

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.kumpali.storax.core.DeviceCapabilities
import com.kumpali.storax.core.StorageRoot
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DeviceManager(
    private val context: Context
) {

    // ================================
    // Caches
    // ================================

    private val safRootPathCache = ConcurrentHashMap<Uri, String>()
    private val safFileCache = ConcurrentHashMap<String, Uri>()

    // ================================
    // ROOT DISCOVERY
    // ================================

    fun getNativeRoots(): List<StorageRoot> {
        val roots = mutableListOf<StorageRoot>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE)
                as android.os.storage.StorageManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm.storageVolumes.forEach { vol ->
                vol.directory?.let { dir ->
                    val stat = StatFs(dir.absolutePath)
                    roots.add(
                        StorageRoot(
                            type = "native",
                            name = vol.getDescription(context),
                            path = dir.absolutePath,
                            uri = null,
                            totalBytes = stat.totalBytes,
                            freeBytes = stat.availableBytes,
                            usedBytes = stat.totalBytes - stat.availableBytes,
                            writable = dir.canWrite()
                        )
                    )
                }
            }
        } else {
            val primary = Environment.getExternalStorageDirectory()
            val stat = StatFs(primary.absolutePath)
            roots.add(
                StorageRoot(
                    type = "native",
                    name = "Internal storage",
                    path = primary.absolutePath,
                    uri = null,
                    totalBytes = stat.totalBytes,
                    freeBytes = stat.availableBytes,
                    usedBytes = stat.totalBytes - stat.availableBytes,
                    writable = primary.canWrite()
                )
            )
        }
        return roots
    }

    fun getSafRoots(): List<StorageRoot> {
        val roots = mutableListOf<StorageRoot>()

        context.contentResolver.persistedUriPermissions.forEach { permission ->
            val uri = permission.uri
            if (!DocumentsContract.isTreeUri(uri)) return@forEach

            val doc = DocumentFile.fromTreeUri(context, uri) ?: return@forEach

            roots.add(
                StorageRoot(
                    type = "saf",
                    name = doc.name ?: "SAF Folder",
                    path = null,
                    uri = uri.toString(),
                    totalBytes = null,
                    freeBytes = null,
                    usedBytes = null,
                    writable = permission.isWritePermission
                )
            )
        }

        return roots
    }

    fun getUnifiedRoots(): List<StorageRoot> =
        getNativeRoots() + getSafRoots()

    // ================================
    // FILE OPENING (Fully Encapsulated)
    // ================================

    fun openFile(
        activity: Activity?,
        path: String?,
        uriStr: String?,
        mime: String?,
        callback: (Result<Map<String, Any?>>) -> Unit
    ) {

        if (path != null && uriStr != null) {
            callback(Result.failure(IllegalArgumentException("Provide either path or uri")))
            return
        }

        try {

            val uri = when {
                path != null -> resolveUriFromPath(path)
                uriStr != null -> uriStr.toUri()
                else -> throw IllegalArgumentException("Missing path or uri")
            }

            val finalMime =
                if (!mime.isNullOrBlank() && mime != "*/*")
                    mime
                else
                    context.contentResolver.getType(uri) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, finalMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            grantUriPermissionToTargets(uri, intent)

            if (activity != null) {
                activity.startActivity(Intent.createChooser(intent, "Open with"))
            } else {
                context.startActivity(Intent.createChooser(intent, "Open with"))
            }

            callback(Result.success(mapOf(
                "ok" to true,
                "uri" to uri.toString()
            )))

        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    private fun resolveUriFromPath(path: String): Uri {

        val file = File(path)
        if (!file.exists())
            throw IllegalArgumentException("File not found")

        // 1️⃣ SAF resolution
        resolveFileInSafTree(file)?.let { return it }

        // 2️⃣ FileProvider fallback
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun grantUriPermissionToTargets(uri: Uri, intent: Intent) {
        val resInfoList = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        for (info in resInfoList) {
            context.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    // ================================
    // SAF TREE RESOLUTION
    // ================================

    private fun resolveSafRootPath(treeUri: Uri): String? {

        safRootPathCache[treeUri]?.let { return it }

        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val name = doc.name ?: return null

        val candidates = listOf(
            File("/storage"),
            Environment.getExternalStorageDirectory()
        )

        for (base in candidates) {
            base.walkTopDown()
                .maxDepth(3)
                .firstOrNull { it.name == name }
                ?.let {
                    safRootPathCache[treeUri] = it.absolutePath
                    return it.absolutePath
                }
        }

        return null
    }

    private fun resolveFileInSafTree(file: File): Uri? {

        val path = file.absolutePath

        safFileCache[path]?.let { return it }

        val trees = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

        for (tree in trees) {
            val rootPath = resolveSafRootPath(tree) ?: continue
            if (!path.startsWith(rootPath)) continue

            val relativePath = path.removePrefix(rootPath)
                .trimStart(File.separatorChar)

            val uri = resolveRelativeDocument(tree, relativePath)
            if (uri != null) {
                safFileCache[path] = uri
                return uri
            }
        }

        return null
    }

    private fun resolveRelativeDocument(treeUri: Uri, relativePath: String): Uri? {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (relativePath.isEmpty()) return current.uri

        val parts = relativePath.split(File.separatorChar)
        for (segment in parts) {
            current = current.findFile(segment) ?: return null
        }

        return current.uri.takeIf { current.isFile }
    }

    // ================================
    // SAF FOLDER PICKER
    // ================================

    fun openSafFolderPicker(activity: Activity?, requestCode: Int) {
        activity?.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            requestCode
        )
    }

    fun persistSafPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    // ================================
    // PERMISSIONS
    // ================================

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    fun requestAllFilesAccess(activity: Activity?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        activity?.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${activity.packageName}".toUri()
            )
        )
    }

    // ================================
    // USB LISTENER
    // ================================

    private var usbReceiver: BroadcastReceiver? = null

    fun registerUsbListener(
        onAttached: () -> Unit,
        onDetached: () -> Unit
    ) {

        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_MEDIA_MOUNTED,
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached()
                    Intent.ACTION_MEDIA_REMOVED,
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached()
                }
            }
        }

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

    fun unregisterUsbListener() {
        usbReceiver?.let {
            context.unregisterReceiver(it)
            usbReceiver = null
        }
    }

    // ================================
    // DEVICE CAPABILITIES
    // ================================

    fun getDeviceCapabilities(): DeviceCapabilities =
        DeviceCapabilities(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            sdk = Build.VERSION.SDK_INT,
            hasAllFilesAccess = hasAllFilesAccess(),
            supportsScopedStorage =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            usbSupported = true,
            androidVersion = "Android ${Build.VERSION.RELEASE}"
        )
}
