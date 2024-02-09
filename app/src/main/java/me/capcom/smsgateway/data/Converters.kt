package me.capcom.smsgateway.data

import androidx.room.TypeConverter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Converters {
    @TypeConverter
    fun listToString(value: List<String>?): String? {
        return value?.joinToString("|")
    }

    @TypeConverter
    fun listFromString(value: String?): List<String>? {
        return value?.split("|")
    }


    @TypeConverter
    fun dateToString(value: Date?): String? {
        return value?.let { DATE_FORMAT.format(it) }
    }

    @TypeConverter
    fun dateFromString(value: String?): Date? {
        return value?.let { DATE_FORMAT.parse(it) }
    }

    companion object {
        private val DATE_FORMAT =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
    }
}