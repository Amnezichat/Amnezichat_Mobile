package com.nemesis.amnezichat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import okhttp3.*;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class KeyExchange {

    private static final String START_TAG = "ECDH_PUBLIC_KEY:";
    private static final String EDDSA_TAG = "EDDSA_PUBLIC_KEY:";
    private static final String END_TAG = "[END DATA]";
    private static final Gson gson = new Gson();

    static class MessagePayload {
        public String message;
        @SerializedName("room_id")
        public String roomId;

        MessagePayload(String message, String roomId) {
            this.message = message;
            this.roomId = roomId;
        }
    }

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();

    public interface KeyConfirmationListener {
        void onKeyReceivedForConfirmation(String peerEdDsaHex, Runnable onConfirm, Runnable onReject);
    }

    private static String fetchMessagesFromServer(String serverBaseUrl, String roomId) {
        Request req = new Request.Builder()
                .url(serverBaseUrl + "/messages?room_id=" + roomId)
                .get()
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            return resp.body() != null ? resp.body().string() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String performEcdhKeyExchange(
            Context context,
            String roomId,
            Ed25519PrivateKeyParameters signingPrivateKey,
            Ed25519PublicKeyParameters signingPublicKey,
            String serverBaseUrl,
            KeyConfirmationListener confirmationListener
    ) throws Exception {

        if (roomId == null || roomId.trim().length() < 8) {
            throw new IllegalArgumentException("roomId must be at least 8 characters");
        }
        roomId = roomId.trim();

        SecureRandom rnd = new SecureRandom();
        X25519PrivateKeyParameters ephPriv = new X25519PrivateKeyParameters(rnd);
        X25519PublicKeyParameters ephPub = ephPriv.generatePublicKey();
        byte[] ephPubBytes = ephPub.getEncoded();

        byte[] sig = signData(ephPubBytes, signingPrivateKey);
        String signedHex = Hex.toHexString(ephPubBytes) + Hex.toHexString(sig);

        String pubEdDsaHex = Hex.toHexString(signingPublicKey.getEncoded());
        String formatted = EDDSA_TAG + pubEdDsaHex + END_TAG + "\n" +
                START_TAG + signedHex + END_TAG;

        MessagePayload payload = new MessagePayload(formatted, roomId);
        String json = gson.toJson(payload);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request post = new Request.Builder()
                .url(serverBaseUrl + "/send")
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(post).execute()) {
            if (resp.code() != 200) {
                throw new IOException("Failed to send keys, response code: " + resp.code());
            }
        }

        Handler ui = new Handler(Looper.getMainLooper());

        while (true) {
            String msgs = fetchMessagesFromServer(serverBaseUrl, roomId);
            if (msgs == null) {
                Thread.sleep(1000);
                continue;
            }

            List<Ed25519PublicKeyParameters> peerEdDsaKeys = new ArrayList<>();
            int startIdx = 0;
            while (true) {
                int edIdx = msgs.indexOf(EDDSA_TAG, startIdx);
                if (edIdx < 0) break;
                int end = msgs.indexOf(END_TAG, edIdx);
                if (end < 0) break;

                String hex = msgs.substring(edIdx + EDDSA_TAG.length(), end).trim();
                startIdx = end + END_TAG.length();

                if (hex.equalsIgnoreCase(pubEdDsaHex)) continue;

                try {
                    peerEdDsaKeys.add(new Ed25519PublicKeyParameters(Hex.decode(hex), 0));
                } catch (Exception ex) {
                }
            }

            if (peerEdDsaKeys.isEmpty()) {
                Thread.sleep(1000);
                continue;
            }

            int idx = 0;
            while (true) {
                int s = msgs.indexOf(START_TAG, idx);
                if (s < 0) break;
                int e = msgs.indexOf(END_TAG, s);
                if (e < 0) break;

                String found = msgs.substring(s + START_TAG.length(), e).trim();
                idx = e + END_TAG.length();

                if (found.equalsIgnoreCase(signedHex)) continue;
                if (found.length() != 192) continue;

                byte[] peerPub = Hex.decode(found.substring(0, 64));
                byte[] peerSig = Hex.decode(found.substring(64));

                Ed25519PublicKeyParameters matchingPeerKey = null;
                for (Ed25519PublicKeyParameters peerKey : peerEdDsaKeys) {
                    try {
                        verifySignature(peerPub, peerSig, peerKey);
                        matchingPeerKey = peerKey;
                        break;
                    } catch (Exception ex) {
                    }
                }
                if (matchingPeerKey == null) continue;

                final String peerHex = Hex.toHexString(matchingPeerKey.getEncoded());
                final Object lock = new Object();
                final boolean[] ok = new boolean[1], done = new boolean[1];

                ui.post(() -> confirmationListener.onKeyReceivedForConfirmation(
                        peerHex,
                        () -> {
                            synchronized (lock) {
                                ok[0] = true;
                                done[0] = true;
                                lock.notifyAll();
                            }
                        },
                        () -> {
                            synchronized (lock) {
                                ok[0] = false;
                                done[0] = true;
                                lock.notifyAll();
                            }
                        }
                ));

                synchronized (lock) {
                    while (!done[0]) lock.wait();
                }

                if (!ok[0]) continue;

                X25519PublicKeyParameters peerParams = new X25519PublicKeyParameters(peerPub, 0);
                byte[] secret = new byte[32];
                ephPriv.generateSecret(peerParams, secret, 0);
                return Base64.getEncoder().encodeToString(secret);
            }

            Thread.sleep(1000);
        }
    }

    private static byte[] signData(byte[] data, Ed25519PrivateKeyParameters priv) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, priv);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    private static void verifySignature(byte[] data, byte[] sig, Ed25519PublicKeyParameters pub) throws Exception {
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, pub);
        verifier.update(data, 0, data.length);
        if (!verifier.verifySignature(sig)) {
            throw new Exception("Signature verification failed");
        }
    }
}
