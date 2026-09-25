package com.aegis.eidolon.audit

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class CallRecording(val uri: Uri, val path: String?, val dateAdded: Long)

class CallRecordingStore(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun latest(): CallRecording? {
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED)
        val selection = "${MediaStore.Audio.Media.IS_PENDING} = 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sortOrder)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
            return CallRecording(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build(), path, dateAdded)
        }
        return null
    }
}