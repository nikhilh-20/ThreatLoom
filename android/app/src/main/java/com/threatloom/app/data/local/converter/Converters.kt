package com.threatloom.app.data.local.converter

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class Converters {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, String::class.java)
    private val adapter = moshi.adapter<List<String>>(listType)

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { adapter.toJson(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { adapter.fromJson(it) }
    }
}
