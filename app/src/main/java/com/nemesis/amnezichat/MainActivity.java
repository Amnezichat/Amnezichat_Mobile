package com.nemesis.amnezichat;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int FETCH_INTERVAL_MS = 10_000;
    private static final int PICK_IMAGE_REQUEST = 300;
    private static final int MAX_MESSAGES = 100;

    private final ArrayList<Message> messageList = new ArrayList<>();
    private final LinkedHashMap<String, Message> messageCache = new LinkedHashMap<>();

    private MessageAdapter messageAdapter;
    private EditText inputMessage;
    private MaterialButton sendButton, recordButton, uploadImageButton;

    private String serverUrl, username, roomId, encryptionKeyHex;
    private String privatePassword;
    private boolean isGroupChat;
    private String daita;

    private Handler handler;
    private Handler backgroundHandler;
    private Runnable fetchRunnable;

    private boolean isRecording = false;
    private MediaRecorder recorder;
    private File audioFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        String wallpaperUriString = prefs.getString("wallpaper_uri", null);
        if (wallpaperUriString != null) {
            try {
                Uri wallpaperUri = Uri.parse(wallpaperUriString);
                ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
                try (InputStream is = getContentResolver().openInputStream(wallpaperUri)) {
                    Drawable wallpaper = Drawable.createFromStream(is, wallpaperUri.toString());
                    rootLayout.setBackground(wallpaper);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load wallpaper", e);
                Toast.makeText(this, "Failed to load custom wallpaper", Toast.LENGTH_SHORT).show();
            }
        }

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 200);

        Intent intent = getIntent();
        serverUrl = intent.getStringExtra("server_url");
        username = intent.getStringExtra("username");
        roomId = intent.getStringExtra("room_id");
        encryptionKeyHex = intent.getStringExtra("encryption_key");
        daita = intent.getStringExtra("daita_enabled");
        privatePassword = intent.getStringExtra("private_password");
        isGroupChat = intent.getBooleanExtra("is_group_chat", false);

        if (!isGroupChat && privatePassword != null && !privatePassword.isEmpty()) {
            new Thread(() -> {
                try {
                    Ed25519PrivateKeyParameters signingPrivateKey = loadEd25519PrivateKey();
                    Ed25519PublicKeyParameters signingPublicKey;

                    if (signingPrivateKey != null) {
                        signingPublicKey = loadEd25519PublicKey();
                        if (signingPublicKey == null) {
                            signingPublicKey = signingPrivateKey.generatePublicKey();
                            saveEd25519PublicKey(signingPublicKey);
                        }
                        Log.d(TAG, "Loaded existing Ed25519 key pair.");
                    } else {
                        SecureRandom random = new SecureRandom();
                        signingPrivateKey = new Ed25519PrivateKeyParameters(random);
                        signingPublicKey = signingPrivateKey.generatePublicKey();

                        saveEd25519PrivateKey(signingPrivateKey);
                        saveEd25519PublicKey(signingPublicKey);
                        Log.d(TAG, "Generated and saved new Ed25519 key pair.");
                    }

                    KeyExchange.KeyConfirmationListener listener = new KeyExchange.KeyConfirmationListener() {
                        @Override
                        public void onKeyReceivedForConfirmation(String peerEdDsaHex, Runnable onConfirm, Runnable onReject) {
                            runOnUiThread(() -> {
                                try {
                                    Ed25519PublicKeyParameters myPublicKey = loadEd25519PublicKey();

                                    String myFingerprint = "<unavailable>";
                                    if (myPublicKey != null) {
                                        byte[] myPubBytes = myPublicKey.getEncoded();
                                        byte[] myHash = MessageDigest.getInstance("SHA-256").digest(myPubBytes);
                                        myFingerprint = bytesToHex(myHash);
                                    }

                                    byte[] peerPubBytes = hexStringToByteArray(peerEdDsaHex);
                                    byte[] peerHash = MessageDigest.getInstance("SHA-256").digest(peerPubBytes);
                                    String peerFingerprint = bytesToHex(peerHash);

                                    String message = "Peer Ed25519 Public Key Fingerprint:\n" + peerFingerprint +
                                            "\n\nYour Ed25519 Public Key Fingerprint:\n" + myFingerprint +
                                            "\n\nDo you confirm this key?";

                                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                            .setTitle("Key Confirmation")
                                            .setMessage(message)
                                            .setCancelable(false)
                                            .setPositiveButton("Confirm", (dialog, which) -> {
                                                onConfirm.run();
                                                Toast.makeText(MainActivity.this, "Key confirmed", Toast.LENGTH_SHORT).show();
                                            })
                                            .setNegativeButton("Reject", (dialog, which) -> {
                                                onReject.run();
                                                Toast.makeText(MainActivity.this, "Key rejected", Toast.LENGTH_SHORT).show();
                                                finishAffinity();
                                            })
                                            .show();

                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to display fingerprint", e);
                                    Toast.makeText(MainActivity.this, "Error computing fingerprint", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    };



                    String sharedSecretB64 = KeyExchange.performEcdhKeyExchange(
                            MainActivity.this,
                            roomId,
                            signingPrivateKey,
                            signingPublicKey,
                            serverUrl,
                            listener
                    );

                    byte[] secretBytes = Base64.decode(sharedSecretB64, Base64.NO_WRAP);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] keyBytes = digest.digest(secretBytes);
                    encryptionKeyHex = bytesToHex(keyBytes);
                    Log.d(TAG, "Derived symmetric key for private chat");
                } catch (Exception e) {
                    Log.e(TAG, "Key exchange failed", e);
                }
            }).start();
        }

        messageAdapter = new MessageAdapter(messageList);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        inputMessage = findViewById(R.id.inputMessage);
        sendButton = findViewById(R.id.sendButton);
        recordButton = findViewById(R.id.recordButton);
        uploadImageButton = findViewById(R.id.uploadImageButton);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(messageAdapter);

        handler = new Handler(getMainLooper());
        HandlerThread handlerThread = new HandlerThread("BackgroundHandler");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        fetchRunnable = () -> {
            backgroundHandler.post(this::fetchMessages);
            handler.postDelayed(fetchRunnable, FETCH_INTERVAL_MS);
        };

        sendButton.setOnClickListener(v -> {
            String text = inputMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                backgroundHandler.post(() -> {
                    String message = "<strong>" + username + "</strong>: " + text;
                    String padded = padMessage(message, 2048);
                    String encrypted = CryptoUtil.encrypt_data(padded, encryptionKeyHex);
                    if (encrypted != null) {
                        MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                        runOnUiThread(() -> inputMessage.setText(""));
                    }
                });
            }
        });

        recordButton.setOnClickListener(v -> {
            if (isRecording) stopRecording(); else startRecording();
        });

        uploadImageButton.setOnClickListener(v -> openImagePicker());

        startFetchingMessages();

        if ("true".equalsIgnoreCase(daita)) {
            startFakeTrafficThread();
        }
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private void saveEd25519PrivateKey(Ed25519PrivateKeyParameters key) {
        SharedPreferences prefs = getSharedPreferences("KeyPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String base64 = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);

        String encrypted = CryptoUtil.encrypt_data(base64, privatePassword);
        if (encrypted != null) {
            editor.putString("ed_private_key", encrypted);
            editor.apply();
        } else {
            Log.e(TAG, "Failed to encrypt private key");
        }
    }

    private void saveEd25519PublicKey(Ed25519PublicKeyParameters key) {
        SharedPreferences prefs = getSharedPreferences("KeyPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String base64 = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);

        String encrypted = CryptoUtil.encrypt_data(base64, privatePassword);
        if (encrypted != null) {
            editor.putString("ed_public_key", encrypted);
            editor.apply();
        } else {
            Log.e(TAG, "Failed to encrypt public key");
        }
    }


    @Nullable
    private Ed25519PrivateKeyParameters loadEd25519PrivateKey() {
        SharedPreferences prefs = getSharedPreferences("KeyPrefs", MODE_PRIVATE);
        String encrypted = prefs.getString("ed_private_key", null);
        if (encrypted != null) {
            String decryptedBase64 = CryptoUtil.decrypt_data(encrypted, privatePassword);
            if (decryptedBase64 == null || decryptedBase64.equals("DECRYPTION_FAILED")) {
                Log.e(TAG, "Failed to decrypt private key");
                return null;
            }
            byte[] keyBytes = Base64.decode(decryptedBase64, Base64.NO_WRAP);
            return new Ed25519PrivateKeyParameters(keyBytes, 0);
        }
        return null;
    }

    @Nullable
    private Ed25519PublicKeyParameters loadEd25519PublicKey() {
        SharedPreferences prefs = getSharedPreferences("KeyPrefs", MODE_PRIVATE);
        String encrypted = prefs.getString("ed_public_key", null);
        if (encrypted != null) {
            String decryptedBase64 = CryptoUtil.decrypt_data(encrypted, privatePassword);
            if (decryptedBase64 == null || decryptedBase64.equals("DECRYPTION_FAILED")) {
                Log.e(TAG, "Failed to decrypt public key");
                return null;
            }
            byte[] keyBytes = Base64.decode(decryptedBase64, Base64.NO_WRAP);
            return new Ed25519PublicKeyParameters(keyBytes, 0);
        }
        return null;
    }



    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void startFakeTrafficThread() {
        Thread fakeTrafficThread = new Thread(() -> {
            Random rand = new Random();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int size = rand.nextInt(2048) + 1;
                    byte[] randomData = new byte[size];
                    rand.nextBytes(randomData);

                    String dummyMessage = "[DUMMY_DATA]: " + Base64.encodeToString(randomData, Base64.NO_WRAP);
                    String paddedMessage = padMessage(dummyMessage, 2048);
                    String encrypted = CryptoUtil.encrypt_data(paddedMessage, encryptionKeyHex);

                    if (encrypted != null) {
                        MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                        Log.d(TAG, "Fake message sent successfully");
                    } else {
                        Log.e(TAG, "Encryption failed for a message");
                    }

                    int sleepTime = rand.nextInt(120) + 1;
                    Thread.sleep(sleepTime * 1000L);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Fake traffic thread interrupted, stopping.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in fake traffic thread", e);
                }
            }
        });
        fakeTrafficThread.start();
    }

    private String padMessage(String message, int maxLength) {
        int currentLength = message.length();
        if (currentLength >= maxLength) {
            return message;
        }

        int paddingLength = maxLength - currentLength;
        StringBuilder padding = new StringBuilder(paddingLength);
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < paddingLength; i++) {
            char ch = (char) (random.nextInt(94) + 33);
            padding.append(ch);
        }

        return message + "<padding>" + padding + "</padding>";
    }

    private void startFetchingMessages() {
        handler.postDelayed(fetchRunnable, FETCH_INTERVAL_MS);
    }

    private void fetchMessages() {
        try {
            List<String> fetched = MessageService.receiveAndFetchMessages(
                    roomId, encryptionKeyHex, serverUrl, true);

            if (fetched != null && !fetched.isEmpty()) {
                boolean updated = false;

                for (String raw : fetched) {
                    if (!messageCache.containsKey(raw)) {
                        Message msg = new Message(raw);
                        messageCache.put(raw, msg);
                        updated = true;
                    }
                }

                while (messageCache.size() > MAX_MESSAGES) {
                    String oldestKey = messageCache.keySet().iterator().next();
                    messageCache.remove(oldestKey);
                }

                if (updated) {
                    runOnUiThread(() -> {
                        messageList.clear();
                        messageList.addAll(messageCache.values());
                        messageAdapter.notifyDataSetChanged();
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching messages", e);
        }
    }

    private void startRecording() {
        try {
            File dir = getExternalFilesDir("audio");
            if (dir != null && !dir.exists()) dir.mkdirs();
            audioFile = File.createTempFile("audio", ".3gp", dir);

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.prepare();
            recorder.start();

            isRecording = true;
            Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Recording failed!", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        try {
            recorder.stop();
            recorder.release();
            recorder = null;
            isRecording = false;
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();

            backgroundHandler.post(() -> {
                try {
                    byte[] audioBytes = readFileToBytes(audioFile);
                    String base64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP);
                    String message = "<strong>" + username + "</strong>: " + "<audio>" + base64 + "</audio>";
                    String encrypted = CryptoUtil.encrypt_data(message, encryptionKeyHex);
                    if (encrypted != null) {
                        MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send audio message", e);
                }
            });
        } catch (RuntimeException e) {
            e.printStackTrace();
            Toast.makeText(this, "Recording error", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), selectedImageUri);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                    byte[] imageBytes = baos.toByteArray();

                    String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                    String message = "<strong>" + username + "</strong>: " + "<media>" + base64 + "</media>";

                    backgroundHandler.post(() -> {
                        String encrypted = CryptoUtil.encrypt_data(message, encryptionKeyHex);
                        if (encrypted != null) {
                            MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                        }
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Failed to process selected image", e);
                    Toast.makeText(this, "Failed to send image", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
