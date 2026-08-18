package com.example.roboticsdatacollector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionRecord(
    val sessionId: String,
    val directory: File,
    val status: String,
    val durationSeconds: Double,
    val handsDetectedPercentage: Double,
    val totalBytes: Long,
    val createdAtEpochMs: Long,
    val videoFile: File?,
    val imuFile: File?,
    val metadataFile: File?,
    val experiment: String = "",
    val participantId: String = "",
    val task: String = ""
) {
    val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
    val createdLabel: String
        get() = DATE_FORMAT.format(Date(createdAtEpochMs))

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}

/**
 * Lists, deletes, and prepares share URIs for `session_*` folders on app storage.
 * Sessions are written to [Context.getExternalFilesDir]; internal [Context.filesDir]
 * is also scanned so older/local copies still appear.
 */
class SessionRepository(private val context: Context) {

    fun listSessions(): List<SessionRecord> {
        return sessionRoots()
            .flatMap { root ->
                root.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isDirectory && it.name.startsWith(SESSION_PREFIX) }
                    .toList()
            }
            .distinctBy { it.absolutePath }
            .mapNotNull { parseSession(it) }
            .sortedByDescending { it.createdAtEpochMs }
    }

    fun orphanSessions(): List<SessionRecord> {
        return listSessions().filter {
            it.status == SessionStatus.RECORDING || it.status == SessionStatus.PAUSED
        }
    }

    fun recoverSession(session: SessionRecord): Boolean {
        val metadata = session.metadataFile ?: return false
        return try {
            val json = JSONObject(metadata.readText())
            json.put("status", SessionStatus.INTERRUPTED_SYSTEM)
            metadata.writeText(json.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Recover failed", e)
            false
        }
    }

    fun deleteSession(session: SessionRecord): Boolean {
        return try {
            session.directory.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete ${session.directory}", e)
            false
        }
    }

    fun shareIntent(session: SessionRecord): Intent? {
        val files = session.directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .toList()
        if (files.isEmpty()) return null
        val uris = ArrayList<Uri>(files.size)
        files.forEach { file ->
            uris.add(
                FileProvider.getUriForFile(context, authority(context), file)
            )
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, session.sessionId)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(
                context.contentResolver,
                session.sessionId,
                uris.first()
            ).also { clip ->
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            }
        }
    }

    private fun sessionRoots(): List<File> = listOfNotNull(
        context.getExternalFilesDir(null),
        context.filesDir
    ).distinctBy { it.absolutePath }

    private fun parseSession(dir: File): SessionRecord? {
        return try {
            val metadataFile = File(dir, "metadata.json").takeIf { it.exists() }
            val json = metadataFile?.let { file ->
                JSONObject(file.readText())
            }
            val sessionId = json?.optString("session_id")?.ifBlank { null } ?: dir.name
            val status = json?.optString("status")?.ifBlank { null } ?: "UNKNOWN"
            val duration = json?.optDouble("duration_seconds", 0.0) ?: 0.0
            val hands = json?.optJSONObject("guardian_summary")
                ?.optDouble("hands_detected_percentage", 0.0) ?: 0.0
            val createdFromId = dir.name.removePrefix(SESSION_PREFIX).toLongOrNull()
            val createdAt = createdFromId
                ?: json?.optLong("checked_at_epoch_ms", 0L)?.takeIf { it > 0 }
                ?: dir.lastModified()
            val config = json?.optJSONObject("session_config")
            val video = dir.listFiles { file ->
                file.isFile && (file.name == "video.mp4" || file.name.matches(Regex("video_\\d+\\.mp4")))
            }?.maxByOrNull { it.length() }
            val imu = File(dir, "imu_data.csv").takeIf { it.exists() }
            SessionRecord(
                sessionId = sessionId,
                directory = dir,
                status = status,
                durationSeconds = duration,
                handsDetectedPercentage = hands,
                totalBytes = dirSize(dir),
                createdAtEpochMs = createdAt,
                videoFile = video,
                imuFile = imu,
                metadataFile = metadataFile,
                experiment = config?.optString("experiment").orEmpty(),
                participantId = config?.optString("participant_id").orEmpty(),
                task = config?.optString("task").orEmpty()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping unreadable session ${dir.name}", e)
            null
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        private const val TAG = "SessionRepository"
        private const val SESSION_PREFIX = "session_"
        fun authority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
