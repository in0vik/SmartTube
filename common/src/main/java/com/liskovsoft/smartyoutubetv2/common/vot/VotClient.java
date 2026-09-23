package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Small client for the unofficial Yandex Browser video translation endpoint. */
public final class VotClient {
    private static final String ENDPOINT = "https://api.browser.yandex.ru/video-translation/translate";
    private static final String SIGNING_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 YaBrowser/24.4.0.0 Safari/537.36";
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private final OkHttpClient mHttp = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    /** Returns a short-lived audio URL. No login or source-audio upload is attempted. */
    public String translate(String videoId, long durationMs) throws Exception {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        for (int attempt = 0; attempt < 24; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            Reply reply = request(url, durationMs / 1000d, attempt == 0);
            if ((reply.status == 1 || reply.status == 5) && reply.audioUrl != null) {
                return reply.audioUrl;
            }
            if (reply.status != 2 && reply.status != 3) {
                throw new IOException(reply.status == 6 ? "Для этого видео сервис запрашивает исходное аудио"
                        : reply.status == 7 ? "Сервис требует входа в аккаунт"
                        : "Перевод недоступен (код " + reply.status + ")");
            }
            Thread.sleep(Math.min(15, Math.max(3, reply.waitSeconds)) * 1000L);
        }
        throw new IOException("Время ожидания перевода истекло");
    }

    private Reply request(String url, double duration, boolean first) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 3, url);
        if (first) writeVarintField(out, 5, 1);
        writeVarint(out, 6 * 8 + 1);
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(duration).array());
        writeVarintField(out, 7, 1);
        writeString(out, 8, "en");
        writeString(out, 14, "ru");
        writeVarintField(out, 15, 1);
        writeVarintField(out, 16, 2);
        byte[] body = out.toByteArray();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder signature = new StringBuilder();
        for (byte b : mac.doFinal(body)) signature.append(String.format("%02x", b & 0xff));
        Request req = new Request.Builder().url(ENDPOINT)
                .header("Accept", "application/x-protobuf")
                .header("User-Agent", USER_AGENT)
                .header("Vtrans-Signature", signature.toString())
                .header("Sec-Vtrans-Token", UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .post(RequestBody.create(PROTOBUF, body)).build();
        try (Response response = mHttp.newCall(req).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Сервис перевода: HTTP " + response.code());
            }
            return parse(response.body().bytes());
        }
    }

    private static Reply parse(byte[] bytes) throws IOException {
        Reply reply = new Reply();
        int[] offset = {0};
        while (offset[0] < bytes.length) {
            long tag = readVarint(bytes, offset);
            int field = (int) (tag >> 3), wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(bytes, offset);
                if (field == 4) reply.status = (int) value;
                if (field == 5) reply.waitSeconds = (int) value;
            } else if (wire == 2) {
                long length = readVarint(bytes, offset);
                if (length < 0 || length > bytes.length - offset[0]) throw new IOException("Invalid VOT response");
                if (field == 1) reply.audioUrl = new String(bytes, offset[0], (int) length, StandardCharsets.UTF_8);
                offset[0] += (int) length;
            } else if (wire == 1 && bytes.length - offset[0] >= 8) {
                offset[0] += 8;
            } else if (wire == 5 && bytes.length - offset[0] >= 4) {
                offset[0] += 4;
            } else {
                throw new IOException("Invalid VOT response field");
            }
        }
        return reply;
    }

    private static long readVarint(byte[] bytes, int[] offset) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64 && offset[0] < bytes.length; shift += 7) {
            int b = bytes[offset[0]++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("Invalid VOT varint");
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, field * 8 + 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, field * 8);
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static final class Reply {
        int status;
        int waitSeconds;
        String audioUrl;
    }
}
