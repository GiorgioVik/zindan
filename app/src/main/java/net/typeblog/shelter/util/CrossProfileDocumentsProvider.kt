package net.typeblog.shelter.util

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import net.typeblog.shelter.R
import net.typeblog.shelter.services.FileShuttleService
import net.typeblog.shelter.services.IFileShuttleService
import net.typeblog.shelter.services.IFileShuttleServiceCallback
import net.typeblog.shelter.ui.DummyActivity
import java.io.Serializable

// A document provider to show files across the profile boundary
// in the system's Documents UI.
// This is an interface to FileShuttleService
class CrossProfileDocumentsProvider : DocumentsProvider() {
    private var service: IFileShuttleService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseServiceTask = Runnable { releaseService() }
    private val lock = Object()

    private fun doBindService() {
        var intent = Intent(DummyActivity.START_FILE_SHUTTLE)
        val extra = Bundle()
        extra.putBinder(
            "callback",
            object : IFileShuttleServiceCallback.Stub() {
                override fun callback(service: IFileShuttleService) {
                    this@CrossProfileDocumentsProvider.service = service
                    synchronized(lock) {
                        lock.notifyAll()
                    }
                }
            }
        )
        intent.putExtra("extra", extra)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            Utility.transferIntentToProfile(context!!, intent)
        } catch (e: IllegalStateException) {
            intent.action = DummyActivity.START_FILE_SHUTTLE_2
            Utility.transferIntentToProfile(context!!, intent)
        }
        context!!.startActivity(intent)

        synchronized(lock) {
            try {
                lock.wait()
            } catch (e: InterruptedException) {
            }
        }
    }

    private fun ensureServiceBound() {
        if (service == null) {
            doBindService()
        } else {
            try {
                service!!.ping()
                resetReleaseService()
            } catch (e: RemoteException) {
                doBindService()
            }
        }
    }

    private fun releaseService() {
        service = null
    }

    private fun resetReleaseService() {
        handler.removeCallbacks(releaseServiceTask)
        handler.postDelayed(releaseServiceTask, FileShuttleService.TIMEOUT / 2)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher_egg)
        row.add(
            DocumentsContract.Root.COLUMN_TITLE,
            if (Utility.isProfileOwner(context!!)) {
                context!!.getString(R.string.fragment_profile_main)
            } else {
                context!!.getString(R.string.fragment_profile_work)
            }
        )
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                    or DocumentsContract.Root.FLAG_LOCAL_ONLY
                    or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor? {
        ensureServiceBound()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val fileInfo: Map<String, Serializable> = try {
            @Suppress("UNCHECKED_CAST")
            service!!.loadFileMeta(documentId) as Map<String, Serializable>
        } catch (e: RemoteException) {
            return null
        }
        includeFile(result, fileInfo)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        ensureServiceBound()
        val files: List<Map<String, Serializable>> = try {
            @Suppress("UNCHECKED_CAST")
            service!!.loadFiles(parentDocumentId) as List<Map<String, Serializable>>
        } catch (e: RemoteException) {
            return null
        }
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        result.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
        )

        for (file in files) {
            includeFile(result, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        ensureServiceBound()
        return try {
            service!!.openFile(documentId, mode)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        ensureServiceBound()
        return try {
            AssetFileDescriptor(
                service!!.openThumbnail(documentId, sizeHint),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH
            )
        } catch (e: RemoteException) {
            null
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        ensureServiceBound()
        return try {
            val ret = service!!.createFile(parentDocumentId, mimeType, displayName)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId),
                null
            )
            ret
        } catch (e: RemoteException) {
            null
        }
    }

    override fun deleteDocument(documentId: String) {
        ensureServiceBound()
        try {
            val parent = service!!.deleteFile(documentId)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parent),
                null
            )
        } catch (e: RemoteException) {
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        ensureServiceBound()
        return try {
            service!!.isChildOf(parentDocumentId, documentId)
        } catch (e: RemoteException) {
            false
        }
    }

    private fun includeFile(cursor: MatrixCursor, fileInfo: Map<String, Serializable>) {
        val row = cursor.newRow()
        for (col in DEFAULT_DOCUMENT_PROJECTION) {
            row.add(col, fileInfo[col])
        }
    }

    companion object {
        const val DUMMY_ROOT = "/shelter_storage_root/"
        private const val AUTHORITY = "net.typeblog.shelter.documents"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
