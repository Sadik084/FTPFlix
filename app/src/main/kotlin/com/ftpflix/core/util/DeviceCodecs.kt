package com.ftpflix.core.util

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary

@UnstableApi
object DeviceCodecs {

    data class Row(val label: String, val ok: Boolean, val detail: String)

    fun report(): List<Row> = listOf(
        Row(
            "Device", true,
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        ),
        codecRow("H.264 (High)", MimeTypes.VIDEO_H264, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
        codecRow("HEVC (8-bit)", MimeTypes.VIDEO_H265, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
        codecRow("HEVC 10-bit", MimeTypes.VIDEO_H265, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
        codecRow("VP9", MimeTypes.VIDEO_VP9, null),
        codecRow("AV1", MimeTypes.VIDEO_AV1, null),
        Row(
            "Dolby Digital (AC-3)",
            FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3),
            if (FfmpegLibrary.isAvailable()) "Software (FFmpeg)" else "FFmpeg library not loaded"
        ),
        widevineRow(),
    )

    val supportsHevc10: Boolean
        get() = decodersFor(MimeTypes.VIDEO_H265, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            .isNotEmpty()

    private fun decodersFor(mime: String, profile: Int?): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (profile == null || runCatching {
                    info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
                }.getOrDefault(false))
        }

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated
        else !(name.startsWith("OMX.google.") || name.startsWith("c2.android."))

    private fun codecRow(label: String, mime: String, profile: Int?): Row {
        val all = decodersFor(mime, profile)
        val hw = all.firstOrNull { it.isHardware() }
        return when {
            hw != null -> Row(label, true, "Hardware · ${hw.name}")
            all.isNotEmpty() -> Row(label, true, "Software only")
            else -> Row(label, false, "Not supported")
        }
    }

    private fun widevineRow(): Row {
        val level = runCatching {
            val drm = MediaDrm(C.WIDEVINE_UUID)
            try { drm.getPropertyString("securityLevel") } finally {
                if (Build.VERSION.SDK_INT >= 28) drm.close() else drm.release()
            }
        }.getOrNull()
        return Row("Widevine DRM", level == "L1", level?.let { "Level $it" } ?: "Unavailable")
    }
}
