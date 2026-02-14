package com.kumpali.storax

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.kumpali.storax.core.*
import com.kumpali.storax.manager.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.*

/**
 * StoraxPlugin
 *
 * Flutter bridge layer for the Storax file-management engine.
 *
 * Responsibilities:
 * - Receives MethodChannel calls from Flutter
 * - Delegates Android-specific behavior to DeviceManager
 * - Delegates file operations to StorageManager
 * - Delegates media operations to MediaManager
 * - Manages lifecycle and coroutine scope
 * - Emits reactive events back to Flutter (USB events, undo state, progress)
 *
 * Architectural Principle:
 * -------------------------------------------------
 * This class MUST NOT contain:
 * - Filesystem logic
 * - SAF logic
 * - Intent resolution logic
 * - Storage mutation logic
 *
 * It is strictly a communication + orchestration layer.
 */
private const val SAF_REQUEST_CODE = 9091

class StoraxPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {

    // ─────────────────────────────────────────────
    // Core references
    // ─────────────────────────────────────────────

    /** Flutter communication channel */
    private lateinit var channel: MethodChannel

    /** Application context (safe to retain) */
    private lateinit var context: Context

    /** Current foreground activity (nullable due to lifecycle) */
    private var activity: Activity? = null

    /** Handles Android device-level behavior */
    private lateinit var deviceManager: DeviceManager

    /** Handles all storage logic and transactional operations */
    private lateinit var storageManager: StorageManager

    /** Handles video thumbnail generation */
    private lateinit var mediaManager: MediaManager

    /** Plugin coroutine scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Signals when recovery of journaled operations is complete.
     * All operations await this before executing.
     */
    private val recoveryCompleted = CompletableDeferred<Unit>()

    // ─────────────────────────────────────────────
    // Flutter Engine Lifecycle
    // ─────────────────────────────────────────────

    /**
     * Called when the plugin is attached to the Flutter engine.
     *
     * Initializes:
     * - MethodChannel
     * - Managers
     * - USB broadcast listeners
     * - Power-loss recovery sequence
     */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {

        context = binding.applicationContext

        channel = MethodChannel(binding.binaryMessenger, "storax")
        channel.setMethodCallHandler(this)

        val journalManager = JournalManager(context)
        val mediaIndexer = MediaIndexer(context)

        deviceManager = DeviceManager(context)
        storageManager = StorageManager(context, journalManager, mediaIndexer)
        mediaManager = MediaManager(context)

        // Register USB attach/detach listeners
        deviceManager.registerUsbListener(
            onAttached = { channel.invokeMethod("onUsbAttached", null) },
            onDetached = { channel.invokeMethod("onUsbDetached", null) }
        )

        // Recover pending journal operations
        scope.launch {
            journalManager.recoverPendingOperations {
                BackendDetector.detect(context, it, mediaIndexer)
            }
            recoveryCompleted.complete(Unit)
        }
    }

    /**
     * Called when the plugin is detached from the Flutter engine.
     *
     * Performs cleanup:
     * - Unregisters USB listener
     * - Cancels coroutine scope
     * - Releases MethodChannel
     */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        deviceManager.unregisterUsbListener()
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    // ─────────────────────────────────────────────
    // Activity Lifecycle
    // ─────────────────────────────────────────────

    /**
     * Captures the current Activity for:
     * - SAF folder picker
     * - Permission screens
     * - Intent launching
     */
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() { activity = null }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() { activity = null }

    // ─────────────────────────────────────────────
    // Method Channel Entry Point
    // ─────────────────────────────────────────────

