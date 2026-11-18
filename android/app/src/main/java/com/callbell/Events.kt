package com.callbell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import org.json.JSONObject
import java.util.UUID

object Events {
    const val RINGING = "ringing"
    const val MISSED = "missed"
    const val ANSWERED = "answered"
    const val TEST = "test"

    fun make(context: Context, type: String, callId: String, number: String?): JSONObject {
        val num = number?.takeIf { it.isNotBlank() }
        return JSONObject().apply {
            put("v", 1)
            put("id", UUID.randomUUID().toString())
            put("call", callId)
            put("type", type)
            put("number", num ?: JSONObject.NULL)
            put("name", contactName(context, num) ?: JSONObject.NULL)
            put("device", Build.MODEL)
            put("ts", System.currentTimeMillis())
        }
    }

    private fun contactName(context: Context, number: String?): String? {
        if (number == null) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
