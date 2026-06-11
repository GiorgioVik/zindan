package net.typeblog.shelter.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApplication
import net.typeblog.shelter.util.CrossProfileDocumentsProvider
import net.typeblog.shelter.util.Utility
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable

class FileShuttleService : Service() {
    private val suicideTask = Runnable { suicide() }
    private val handler = Handler(Looper.getMainLooper())

    private val stub = object : IFileShuttleService.Stub() {
        override fun ping() {
            resetSuicideTask()
        }

        override fun loadFiles(path: String): List<Map<String, Serializable>> {
            resetSuicideTask()
            val ret = ArrayList<Map<String, Serializable>>()
            val f = File(resolvePath(path))
            f.listFiles()?.forEach { child ->
                ret.add(loadFileMeta(child.path))
            }
            return ret
        }

        override fun loadFileMeta(path: String): Map<String, Serializable> {
            resetSuicideTask()
            val f = File(resolvePath(path))
            val map = HashMap<String, Serializable>()
            map[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = f.absolutePath
            if (f == Environment.getExternalStorageDirectory()) {
                map[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = getString(R.string.app_name)
            } else {
                map[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = f.name
            }
            map[DocumentsContract.Document.COLUMN_SIZE] = f.length()
            map[DocumentsContract.Document.COLUMN_LAST_MODIFIED] = f.lastModified()

            if (f.isDirectory) {
                map[DocumentsContract.Document.COLUMN_MIME_TYPE] =
                    DocumentsContract.Document.MIME_TYPE_DIR
                map[DocumentsContract.Document.COLUMN_FLAGS] =
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            } else {
                var mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                }
                if (mime == null) {
                    mime = "application/unknown"
                }
                map[DocumentsContract.Document.COLUMN_MIME_TYPE] = mime
                map[DocumentsContract.Document.COLUMN_FLAGS] = flags
            }
            return map
        }

        override fun openFile(path: String, mode: String): ParcelFileDescriptor? {
            resetSuicideTask()
            val f = File(resolvePath(path))
            val numericMode = ParcelFileDescriptor.parseMode(mode)

            return try {
                if (numericMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
                    ParcelFileDescriptor.open(f, numericMode, handler) {
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                        notifyMediaScannerIfNecessary(f, mime)
                    }
                } else {
                    ParcelFileDescriptor.open(f, numericMode)
                }
            } catch (_: IOException) {
                null
            }
        }

        override fun openThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
            resetSuicideTask()
            val fullPath = resolvePath(path)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(Utility.getFileExtension(fullPath))
                ?: return null
            return when {
                mime.startsWith("image/") -> loadImageThumbnail(fullPath, sizeHint)
                mime.startsWith("video/") -> loadVideoThumbnail(fullPath)
                else -> null
            }
        }

        override fun createFile(path: String, mimeType: String, displayName: String): String? {
            resetSuicideTask()
            var fullPath = "$path/$displayName"
            val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
            val shouldAppendExtension = mimeType.isNotEmpty() && !isDirectory &&
                mimeType != "application/octet-stream"

            if (shouldAppendExtension) {
                val extensionPart = "." +
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (!fullPath.endsWith(extensionPart)) {
                    fullPath += extensionPart
                }
            }

            val f = File(resolvePath(fullPath))
            return try {
                if ((isDirectory && !f.mkdir()) || (!isDirectory && !f.createNewFile())) {
                    null
                } else {
                    notifyMediaScannerIfNecessary(f, mimeType)
                    f.absolutePath
                }
            } catch (_: IOException) {
                null
            }
        }

        override fun deleteFile(path: String): String {
            resetSuicideTask()
            val f = File(resolvePath(path))
            f.delete()
            return f.parentFile!!.absolutePath
        }

        override fun isChildOf(parent: String, child: String): Boolean {
            val parentFile = File(resolvePath(parent))
            val childFile = File(resolvePath(child))
            var parentPath = parentFile.absolutePath
            if (parentPath[parentPath.length - 1] != '/') {
                parentPath += "/"
            }
            return parentFile.exists() && parentFile.isDirectory &&
                childFile.exists() &&
                childFile.absolutePath.startsWith(parentPath)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        resetSuicideTask()
        return stub
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("FileShuttleService", "being destroyed")
    }

    private fun resolvePath(path: String): String {
        return if (path.startsWith(CrossProfileDocumentsProvider.DUMMY_ROOT)) {
            path.replaceFirst(
                CrossProfileDocumentsProvider.DUMMY_ROOT,
                Environment.getExternalStorageDirectory().absolutePath,
            )
        } else {
            path
        }
    }

    private fun resetSuicideTask() {
        handler.removeCallbacks(suicideTask)
        handler.postDelayed(suicideTask, TIMEOUT)
    }

    private fun suicide() {
        handler.removeCallbacks(suicideTask)
        (application as ShelterApplication).unbindFileShuttleService()
        stopSelf()
    }

    private fun loadImageThumbnail(fullPath: String, sizeHint: Point): ParcelFileDescriptor? {
        val id = Utility.getMediaStoreId(this, fullPath)
        if (id == -1) {
            return loadBitmapThumbnail(fullPath, sizeHint)
        }
        var result = MediaStore.Images.Thumbnails.queryMiniThumbnail(
            contentResolver,
            id.toLong(),
            MediaStore.Images.Thumbnails.MINI_KIND,
            null,
        )
        if (result.count == 0) {
            MediaStore.Images.Thumbnails.getThumbnail(
                contentResolver,
                id.toLong(),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
            result = MediaStore.Images.Thumbnails.queryMiniThumbnail(
                contentResolver,
                id.toLong(),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
        if (result.count == 0) {
            return loadBitmapThumbnail(fullPath, sizeHint)
        }
        result.moveToFirst()
        return try {
            val index = result.getColumnIndex(MediaStore.Images.Thumbnails.DATA)
            contentResolver.openFileDescriptor(
                Uri.fromFile(File(result.getString(index))),
                "r",
            )
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun loadVideoThumbnail(fullPath: String): ParcelFileDescriptor? {
        val bmp = ThumbnailUtils.createVideoThumbnail(
            fullPath,
            MediaStore.Video.Thumbnails.MINI_KIND,
        )
        return bitmapToFd(bmp)
    }

    private fun loadBitmapThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
        val bmp = Utility.decodeSampledBitmap(path, sizeHint.x, sizeHint.y) ?: return null
        return bitmapToFd(bmp)
    }

    private fun bitmapToFd(bmp: Bitmap?): ParcelFileDescriptor? {
        if (bmp == null) return null
        val pair = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            return null
        }

        Thread {
            try {
                FileOutputStream(pair[1].fileDescriptor).use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }
            } catch (_: IOException) {
            }
            bmp.recycle()
        }.start()

        return pair[0]
    }

    private fun notifyMediaScannerIfNecessary(f: File, mimeType: String?) {
        if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(f)
            sendBroadcast(intent)
        }
    }

    companion object {
        const val TIMEOUT = 10000L
    }
}
