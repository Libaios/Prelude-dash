package fr.preludedash.card

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Sert la pochette de chaque image publiée. Android Auto (une autre appli) vient la
 * chercher par son adresse content://, d'où le fournisseur exporté.
 */
class CardArtProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "fr.preludedash.cardart"
        private const val SIZE = 512

        fun uriFor(id: Int): Uri = Uri.parse("content://$AUTHORITY/f/$id")
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.toIntOrNull()
        val snapshot = id?.let { CardFrames.get(it) } ?: throw FileNotFoundException("image $id inconnue")
        val bitmap = CardRenderer.render(snapshot, SIZE)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {
            } finally {
                bitmap.recycle()
            }
        }, "card-art").start()
        return pipe[0]
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
