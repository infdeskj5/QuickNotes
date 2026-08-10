package com.infdesk5.quicknotes.model

import org.json.JSONArray
import org.json.JSONObject

data class NoteMetadata(
    val notes: MutableList<NoteMetaEntry> = mutableListOf(),
    val slotCount: Int = 5,
    val appColor: Int = 0xFF00FF7F.toInt() // Neon green default
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("slotCount", slotCount)
        obj.put("appColor", appColor)
        val arr = JSONArray()
        notes.forEach { arr.put(it.toJson()) }
        obj.put("notes", arr)
        return obj.toString(2)
    }

    companion object {
        fun fromJson(json: String): NoteMetadata {
            return try {
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("notes")
                val notes = mutableListOf<NoteMetaEntry>()
                for (i in 0 until arr.length()) {
                    notes.add(NoteMetaEntry.fromJson(arr.getJSONObject(i)))
                }
                NoteMetadata(
                    notes = notes,
                    slotCount = obj.optInt("slotCount", 5),
                    appColor = obj.optInt("appColor", 0xFF00FF7F.toInt())
                )
            } catch (e: Exception) {
                NoteMetadata()
            }
        }
    }
}

data class NoteMetaEntry(
    val id: String,
    val name: String,
    val slotIndex: Int = -1,
    val slotColor: Int = 0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("slotIndex", slotIndex)
            put("slotColor", slotColor)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): NoteMetaEntry {
            return NoteMetaEntry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                slotIndex = obj.optInt("slotIndex", -1),
                slotColor = obj.optInt("slotColor", 0)
            )
        }
    }
}
