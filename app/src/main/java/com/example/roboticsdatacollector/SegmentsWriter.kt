package com.example.roboticsdatacollector

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SegmentsWriter {
    fun write(sessionDir: File, segments: List<VideoSegment>) {
        val json = JSONObject().apply {
            put(
                "segments",
                JSONArray().apply {
                    segments.forEach { segment ->
                        put(
                            JSONObject().apply {
                                put("index", segment.index)
                                put("path", segment.fileName)
                                put("start_ns", segment.startTimestampNs)
                                put("end_ns", segment.endTimestampNs)
                            }
                        )
                    }
                }
            )
        }
        File(sessionDir, "segments.json").writeText(json.toString(2))
    }
}
