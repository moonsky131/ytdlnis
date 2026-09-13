package com.deniscerri.ytdl.core.stream

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets

internal class StreamGobbler(private val buffer: StringBuffer, private val stream: InputStream) :
    Thread() {
    init {
        start()
    }

    override fun run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val reader = java.io.BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 8192)
            val chunk = CharArray(4096)
            var readCount: Int
            while (reader.read(chunk, 0, chunk.size).also { readCount = it } != -1) {
                buffer.append(chunk, 0, readCount)
            }
        } catch (e: IOException) {
            Log.e(TAG, "failed to read stream", e)
        }
    }

    companion object {
        private val TAG = StreamGobbler::class.java.simpleName
    }
}