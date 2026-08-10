package com.ecomadison.app.domain.model

/**
 * Fine-grained product guess, one level more specific than [MaterialType]. Names mirror
 * `tools/material_classifier/train_granular_experiment.py`'s trained label set so a classifier's
 * labels-file lines parse directly via [valueOf] uppercased. Deliberately covers only categories
 * on Madison's curbside-recyclable list — non-recyclables collapse to plain [MaterialType.OTHER]
 * with no product-level detail, since the app's message for all of them is the same either way.
 *
 * Two Kaggle-native splits are NOT represented here despite being on the curbside list:
 * cardboard (boxes vs. packaging) and food cans (aluminum vs. steel) turned out to be
 * unlearnable or not a real distinction (see README.md's granular-experiment results) and were
 * merged back into plain `cardboard`/`food_can` labels, which parse as a bare [MaterialType] via
 * [parseClassifierLabel]'s fallback instead of a [ProductCategory] -- cardboard has no
 * product-level entry at all, and the merged can class is [FOOD_CAN].
 */
enum class ProductCategory(val materialType: MaterialType, val displayName: String) {
    AEROSOL_CANS(MaterialType.METAL_CAN, "aerosol can"),
    ALUMINUM_SODA_CANS(MaterialType.METAL_CAN, "aluminum soda can"),
    FOOD_CAN(MaterialType.METAL_CAN, "food can"),
    PLASTIC_WATER_BOTTLES(MaterialType.PLASTIC_JUG, "plastic water bottle"),
    PLASTIC_SODA_BOTTLES(MaterialType.PLASTIC_JUG, "plastic soda bottle"),
    PLASTIC_DETERGENT_BOTTLES(MaterialType.PLASTIC_JUG, "plastic detergent bottle"),
    PLASTIC_FOOD_CONTAINERS(MaterialType.PLASTIC_JUG, "plastic food container"),
    PLASTIC_SHOPPING_BAGS(MaterialType.PLASTIC_FILM, "plastic shopping bag"),
    PLASTIC_TRASH_BAGS(MaterialType.PLASTIC_FILM, "plastic trash bag"),
    GLASS_BEVERAGE_BOTTLES(MaterialType.GLASS, "glass beverage bottle"),
    GLASS_COSMETIC_CONTAINERS(MaterialType.GLASS, "glass cosmetic container"),
    GLASS_FOOD_JARS(MaterialType.GLASS, "glass food jar"),
    MAGAZINES(MaterialType.PAPER, "magazine"),
    NEWSPAPER(MaterialType.PAPER, "newspaper"),
    OFFICE_PAPER(MaterialType.PAPER, "office paper"),
    PAPER_CUPS(MaterialType.PAPER, "paper cup")
}
