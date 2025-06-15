package com.nemesis.amnezichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.text.Html;
import android.text.Spanned;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private final ArrayList<Message> messages;
    private MediaPlayer mediaPlayer;
    private Button currentlyPlayingButton;

    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView nicknameView, textView;
        Button playButton;
        ImageView imageView;
        LinearLayout imageBubble;

        public MessageViewHolder(View v) {
            super(v);
            nicknameView = v.findViewById(R.id.messageNickname);
            textView = v.findViewById(R.id.messageText);
            playButton = v.findViewById(R.id.playButton);
            imageView = v.findViewById(R.id.messageImage);
            imageBubble = v.findViewById(R.id.messageImageBubble);
        }
    }

    public MessageAdapter(ArrayList<Message> messages) {
        this.messages = messages;
    }

    @Override
    public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.message_item, parent, false);
        return new MessageViewHolder(v);
    }

    @Override
    public void onBindViewHolder(MessageViewHolder holder, int position) {
        String raw = messages.get(position).getContent();

        RecyclerView.LayoutParams layoutParams = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.topMargin = (position == 0) ? 100 : 0;
            holder.itemView.setLayoutParams(layoutParams);
        }

        String nickname = extractTagContent(raw, "strong");
        if (nickname == null) {
            nickname = "Unknown";
        }
        holder.nicknameView.setText(nickname);

        // Hide all optional views initially
        holder.textView.setVisibility(View.GONE);
        holder.playButton.setVisibility(View.GONE);
        holder.imageView.setVisibility(View.GONE);
        holder.imageBubble.setVisibility(View.GONE);

        if (raw.contains("<audio>")) {
            holder.playButton.setVisibility(View.VISIBLE);
            holder.playButton.setText("▶️ Play Audio");

            holder.playButton.setOnClickListener(v -> {
                String audioB64 = extractTagContent(raw, "audio");
                if (audioB64 == null) return;

                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    stopAudio();
                    if (currentlyPlayingButton != null) {
                        currentlyPlayingButton.setText("▶️ Play Audio");
                    }
                    if (holder.playButton == currentlyPlayingButton) {
                        currentlyPlayingButton = null;
                        return;
                    }
                }

                currentlyPlayingButton = holder.playButton;
                playAudio(holder.playButton.getContext(), audioB64, holder.playButton);
            });
        } else if (raw.contains("<media>")) {
            String imageB64 = extractTagContent(raw, "media");
            if (imageB64 != null) {
                byte[] decodedBytes = Base64.decode(imageB64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    holder.imageView.setImageBitmap(bitmap);
                    holder.imageBubble.setVisibility(View.VISIBLE);
                    holder.imageView.setVisibility(View.VISIBLE);

                    applyRoundedCorners(holder.imageView, 20);
                }
            }
        } else {
            String withoutNick = raw.replaceFirst("<strong>.*?</strong>:\\s*", "");
            Spanned span = Html.fromHtml(withoutNick, Html.FROM_HTML_MODE_LEGACY);
            holder.textView.setText(span);
            holder.textView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    private String extractTagContent(String src, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">");
        Matcher m = p.matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private void playAudio(Context ctx, String b64, Button btn) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            File tmp = File.createTempFile("audio_play", ".3gp", ctx.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tmp.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            btn.setText("⏸ Playing...");
            mediaPlayer.setOnCompletionListener(mp -> {
                btn.setText("▶️ Play Audio");
                stopAudio();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                btn.setText("⛔ Error");
                stopAudio();
                return true;
            });

        } catch (IOException e) {
            btn.setText("⛔ Error");
            e.printStackTrace();
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void applyRoundedCorners(ImageView imageView, int radiusPx) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            imageView.setClipToOutline(true);
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
        }
    }
}
