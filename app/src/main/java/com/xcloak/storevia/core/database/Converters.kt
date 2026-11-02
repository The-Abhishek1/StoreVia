package com.xcloak.storevia.core.database

import androidx.room.TypeConverter
import java.time.LocalDate

class Converters {
    @TypeConverter fun fromDate(d: LocalDate?): String? = d?.toString()      // ISO yyyy-MM-dd sorts correctly
    @TypeConverter fun toDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
}
