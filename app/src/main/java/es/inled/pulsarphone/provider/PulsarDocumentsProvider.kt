package es.inled.pulsarphone.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.FsItem
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PulsarDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!PulsarPhoneApp.instance.isConfigured()) {
            return result
        }

        val config = PulsarPhoneApp.instance.getConfig()

        // Root 1: Pulsar OS PC
        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, "pulsar_root_home")
        row.add(Root.COLUMN_DOCUMENT_ID, "root:/")
        row.add(Root.COLUMN_TITLE, "${config.pcAlias} (Pulsar OS)")
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher)

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)

        if (documentId == "root:/") {
            row.add(Document.COLUMN_DISPLAY_NAME, PulsarPhoneApp.instance.getConfig().pcAlias)
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
            return result
        }

        val targetPath = parsePath(documentId)
        val name = File(targetPath).name
        row.add(Document.COLUMN_DISPLAY_NAME, name)
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE)

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (!PulsarPhoneApp.instance.isConfigured()) return result

        val targetPath = parsePath(parentDocumentId)
        val items: List<FsItem> = runBlocking {
            val res = PulsarApiClient.getFsChildren(targetPath)
            res.getOrDefault(emptyList())
        }

        for (item in items) {
            val row = result.newRow()
            val docId = "doc:${item.path}"
            row.add(Document.COLUMN_DOCUMENT_ID, docId)
            row.add(Document.COLUMN_DISPLAY_NAME, item.name)
            row.add(Document.COLUMN_SIZE, item.size)
            row.add(Document.COLUMN_LAST_MODIFIED, item.modified)

            if (item.isDir) {
                row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID or Document.FLAG_SUPPORTS_DELETE)
            } else {
                row.add(Document.COLUMN_MIME_TYPE, item.mimeType)
                row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
            }
        }

        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val filePath = parsePath(documentId)
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        Thread {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { out ->
                    val inputStream = PulsarApiClient.openFsFileStream(filePath)
                    if (inputStream != null) {
                        inputStream.use { input ->
                            val buf = ByteArray(65536)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) {
                                if (signal?.isCanceled == true) break
                                out.write(buf, 0, len)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Pipe closed
            } finally {
                try {
                    writeSide.close()
                } catch (e: IOException) {
                }
            }
        }.start()

        return readSide
    }

    override fun deleteDocument(documentId: String) {
        val path = parsePath(documentId)
        runBlocking {
            PulsarApiClient.deleteFsPath(path)
        }
    }

    private fun parsePath(documentId: String): String {
        return when {
            documentId == "root:/" -> ""
            documentId.startsWith("doc:") -> documentId.removePrefix("doc:")
            else -> documentId
        }
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }
}
