package com.lonerxone.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/**
 * Test helper for the emulator run: posts notifications that look like real
 * apps' (a chat, a download, music, a one-time code) so Halo reacts to them.
 *   adb shell am broadcast -n com.lonerxone.probe/.Probe --es cmd msg
 */
public class Probe extends BroadcastReceiver {
    private static MediaSession session;

    @Override
    public void onReceive(Context c, Intent in) {
        String cmd = in.getStringExtra("cmd");
        if (cmd == null) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel("probe") == null) {
            nm.createNotificationChannel(new NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_HIGH));
            nm.createNotificationChannel(new NotificationChannel("quiet", "Probe quiet", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent noop = PendingIntent.getBroadcast(c, 0, new Intent(c, Probe.class).putExtra("cmd", "noop"), PendingIntent.FLAG_IMMUTABLE);
        long now = System.currentTimeMillis();
        switch (cmd) {
            case "msg": {
                String who = in.getStringExtra("who");
                if (who == null) who = "Tolu";
                String text = in.getStringExtra("text");
                if (text == null) text = "Are you coming to the hall tonight?";
                Person p = new Person.Builder().setName(who).setKey(who)
                        .setIcon(Icon.createWithBitmap(face(who))).build();
                Person me = new Person.Builder().setName("Me").build();
                Notification.MessagingStyle st = new Notification.MessagingStyle(me).addMessage(text, now, p);
                PendingIntent reply = PendingIntent.getBroadcast(c, 1, new Intent(c, Probe.class).putExtra("cmd", "noop"), PendingIntent.FLAG_MUTABLE);
                Notification.Action a = new Notification.Action.Builder(null, "Reply", reply)
                        .addRemoteInput(new RemoteInput.Builder("reply").setLabel("Reply").build()).build();
                nm.notify(who.hashCode(), new Notification.Builder(c, "probe")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setStyle(st).setContentTitle(who).setContentText(text)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .setContentIntent(noop).addAction(a).setWhen(now).build());
                break;
            }
            case "dl": {
                int pct = in.getIntExtra("pct", 30);
                Notification.Action pause = new Notification.Action.Builder(null, "Pause", noop).build();
                Notification.Action cancel = new Notification.Action.Builder(null, "Cancel", noop).build();
                nm.notify(2, new Notification.Builder(c, "quiet")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("holiday-video.mp4")
                        .setContentText((pct * 80 / 100) + " MB of 80 MB · 2.4 MB/s · 20 s left")
                        .setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .addAction(pause).addAction(cancel).setContentIntent(noop).build());
                break;
            }
            case "dldone":
                nm.notify(2, new Notification.Builder(c, "quiet")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("holiday-video.mp4").setContentText("Download complete")
                        .setContentIntent(noop).setAutoCancel(true).build());
                break;
            case "music": {
                if (session == null) session = new MediaSession(c, "probe");
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Midnight Drive")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "Night Tapes")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, 215_000L)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art()).build());
                session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 42_000L, 1f)
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                        .build());
                session.setActive(true);
                nm.notify(3, new Notification.Builder(c, "quiet")
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle("Midnight Drive").setContentText("Test Artist")
                        .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                        .setOngoing(true).setContentIntent(noop).build());
                break;
            }
            case "otp":
                nm.notify(4, new Notification.Builder(c, "probe")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle("Probe Bank")
                        .setContentText("Your verification code is 482913. Don't share it with anyone.")
                        .setContentIntent(noop).setWhen(now).build());
                break;
            case "clear":
                nm.cancelAll();
                if (session != null) {
                    session.setActive(false);
                    session.release();
                    session = null;
                }
                break;
            default:
                break;
        }
    }

    private static Bitmap face(String who) {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.HSVToColor(new float[]{(who.hashCode() & 0xff) * 1.4f, 0.55f, 0.85f}));
        cv.drawCircle(64, 64, 64, p);
        p.setColor(Color.WHITE);
        p.setTextSize(64);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        cv.drawText(who.substring(0, 1), 64, 86, p);
        return b;
    }

    private static Bitmap art() {
        Bitmap b = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 320, 320, 0xFFFF2D55, 0xFF5856D6, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 320, 320, p);
        return b;
    }
}