    /**
     * Handles all calls from Flutter.
     *
     * Delegates operations based on method name.
     */
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {

        when (call.method) {

            // ───────── Root discovery APIs ─────────

            "getNativeRoots" ->
                result.success(deviceManager.getNativeRoots().map { it.toMap() })

            "getSafRoots" ->
                result.success(deviceManager.getSafRoots().map { it.toMap() })

            "getAllRoots" ->
                result.success(deviceManager.getUnifiedRoots().map { it.toMap() })

            "getDeviceCapabilities" ->
                result.success(deviceManager.getDeviceCapabilities().toMap())

            // ───────── Permissions & SAF ─────────

            "openSafFolderPicker" -> {
                deviceManager.openSafFolderPicker(activity, SAF_REQUEST_CODE)
                result.success(true)
            }

            "hasAllFilesAccess" ->
                result.success(deviceManager.hasAllFilesAccess())

            "requestAllFilesAccess" -> {
                deviceManager.requestAllFilesAccess(activity)
                result.success(true)
            }

            "openFile" -> {
                deviceManager.openFile(
                    activity,
                    call.argument("path"),
                    call.argument("uri"),
                    call.argument("mime")
                ) { openResult ->
                    openResult
                        .onSuccess { result.success(it) }
                        .onFailure { result.error("OPEN_FAILED", it.message, null) }
                }
            }

            // ───────── Storage operations ─────────

            "listDirectory" ->
                runSuspend(result) {
                    storageManager.list(requireArg(call, "target"))
                }

            "traverseDirectory" ->
                runSuspend(result) {
                    storageManager.traverse(
                        requireArg(call, "target"),
                        call.argument("maxDepth") ?: -1
                    )
                }

            "create" -> runMutation(result) {
                val r = storageManager.create(
                    requireArg(call, "parent"),
                    requireArg(call, "name"),
                    NodeType.fromCode(call.argument<Int>("type") ?: 0),
                    ConflictPolicy.fromCode(call.argument<Int>("conflictPolicy") ?: 0),
                    call.argument("manualRename")
                )
                if (!r.success) throw RuntimeException(r.error)
                mapOf(
                    "success" to true,
                    "finalName" to r.finalName,
                    "path" to r.pathOrUri
                )
            }

            "rename" -> runMutation(result) {
                storageManager.rename(
                    requireArg(call, "source"),
                    requireArg(call, "newName"),
                    ConflictPolicy.fromCode(call.argument<Int>("conflictPolicy") ?: 0),
                    call.argument("manualRename")
                )
            }

            "move" -> runMutation(result) {
                storageManager.move(
                    requireArg(call, "source"),
                    requireArg(call, "destParent"),
                    requireArg(call, "newName"),
                    ConflictPolicy.fromCode(call.argument<Int>("conflictPolicy") ?: 0),
                    call.argument("manualRename")
                )
            }

            "delete" -> runMutation(result) {
                storageManager.delete(requireArg(call, "target"))
            }

            "undo" -> runMutation(result) { storageManager.undo() }
            "redo" -> runMutation(result) { storageManager.redo() }

            "canUndo" -> runSuspend(result) { storageManager.canUndo() }
            "canRedo" -> runSuspend(result) { storageManager.canRedo() }

            else -> result.notImplemented()
        }
    }

    // ─────────────────────────────────────────────
    // SAF Result Handling
    // ─────────────────────────────────────────────

    /**
     * Handles result from SAF folder picker.
     */
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {

        if (requestCode == SAF_REQUEST_CODE &&
            resultCode == Activity.RESULT_OK &&
            data?.data != null
        ) {
            val uri = data.data!!
            deviceManager.persistSafPermission(uri)
            channel.invokeMethod("onSafPicked", uri.toString())
            return true
        }

        return false
    }

    // ─────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────

    /**
     * Executes a suspend block that does NOT mutate undo stack.
     */
    private fun <T> runSuspend(
        result: MethodChannel.Result,
        block: suspend () -> T
    ) {
        scope.launch {
            runCatching {
                recoveryCompleted.await()
                block()
            }
                .onSuccess { result.success(it) }
                .onFailure { result.error("STORAGE_ERROR", it.message, null) }
        }
    }

    /**
     * Executes a suspend block that mutates state.
     * Automatically notifies Flutter of undo stack changes.
     */
    private fun <T> runMutation(
        result: MethodChannel.Result,
        block: suspend () -> T
    ) {
        scope.launch {
            runCatching {
                recoveryCompleted.await()
                block()
            }
                .onSuccess {
                    result.success(it)
                    notifyUndoState()
                }
                .onFailure {
                    result.error("STORAGE_ERROR", it.message, null)
                }
        }
    }

    /**
     * Emits current undo/redo availability state to Flutter.
     */
    private suspend fun notifyUndoState() {
        val canUndo = storageManager.canUndo()
        val canRedo = storageManager.canRedo()
        channel.invokeMethod(
            "onUndoStateChanged",
            mapOf("canUndo" to canUndo, "canRedo" to canRedo)
        )
    }

    /**
     * Safely retrieves a required argument from Flutter call.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> requireArg(call: MethodCall, key: String): T =
        call.argument(key)
            ?: throw IllegalArgumentException("Missing argument: $key")
}
