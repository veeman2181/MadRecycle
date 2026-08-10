package com.ecomadison.app.domain.model

/** Coarse material classification produced by any scan tier (barcode lookup, object detection, OCR, or manual fallback). */
enum class MaterialType {
    CARDBOARD,
    PLASTIC_JUG,
    METAL_CAN,
    DRINK_CARTON,
    PLASTIC_FILM,
    GLASS,
    PAPER,
    OTHER
}
