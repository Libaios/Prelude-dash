package fr.preludedash.carte

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import fr.preludedash.shared.CardRenderer
import java.io.FileNotFoundException

/**
 * Sert la pochette de chaque image publiée. Android Auto (une autre appli) vient la
 * chercher par son adresse content://, d'où le fournisseur exporté.
 */
class CardArtProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "fr.preludedash.carte.art"
        private const val TAG = "PreludeCarte"
        private const val SIZE = 512
    }

    override fun onCreate(): Boolean = true

    /**
     * Journal de test : on note si Android Auto demande une taille d'image différente selon
     * qu'il affiche la colonne ou l'écran plein. Si c'est le cas, on pourra dessiner un écran
     * complètement différent pour le plein écran. Lecture : adb logcat -s PreludeCarte
     */
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        @Suppress("DEPRECATION")
        val size: Point? = opts?.getParcelable(ContentResolver.EXTRA_SIZE)
        Log.i(TAG, "image ${uri.lastPathSegment} demandée (typed) taille=$size type=$mimeTypeFilter par=$callingPackage")
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        Log.i(TAG, "image ${uri.lastPathSegment} demandée (openFile) par=$callingPackage")
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
