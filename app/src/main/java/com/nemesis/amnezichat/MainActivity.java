package com.nemesis.amnezichat;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private String serverUrl, username, roomId, encryptionKeyHex, daita;

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
                InputStream is = getContentResolver().openInputStream(wallpaperUri);
                Drawable wallpaper = Drawable.createFromStream(is, wallpaperUri.toString());
                rootLayout.setBackground(wallpaper);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load wallpaper", e);
                Toast.makeText(this, "Failed to load custom wallpaper", Toast.LENGTH_SHORT).show();
            }
        }

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 200);

        serverUrl = getIntent().getStringExtra("server_url");
        username = getIntent().getStringExtra("username");
        roomId = getIntent().getStringExtra("room_id");
        encryptionKeyHex = getIntent().getStringExtra("encryption_key");
        daita = getIntent().getStringExtra("daita_enabled");

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
            if (isRecording) stopRecording();
            else startRecording();
        });

        uploadImageButton.setOnClickListener(v -> openImagePicker());

        startFetchingMessages();

        if ("true".equalsIgnoreCase(daita)) {
            startFakeTrafficThread();
        }
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
                    String message = "<audio>" + base64 + "</audio>";
                    String composed = "<strong>" + username + "</strong>: " + message;
                    String padded = padMessage(composed, 2048);
                    String encrypted = CryptoUtil.encrypt_data(padded, encryptionKeyHex);
                    if (encrypted != null) {
                        MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading audio file", e);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error stopping recording!", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream(8192)) {
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
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();

            backgroundHandler.post(() -> {
                try (InputStream input1 = getContentResolver().openInputStream(imageUri)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(input1, null, options);

                    int scale = 1, maxDim = 1024;
                    while (options.outWidth / scale > maxDim || options.outHeight / scale > maxDim) {
                        scale *= 2;
                    }

                    try (InputStream input2 = getContentResolver().openInputStream(imageUri)) {
                        options.inJustDecodeBounds = false;
                        options.inSampleSize = scale;

                        Bitmap bitmap = BitmapFactory.decodeStream(input2, null, options);
                        if (bitmap == null) {
                            runOnUiThread(() -> Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                        bitmap.recycle();

                        String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                        String message = "<media>" + base64Image + "</media>";
                        String composed = "<strong>" + username + "</strong>: " + message;
                        String padded = padMessage(composed, 2048);
                        String encrypted = CryptoUtil.encrypt_data(padded, encryptionKeyHex);

                        runOnUiThread(() -> {
                            if (encrypted != null) {
                                MessageService.sendEncryptedMessage(encrypted, roomId, serverUrl);
                                Toast.makeText(this, "Image sent", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Encryption failed for image", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to handle image", e);
                    runOnUiThread(() -> Toast.makeText(this, "Failed to read image", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(fetchRunnable);
    }
}
