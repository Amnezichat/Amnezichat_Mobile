package com.nemesis.amnezichat;

import android.util.Log;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.*;

import com.google.gson.Gson;

public class MessageService {
    private static final String TAG = "MessageService";

    private static final Gson gson = new Gson();

    static class MessageData {
        String message;
        String room_id;

        MessageData(String message, String room_id) {
            this.message = message;
            this.room_id = room_id;
        }
    }

    public static void sendEncryptedMessage(String encryptedMessage, String roomId, String serverUrl) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(60))
                        .build();

                String formattedMessage = "-----BEGIN ENCRYPTED MESSAGE-----" + encryptedMessage + "-----END ENCRYPTED MESSAGE-----";
                MessageData messageData = new MessageData(formattedMessage, roomId);

                String json = gson.toJson(messageData);

                RequestBody body = RequestBody.create(json, MediaType.get("text/plain; charset=utf-8"));

                String sendUrl = serverUrl + "/send";

                Request request = new Request.Builder()
                        .url(sendUrl)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Failed to send message: " + response.code());
                    } else {
                        Log.d(TAG, "Message sent successfully");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
            }
        }).start();
    }

    public static List<String> receiveAndFetchMessages(String roomId, String sharedSecret, String serverUrl, boolean gui) {
        List<String> messages = new ArrayList<>();

        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(30))
                .build();

        String url = serverUrl + "/messages?room_id=" + roomId;

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String body = response.body().string();

                Pattern pattern = Pattern.compile(
                        "-----BEGIN ENCRYPTED MESSAGE-----\\s*(.*?)\\s*-----END ENCRYPTED MESSAGE-----",
                        Pattern.DOTALL);
                Matcher matcher = pattern.matcher(body);

                while (matcher.find()) {
                    String encrypted = matcher.group(1).trim();

                    String decrypted = decryptData(encrypted, sharedSecret);
                    if (decrypted == null || decrypted.equals("DECRYPTION_FAILED")) {
                        Log.e(TAG, "Failed to decrypt a message");
                        continue;
                    }

                    String unpadded = unpadMessage(decrypted);

                    if (unpadded.contains("[DUMMY_DATA]:")) continue;

                    messages.add(unpadded);
                }
            } else {
                Log.e(TAG, "Failed to fetch messages: " + response.code() + " - " + response.body().string());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error receiving messages", e);
        }

        return messages;
    }

    private static String decryptData(String encrypted, String sharedSecret) {
        return CryptoUtil.decrypt_data(encrypted, sharedSecret);
    }

    private static String unpadMessage(String message) {
        int start = message.indexOf("<padding>");
        int end = message.indexOf("</padding>");
        if (start >= 0 && end >= 0 && end > start) {
            return message.substring(0, start) + message.substring(end + "</padding>".length());
        }
        return message;
    }
}
