package com.ecomadison.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Spec §3.1. Populated by the Phase 3 property-binding flow; unused by the Phase 1 read path. */
@Entity(tableName = "property_org")
data class PropertyOrgEntity(
    @PrimaryKey val propertyCode: String,
    val landlordId: String,
    val displayName: String,
    val boundAtTimestamp: Long
)
