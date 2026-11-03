package com.brightblu.parc.model

/** Immutable arc preferences. Storage uses the explicit ids, never enum names or ordinals. */
data class ArcFeel(
    val spinLimit: SpinLimit = SpinLimit.Balanced,
    val bounce: Bounce = Bounce.Balanced,
    val magnify: Magnify = Magnify.Subtle,
)

enum class SpinLimit(val id: String, val slots: Int) {
    Tight("tight", 2), Balanced("balanced", 4), Free("free", 8);

    companion object {
        fun fromId(id: String?): SpinLimit = entries.firstOrNull { it.id == id } ?: Balanced
    }
}

enum class Bounce(val id: String, val dampingRatio: Float) {
    Calm("calm", 0.90f), Balanced("balanced", 0.72f), Playful("playful", 0.55f);

    companion object {
        fun fromId(id: String?): Bounce = entries.firstOrNull { it.id == id } ?: Balanced
    }
}

enum class Magnify(val id: String) {
    Off("off"), Subtle("subtle"), DockLike("dock");

    companion object {
        fun fromId(id: String?): Magnify = entries.firstOrNull { it.id == id } ?: Subtle
    }
}
