package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

/** The best directly downloadable, non-DRC audio-only stream exposed by the player. */
public final class VotAudioSource {
    public final String url;
    public final String itag;
    public final long size;

    private VotAudioSource(MediaFormat format) {
        url = format.getUrl();
        itag = format.getITag();
        size = number(format.getClen());
    }

    public static VotAudioSource best(MediaItemFormatInfo info) {
        if (info == null || info.getAdaptiveFormats() == null) return null;
        MediaFormat best = null;
        long bestBitrate = -1;
        for (MediaFormat format : info.getAdaptiveFormats()) {
            String mime = format.getMimeType();
            if (format.getFormatType() != MediaFormat.FORMAT_TYPE_DASH || format.isDrc()
                    || mime == null || !mime.startsWith("audio/") || format.getUrl() == null
                    || format.getUrl().isEmpty()) continue;
            String language = format.getLanguage();
            if (language != null && !language.isEmpty() && !language.startsWith("en")) continue;
            long bitrate = number(format.getBitrate());
            if (bitrate > bestBitrate) {
                best = format;
                bestBitrate = bitrate;
            }
        }
        return best == null ? null : new VotAudioSource(best);
    }

    private static long number(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0; }
    }
}
