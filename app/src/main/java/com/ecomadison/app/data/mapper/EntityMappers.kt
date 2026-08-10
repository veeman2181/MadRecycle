package com.ecomadison.app.data.mapper

import com.ecomadison.app.data.local.entity.RecyclableItemEntity
import com.ecomadison.app.data.local.entity.ScanLogEntity
import com.ecomadison.app.domain.model.RecyclableItem
import com.ecomadison.app.domain.model.ScanLogEntry

fun RecyclableItemEntity.toDomain() = RecyclableItem(
    barcode = barcode,
    itemName = itemName,
    materialType = materialType,
    rulesText = rulesText,
    minDimensionInches = minDimensionInches,
    requiresFlatten = requiresFlatten,
    requires3D = requires3D,
    isRecyclableAsIs = isRecyclableAsIs,
    lastUpdatedTimestamp = lastUpdatedTimestamp
)

fun ScanLogEntry.toEntity() = ScanLogEntity(
    id = id,
    userId = userId,
    propertyCode = propertyCode,
    barcode = barcode,
    materialType = materialType,
    resolvedByTier = resolvedByTier,
    anchorId = anchorId,
    attestationPhotoUri = attestationPhotoUri,
    pointsAwarded = pointsAwarded,
    timestamp = timestamp,
    syncStatus = syncStatus
)
