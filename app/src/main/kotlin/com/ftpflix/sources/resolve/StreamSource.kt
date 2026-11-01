package com.ftpflix.sources.resolve

// Stream source selector; specifying one pins resolution to that provider.
enum class StreamSource(val label: String) {
    AUTO("Auto"),
    CIRCLE_FTP("CircleFTP"),
    ROAR_ZONE("RoarZone"),
    HD_HUB("HdHub"),
    HYPER("Hyper");

    // Stremio addons: no series browse endpoint; per-episode resolve + quality tiers.
    val isAddon: Boolean get() = this == HD_HUB || this == HYPER
}
