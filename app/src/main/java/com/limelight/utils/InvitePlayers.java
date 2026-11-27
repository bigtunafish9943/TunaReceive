package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.Collections;

/**
 * Invite players: a QR code on the TV that friends scan with their phones to join the game as
 * controllers (TunaStream's phone controllers). The link works for 12 hours, only as a controller.
 */
public final class InvitePlayers {
    private InvitePlayers() {
    }

    public static void show(final Activity a, final NvHTTP http) {
        if (http == null) {
            Toast.makeText(a, R.string.tuna_invite_failed, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            String url = null;
            int problem = R.string.tuna_invite_failed;
            try {
                JSONObject out = new JSONObject(http.tunaPadInvite());
                url = out.optString("url", null);
                if (url == null && "not allowed".equals(out.optString("error"))) {
                    problem = R.string.tuna_sleep_not_allowed;
                }
            } catch (FileNotFoundException e) {
                problem = R.string.tuna_invite_update;
            } catch (Exception e) {
                LimeLog.warning("Invite players: " + e);
            }
            final String link = url;
            final int why = problem;
            a.runOnUiThread(() -> {
                if (link == null) {
                    Toast.makeText(a, why, Toast.LENGTH_LONG).show();
                } else {
                    showCode(a, link);
                }
            });
        }, "TunaReceive invite").start();
    }

    private static void showCode(Activity a, String link) {
        int size = (int) (300 * a.getResources().getDisplayMetrics().density);
        Bitmap code;
        try {
            BitMatrix m = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 2));
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = m.get(x, y) ? Color.rgb(16, 20, 28) : Color.WHITE;
                }
            }
            code = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            LimeLog.warning("Invite players QR: " + e);
            Toast.makeText(a, R.string.tuna_invite_failed, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        ImageView picture = new ImageView(a);
        picture.setImageBitmap(code);
        box.addView(picture, new LinearLayout.LayoutParams(size, size));
        TextView note = new TextView(a);
        note.setText(R.string.tuna_invite_note);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, pad, 0, 0);
        box.addView(note);
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(R.string.tuna_invite_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }
}
