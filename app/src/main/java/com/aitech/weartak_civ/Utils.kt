package com.aitech.weartak_civ

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Utils {
    private const val TAG = "WearTAK-Utils"
    private var logFileFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun isValidJson(jsonString: String) : Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return try {
            Gson().fromJson(jsonString, Any::class.java)
            true
        } catch (e: JsonSyntaxException) {
            false
        }
    }

    fun getValueFromJson(jsonString: String, key: String): String? {
        return try {
            val gson = Gson()
            val jsonObject: JsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            jsonObject.get(key)?.asString
        } catch (e: Exception) {
            null
        }
    }

    fun isStringEmptyJson(jsonString: String): Boolean {
        if (jsonString.isNullOrEmpty()) {
            return true
        }

        return try {
            Gson().fromJson(jsonString, Any::class.java)
            jsonString.trim() == "{}" || jsonString.trim() == "[]"
        } catch (e: JsonParseException) {
            false
        }
    }

    fun writeToLogFileLocation(logFile: File?, data: String) {
        if (null == logFile) {
            return
        }
        try {
            val writer = FileWriter(logFile, true) // Append to existing file
            writer.write("${LocalDateTime.now().format(logFileFormatter)}: $data\n")
            writer.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log to file: ${e.message}", e)
        }
    }
}