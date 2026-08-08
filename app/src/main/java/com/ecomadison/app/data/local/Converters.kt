package com.ecomadison.app.data.local

import androidx.room.TypeConverter
import com.ecomadison.app.domain.model.MaterialType
import com.ecomadison.app.domain.model.SyncStatus

class Converters {

    @TypeConverter
    fun fromMaterialType(value: MaterialType): String = value.name

    @TypeConverter
    fun toMaterialType(value: String): MaterialType = MaterialType.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
