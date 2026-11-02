package com.muastudio.fotufilm.flutter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * Photographs, scans and movies chosen in the system's picker, as files the engine opens by path
 * (`chooseFiles` in lib/model/file_types.dart, over `fotufilm/files`). The picker answers with
 * content URIs; each is copied into the app's cache a buffer at a time, so a camera's RAW or a
 * scan of any size crosses without being held in memory, which file_selector does and runs out of.
 */
class OpenedFiles(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "fotufilm/files")
    private val main = Handler(Looper.getMainLooper())

    init {
        // A rebuild of the activity (the process was kept, the system took the activity back)
        // makes a fresh OpenedFiles; a picker answer still in flight belongs to no Dart code any
        // more, so it answers with nothing instead of never.
        pending?.success(emptyList<String>())
        pending = null
        channel.setMethodCallHandler { call, result ->
            if (call.method != "open") return@setMethodCallHandler result.notImplemented()
            pending?.success(emptyList<String>())
            pending = result
            val types = call.argument<List<String>>("types") ?: listOf("image/*", "video/*")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            activity.startActivityForResult(intent, REQUEST)
        }
        // Copies the last session left behind are dead weight (a camera RAW is tens of MB a
        // piece): a new activity means a new Dart run, which opens nothing of the old one's.
        // Every folder written before this launch goes; nothing this session copies is touched.
        cleanStaleCopies(activity)
    }

    /** The picker's answer; false when the result is another request's. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val result = pending ?: return true
        pending = null
        val uris = buildList {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
                else data.data?.let(::add)
            }
        }
        copying.execute {
            val paths = uris.mapNotNull { uri -> runCatching { copy(uri) }.getOrNull() }
            main.post { result.success(paths) }
        }
        return true
    }

    /** `uri` in a folder of its own under the cache, under its own name (its extension says what
     *  it is to the engine). */
    private fun copy(uri: Uri): String {
        val name = displayName(uri)?.replace('/', '_') ?: "photo"
        val folder = File(activity.cacheDir, "opened/${System.nanoTime()}").apply { mkdirs() }
        val target = File(folder, name)
        activity.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        return target.path
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    companion object {
        private const val REQUEST = 0x0f07

        /**
         * One copying executor for the whole process: an activity rebuilt in place would
         * otherwise leave a thread pool behind per rebuild. A copy already running finishes, so
         * a rebuild mid-copy still answers — into the channel the old Dart side no longer
         * hears, where the answer is dropped harmlessly.
         */
        private val copying = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fotufilm-opened-files").apply { priority = Thread.NORM_PRIORITY - 1 }
        }

        /** A picker answer waiting for the system's, held across activity rebuilds. */
        private var pending: MethodChannel.Result? = null

        /** Folders under the cache's `opened` written before this launch are this launch's to
         *  take; the date says it, for the folder's name (a nanoTime) is no clock. */
        private fun cleanStaleCopies(activity: Activity) {
            val opened = File(activity.cacheDir, "opened")
            val born = System.currentTimeMillis()
            copying.execute {
                for (child in opened.listFiles() ?: return@execute) {
                    if (child.lastModified() < born) child.deleteRecursively()
                }
            }
        }
    }
}
