package com.ftpflix.sources.resolve

// Stream source selector; specifying one pins resolution to that provider.
enum class StreamSource(val label: String) {
    AUTO("Auto"),
    CIRCLE_FTP("CircleFTP"),
    ROAR_ZONE("RoarZone"),
    HD_HUB("HdHub");
}
