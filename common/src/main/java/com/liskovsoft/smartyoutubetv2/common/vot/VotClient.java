package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final String HOST = "https://api.browser.yandex.ru";
    private static final String VERSION = "26.8.3.1002";
    private static final int CHUNK_SIZE = 5_295_308;
    private static final String SIGNING_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 YaBrowser/26.8.0.0 Safari/537.36";
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private final OkHttpClient mHttp = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build();
    private String mUuid;
    private String mSessionKey;

    /** Prefers lively voices and uploads source audio only when requested. */
    public String translate(String videoId, long durationMs, VotAudioSource source) throws Exception {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        createSession();
        boolean lively = true;
        boolean uploaded = false;
        for (int attempt = 0; attempt < 36; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            Reply reply = request(url, durationMs / 1000d, attempt == 0, lively);
            if ((reply.status == 1 || reply.status == 5) && reply.audioUrl != null) {
                return reply.audioUrl;
            }
            if (reply.status == 7 && lively) {
                lively = false;
                continue;
            }
            if (reply.status == 6 && !uploaded) {
                if (source == null || reply.translationId == null || reply.translationId.isEmpty()) {
                    throw new IOException("Исходная аудиодорожка недоступна для загрузки");
                }
                upload(url, reply.translationId, source);
                uploaded = true;
                continue;
            }
            if (reply.status != 2 && reply.status != 3 && reply.status != 6) {
                throw new IOException(reply.status == 7 ? "Сервис требует входа в аккаунт"
                        : "Перевод недоступен (код " + reply.status + ")");
            }
            Thread.sleep(Math.min(15, Math.max(3, reply.waitSeconds)) * 1000L);
        }
        throw new IOException("Время ожидания перевода истекло");
    }

    private Reply request(String url, double duration, boolean first, boolean lively) throws Exception {
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
        if (lively) writeVarintField(out, 18, 1);
        return parse(send("/video-translation/translate", out.toByteArray(), false, true), false);
    }

    private void createSession() throws Exception {
        mUuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 1, mUuid);
        writeString(out, 2, "video-translation");
        mSessionKey = parse(send("/session/create", out.toByteArray(), false, false), true).sessionKey;
        if (mSessionKey == null || mSessionKey.isEmpty()) throw new IOException("Сессия перевода недоступна");
    }

    private void upload(String url, String translationId, VotAudioSource source) throws Exception {
        Request request = new Request.Builder().url(source.url).header("User-Agent", USER_AGENT).get().build();
        try (Response response = mHttp.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Не удалось скачать исходное аудио: HTTP " + response.code());
            }
            long length = response.body().contentLength();
            if (length <= 0) length = source.size;
            if (length <= 0) throw new IOException("Неизвестен размер исходного аудио");
            long parts = (length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            if (parts > Integer.MAX_VALUE) throw new IOException("Исходное аудио слишком велико");
            String fileId = "random-web_abr-" + UUID.randomUUID();
            try (InputStream stream = response.body().byteStream()) {
                for (int index = 0; index < parts; index++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int count = (int) Math.min(CHUNK_SIZE, length - (long) index * CHUNK_SIZE);
                    byte[] audio = new byte[count];
                    int read = 0;
                    while (read < count) {
                        int n = stream.read(audio, read, count - read);
                        if (n < 0) throw new IOException("Исходное аудио оборвалось при загрузке");
                        read += n;
                    }
                    ByteArrayOutputStream partial = new ByteArrayOutputStream(count + 16);
                    writeVarintField(partial, 1, index);
                    writeBytes(partial, 2, audio);
                    ByteArrayOutputStream chunk = new ByteArrayOutputStream(count + 64);
                    writeBytes(chunk, 1, partial.toByteArray());
                    if (index == parts - 1) writeVarintField(chunk, 2, parts);
                    writeString(chunk, 3, fileId);
                    writeVarintField(chunk, 4, 1);
                    ByteArrayOutputStream body = new ByteArrayOutputStream(count + 128);
                    writeString(body, 1, translationId);
                    writeString(body, 2, url);
                    writeBytes(body, 4, chunk.toByteArray());
                    Reply reply = parse(send("/video-translation/audio", body.toByteArray(), true, true), false);
                    if (reply.uploadStatus != 1 && reply.uploadStatus != 2) {
                        throw new IOException("Сервис отклонил аудио (код " + reply.uploadStatus + ")");
                    }
                }
            }
        }
    }

    private byte[] send(String path, byte[] body, boolean put, boolean session) throws Exception {
        Request.Builder builder = new Request.Builder().url(HOST + path)
                .header("Accept", "application/x-protobuf")
                .header("User-Agent", USER_AGENT)
                .header("Vtrans-Signature", sign(body));
        if (session) {
            String token = mUuid + ":" + path + ":" + VERSION;
            builder.header("Sec-Vtrans-Sk", mSessionKey)
                    .header("Sec-Vtrans-Token", sign(token.getBytes(StandardCharsets.UTF_8)) + ":" + token);
        }
        RequestBody payload = RequestBody.create(PROTOBUF, body);
        Request req = (put ? builder.put(payload) : builder.post(payload)).build();
        try (Response response = mHttp.newCall(req).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Сервис перевода: HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder signature = new StringBuilder();
        for (byte b : mac.doFinal(body)) signature.append(String.format("%02x", b & 0xff));
        return signature.toString();
    }

    private static Reply parse(byte[] bytes, boolean session) throws IOException {
        Reply reply = new Reply();
        int[] offset = {0};
        while (offset[0] < bytes.length) {
            long tag = readVarint(bytes, offset);
            int field = (int) (tag >> 3), wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(bytes, offset);
                if (field == 4) reply.status = (int) value;
                if (field == 5) reply.waitSeconds = (int) value;
                if (field == 1) reply.uploadStatus = (int) value;
            } else if (wire == 2) {
                long length = readVarint(bytes, offset);
                if (length < 0 || length > bytes.length - offset[0]) throw new IOException("Invalid VOT response");
                if (field == 1) {
                    String value = new String(bytes, offset[0], (int) length, StandardCharsets.UTF_8);
                    if (session) reply.sessionKey = value; else reply.audioUrl = value;
                }
                if (field == 7) reply.translationId = new String(bytes, offset[0], (int) length, StandardCharsets.UTF_8);
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
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] bytes) {
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
        int uploadStatus;
        String audioUrl;
        String translationId;
        String sessionKey;
    }
}
