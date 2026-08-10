package com.ecomadison.app.domain.model

/** UI-agnostic result of REQ-4.2.2 rule resolution. The Compose layer renders [emoji] + [text] verbatim. */
sealed class RuleMessage(val emoji: String, val text: String) {

    object TooSmall : RuleMessage(
        emoji = "❌",
        text = "Too Small for Pellitteri Systems. Discard in Trash."
    )

    object Keep3D : RuleMessage(
        emoji = "⚠️",
        text = "Madison Rule: Keep 3D! Do not crush or flatten."
    )

    object Flatten : RuleMessage(
        emoji = "✅",
        text = "Madison Rule: Flatten completely before discarding."
    )

    /** Glass and paper: on Madison's accepted curbside list with no shape prep required. */
    object RecyclableAsIs : RuleMessage(
        emoji = "✅",
        text = "Recyclable as-is — make sure it's clean, empty, and dry."
    )

    /** REQ-4.2.2 negative case: no rule row matched. Never render a blank state. */
    object GenericFallback : RuleMessage(
        emoji = "ℹ️",
        text = "No specific Madison rule found — check local guidelines."
    )
}
