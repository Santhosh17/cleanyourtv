package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

/** Draws "°C" (or "°F") as the degree sign with the unit letter small just under it, as one glyph. */
final class DegreeSpan extends ReplacementSpan {
    /** The unit letter's size, of the text's. */
    private final float LETTER;

    DegreeSpan(float letter) {
        LETTER = letter;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) paint.getFontMetricsInt(fm);
        float degree = paint.measureText("°");
        float size = paint.getTextSize();
        paint.setTextSize(size * LETTER);
        float letter = paint.measureText(text, start + 1, end);
        paint.setTextSize(size);
        return Math.round(Math.max(degree, letter) + 0.04f * size);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     Paint paint) {
        float size = paint.getTextSize();
        float degree = paint.measureText("°");
        paint.setTextSize(size * LETTER);
        float letter = paint.measureText(text, start + 1, end);
        paint.setTextSize(size);
        float w = Math.max(degree, letter);
        canvas.drawText("°", x + (w - degree) / 2, y, paint);
        paint.setTextSize(size * LETTER);
        // the degree sign ends about 0.4 of the size above the baseline; the letter sits just under it
        canvas.drawText(text, start + 1, end, x + (w - letter) / 2, y - 0.12f * size, paint);
        paint.setTextSize(size);
    }
}
