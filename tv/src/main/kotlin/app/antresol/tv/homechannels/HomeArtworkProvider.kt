package app.antresol.tv.homechannels

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Hands the launcher the cached pictures of the home screen ([HomeArtworkFiles]): read only, no network, and only
 * files whose name is the 64-digit digest of this install, so it opens nothing else of the app.
 */
class HomeArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun file(uri: Uri) =
        HomeArtworkFiles.fileFor(HomeArtworkFiles.dir(requireNotNull(context)), uri.pathSegments.singleOrNull())

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (file(uri) != null) "image/jpeg" else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = file(uri) ?: return null
        val columns = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
