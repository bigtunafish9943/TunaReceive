package com.limelight.screensaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Game covers from TunaReceive's picture cache, drifting upward in a few slow columns at
 * different speeds, behind the time of day. Drawn at the display's pace; nothing moves fast, so
 * nothing stays in one place long enough to mark the screen.
 */
public class CoverDriftView extends View implements Choreographer.FrameCallback {
    private static final int MAX_COVERS = 24;
    private static final float SPEED_DP_PER_S = 14f;

    private static final class Tile {
        Bitmap bitmap;
        float x, y, width, height, speed, alpha;
    }

    private final List<Bitmap> covers = new ArrayList<>();
    private final List<Tile> tiles = new ArrayList<>();
    private final Random random = new Random();
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private long lastFrameNs;
    private int nextCover;
    private boolean running;

    public CoverDriftView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.rgb(4, 10, 22));
        clockPaint.setColor(Color.argb(235, 255, 255, 255));
        clockPaint.setTextSize(72 * density);
        clockPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        clockPaint.setShadowLayer(12 * density, 0, 0, Color.BLACK);
        datePaint.setColor(Color.argb(200, 200, 220, 235));
        datePaint.setTextSize(22 * density);
        datePaint.setShadowLayer(8 * density, 0, 0, Color.BLACK);
        loadCovers(context);
    }

    private void loadCovers(Context context) {
        List<File> files = new ArrayList<>();
        File[] pcs = new File(context.getCacheDir(), "boxart").listFiles();
        if (pcs != null) {
            for (File pc : pcs) {
                File[] pictures = pc.listFiles((dir, name) -> name.endsWith(".png"));
                if (pictures != null) {
                    Collections.addAll(files, pictures);
                }
            }
        }
        Collections.shuffle(files, random);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        for (File file : files) {
            if (covers.size() >= MAX_COVERS) {
                break;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            // The plain box TunaStream sends for apps without a picture isn't worth showing
            if (bitmap != null && bitmap.getHeight() > bitmap.getWidth() && bitmap.getWidth() >= 150) {
                covers.add(bitmap);
            } else if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    public void start() {
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        for (Bitmap b : covers) {
            b.recycle();
        }
        covers.clear();
        tiles.clear();
    }

    private void fillColumns() {
        if (covers.isEmpty() || getWidth() == 0) {
            return;
        }
        int columns = Math.max(3, getWidth() / (int) (230 * density));
        float columnWidth = getWidth() / (float) columns;
        while (tiles.size() < columns * 2) {
            int column = tiles.size() % columns;
            Tile t = new Tile();
            t.bitmap = covers.get(nextCover++ % covers.size());
            // Nearer columns are bigger, brighter and faster, for a little depth
            float depth = 0.55f + random.nextFloat() * 0.45f;
            t.width = columnWidth * 0.72f * depth;
            t.height = t.width * t.bitmap.getHeight() / t.bitmap.getWidth();
            t.x = column * columnWidth + (columnWidth - t.width) / 2;
            t.y = getHeight() + random.nextFloat() * getHeight() * (tiles.size() < columns ? 0.2f : 1.4f);
            t.speed = SPEED_DP_PER_S * density * depth;
            t.alpha = 0.35f + 0.55f * depth;
            tiles.add(t);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        float dt = lastFrameNs == 0 ? 0 : (frameTimeNanos - lastFrameNs) / 1e9f;
        lastFrameNs = frameTimeNanos;
        fillColumns();
        for (int i = tiles.size() - 1; i >= 0; i--) {
            Tile t = tiles.get(i);
            t.y -= t.speed * Math.min(dt, 0.1f);
            if (t.y + t.height < 0) {
                tiles.remove(i);
            }
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Tile t : tiles) {
            rect.set(t.x, t.y, t.x + t.width, t.y + t.height);
            coverPaint.setAlpha((int) (255 * t.alpha));
            canvas.drawBitmap(t.bitmap, null, rect, coverPaint);
        }
        // The time drifts a little too, a few pixels a minute
        long minute = System.currentTimeMillis() / 60000;
        float offsetX = (float) Math.sin(minute * 0.7) * 40 * density;
        float offsetY = (float) Math.cos(minute * 0.5) * 24 * density;
        String time = DateFormat.getTimeFormat(getContext()).format(new Date());
        String date = DateFormat.format("EEEE d MMMM", new Date()).toString();
        float x = 64 * density + offsetX + 40 * density;
        float y = getHeight() - 96 * density + offsetY;
        canvas.drawText(time, x, y, clockPaint);
        canvas.drawText(date, x + 4 * density, y + 36 * density, datePaint);
    }
}
