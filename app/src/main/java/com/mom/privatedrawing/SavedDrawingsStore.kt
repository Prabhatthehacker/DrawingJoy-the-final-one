package com.mom.privatedrawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SavedDrawingsStore {

    private fun projectsDir(context: Context): File {
        val dir = File(context.filesDir, "SavedProjects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Saves a copy of the current drawing under the given name so it can be reopened later. */
    fun saveProject(context: Context, bitmap: Bitmap, name: String): File {
        val safeName = name.trim().ifBlank { "Untitled" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = File(projectsDir(context), "$safeName.png")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(projectsDir(context), "$safeName ($counter).png")
            counter++
        }
        FileOutputStream(candidate).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return candidate
    }

    /** A reasonable default name to prefill the "name this drawing" prompt with. */
    fun suggestedName(): String {
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        return "Drawing - ${sdf.format(Date())}"
    }

    /** Newest saved drawings first. */
    fun listProjects(context: Context): List<File> {
        return projectsDir(context).listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadBitmap(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    fun deleteProject(file: File): Boolean = file.delete()
}
