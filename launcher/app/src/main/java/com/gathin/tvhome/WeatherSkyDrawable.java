package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.Random;

/**
 * A weather card's background in the Apple Weather manner: a gradient for the city's weather and day or night, and,
 * when animated, a light effect over it (a sun glow, twinkling stars, drifting clouds, rain, snow, lightning, mist).
 * Plain shapes only, 15 frames a second, and only between start() and stop(), which MainActivity calls while
 * the cards are on screen.
 */
final class WeatherSkyDrawable extends Drawable implements Animatable {
    private static final long FRAME_MS = 67;   // 15 frames a second: enough for slow clouds and rain, half the CPU of 24
    private static final int CLEAR = 0, CLOUDY = 1, RAIN = 2, SNOW = 3, STORM = 4, FOG = 5;

    private final int[] colours;
    private final int kind, clouds;
    private final boolean day, drizzle, animate;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Random random = new Random();
    /** Built once per size: the sky, the sun glow and a mist band (moved with a matrix, faded with alpha). */
    private Shader sky, glow, mist;
    private final android.graphics.Matrix move = new android.graphics.Matrix();
    /** Particles: x, y (px), speed, size, phase. */
    private float[][] parts = new float[0][];
    private long last, nextFlash, flashAt;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidateSelf();
            if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
        }
    };

    WeatherSkyDrawable(int code, boolean day, float density, boolean animate) {
        this.day = day;
        this.density = density;
        this.animate = animate;
        drizzle = code >= 51 && code <= 57;
        if (code >= 95) kind = STORM;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) kind = SNOW;
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) kind = RAIN;
        else if (code == 45 || code == 48) kind = FOG;
        else if (code >= 1) kind = CLOUDY;
        else kind = CLEAR;
        clouds = code == 1 ? 1 : code == 2 ? 2 : 3;
        colours = colours(kind, code, day);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x26FFFFFF);
    }

    private static int[] colours(int kind, int code, boolean day) {
        switch (kind) {
            case STORM: return day ? new int[]{0xFF3A3F4A, 0xFF1E2128} : new int[]{0xFF22252C, 0xFF0E1013};
            case SNOW: return day ? new int[]{0xFF8FA3B8, 0xFF5F7287} : new int[]{0xFF3A4656, 0xFF1C2430};
            case RAIN: return day ? new int[]{0xFF4E5D6E, 0xFF2F3A47} : new int[]{0xFF222A35, 0xFF10151B};
            case FOG: return day ? new int[]{0xFF8A9099, 0xFF5E646C} : new int[]{0xFF3A3E45, 0xFF1D1F23};
            default:
                if (code == 3) return day ? new int[]{0xFF6B7C8F, 0xFF4A5868} : new int[]{0xFF2B323D, 0xFF15191F};
                if (code == 2) return day ? new int[]{0xFF5B8FC7, 0xFF3B6796} : new int[]{0xFF1E2A47, 0xFF0C1428};
                return day ? new int[]{0xFF3A8DDE, 0xFF1F5FA8} : new int[]{0xFF0E1A3A, 0xFF050A1C};
        }
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        box.inset(density / 2, density / 2);
        clip.reset();
        clip.addRoundRect(box, 22 * density, 22 * density, Path.Direction.CW);
        sky = new LinearGradient(0, b.top, 0, b.bottom, colours[0], colours[1], Shader.TileMode.CLAMP);
        glow = new RadialGradient(b.right - 0.12f * b.width(), b.top + 0.1f * b.height(), 0.6f * b.height(),
                0x55FFF2B0, 0x00FFF2B0, Shader.TileMode.CLAMP);
        mist = new LinearGradient(0, 0, b.width(), 0, new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF}, null,
                Shader.TileMode.CLAMP);
        int n = kind == RAIN ? (drizzle ? 24 : 40) : kind == STORM ? 46 : kind == SNOW ? 30
                : kind == CLEAR && !day ? 18 : kind == CLOUDY && !day ? 12 : 0;
        parts = new float[n][];
        for (int i = 0; i < n; i++) parts[i] = new float[]{random.nextFloat() * b.width(),
                random.nextFloat() * b.height(), 0.6f + random.nextFloat() * 0.8f, random.nextFloat(), random.nextFloat() * 6.3f};
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
        last = now;
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);   // opaque: the last frame's effect colours must not fade the sky
        paint.setShader(sky);
        canvas.drawRect(b, paint);
        paint.setShader(null);
        if (animate) effect(canvas, b, now / 1000f, dt);
        canvas.restore();
        canvas.drawPath(clip, edge);
    }

    private void effect(Canvas c, Rect b, float t, float dt) {
        float w = b.width(), h = b.height();
        boolean starry = !day && (kind == CLEAR || kind == CLOUDY);
        if (kind == CLEAR && day || kind == CLOUDY && day && clouds < 3) {
            // sun glow in the top-right corner, breathing over 6 s
            paint.setShader(glow);
            paint.setAlpha((int) (200 + 55 * Math.sin(t * 1.05)));
            c.drawCircle(b.right - 0.12f * w, b.top + 0.1f * h, 0.6f * h, paint);
            paint.setShader(null);
            paint.setAlpha(255);
        }
        if (starry) {
            for (float[] p : parts) {
                int a = (int) (60 + 150 * Math.abs(Math.sin(t * (0.6 + p[2]) + p[4])));
                paint.setColor((a << 24) | 0xFFFFFF);
                c.drawCircle(b.left + p[0], b.top + p[1] * 0.7f, (0.8f + p[3]) * density, paint);
            }
        }
        if (kind == CLOUDY || kind == RAIN || kind == STORM) {
            int n = kind == CLOUDY ? clouds : 3;
            int alpha = kind == CLOUDY ? (day ? 0x1C : 0x12) : 0x14;
            paint.setColor((alpha << 24) | 0xFFFFFF);
            for (int i = 0; i < n; i++) {
                float span = w + 0.3f * w;   // small puffs in the top part, drifting across in 40-60 s
                float x = b.left - 0.15f * w + ((t * span / (40 + 10 * i)) + i * span / n) % span;
                float y = b.top + (0.12f + 0.09f * i) * h;
                float s = (0.035f + 0.01f * i) * w;
                c.drawCircle(x, y, s, paint);
                c.drawCircle(x + s * 0.9f, y + s * 0.15f, s * 0.8f, paint);
                c.drawCircle(x - s * 0.9f, y + s * 0.2f, s * 0.7f, paint);
            }
        }
        if (kind == RAIN || kind == STORM) {
            paint.setColor(0x59FFFFFF);
            paint.setStrokeWidth(density);
            float len = (drizzle ? 6 : 10) * density;
            for (float[] p : parts) {
                p[1] += dt * h * 1.2f * p[2];
                p[0] -= dt * h * 0.15f * p[2];
                if (p[1] > h) { p[1] = -len; p[0] = random.nextFloat() * (w + 0.2f * w); }
                c.drawLine(b.left + p[0], b.top + p[1], b.left + p[0] - len * 0.15f, b.top + p[1] + len, paint);
            }
        }
        if (kind == SNOW) {
            paint.setColor(0xCCFFFFFF);
            for (float[] p : parts) {
                p[1] += dt * h * 0.16f * p[2];
                if (p[1] > h) { p[1] = -4 * density; p[0] = random.nextFloat() * w; }
                float x = p[0] + (float) Math.sin(t * 0.8 + p[4]) * 6 * density;
                c.drawCircle(b.left + x, b.top + p[1], (1.2f + 1.6f * p[3]) * density, paint);
            }
        }
        if (kind == STORM) {
            long ms = (long) (t * 1000);
            if (nextFlash == 0) nextFlash = ms + 3000 + random.nextInt(6000);
            if (ms >= nextFlash) { flashAt = ms; nextFlash = ms + 6000 + random.nextInt(8000); }
            long since = ms - flashAt;
            if (flashAt != 0 && since < 300) {
                paint.setColor(((int) (0x60 * (1 - since / 300f)) << 24) | 0xFFFFFF);
                c.drawRect(b, paint);
            }
        }
        if (kind == FOG) {
            for (int i = 0; i < 3; i++) {
                float y = b.top + (0.25f + 0.25f * i) * h;
                move.setTranslate(b.left + (float) Math.sin(t * 0.15 + i * 2) * 0.15f * w, 0);
                mist.setLocalMatrix(move);
                paint.setShader(mist);
                c.drawRect(b.left, y - 0.06f * h, b.right, y + 0.06f * h, paint);
                paint.setShader(null);
            }
        }
    }

    @Override public void start() {
        if (!animate || running) return;
        running = true;
        last = 0;
        scheduleSelf(tick, SystemClock.uptimeMillis() + FRAME_MS);
    }

    @Override public void stop() {
        running = false;
        unscheduleSelf(tick);
    }

    @Override public boolean isRunning() {
        return running;
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter filter) { }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
