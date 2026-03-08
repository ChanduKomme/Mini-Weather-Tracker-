// ForecastGraphView.java
package com.example.miniweathertracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/** Weekly cards graph used by ForecastGraphActivity. */
public class ForecastGraphView extends View {

    // legacy line support (optional)
    private ForecastItem[] legacy;
    // new card data
    private ForecastCard[] cards;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public ForecastGraphView(Context c) {
        super(c);
        init();
    }

    public ForecastGraphView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public ForecastGraphView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        t.setColor(Color.DKGRAY);
        t.setTextSize(30f);
    }

    /** Old API (kept so nothing else breaks). */
    public void setData(ForecastItem[] items) {
        this.legacy = items;
        this.cards = null;
        invalidate();
    }

    /** New API used by the weekly screen. */
    public void setCards(ForecastCard[] cards) {
        this.cards = cards;
        this.legacy = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        if (cards == null && legacy != null) {
            drawLegacyLine(c);
            return;
        }
        if (cards == null || cards.length == 0) {
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText("No data", getWidth() / 2f, getHeight() / 2f, t);
            return;
        }

        float W = getWidth(), H = getHeight();
        float pad = 16f;
        float cardGap = 12f;

        float gMin = Float.MAX_VALUE, gMax = -Float.MAX_VALUE;
        for (ForecastCard d : cards) {
            if (d.tMin < gMin) gMin = d.tMin;
            if (d.tMax > gMax) gMax = d.tMax;
        }
        if (gMin == gMax) {
            gMin -= 1;
            gMax += 1;
        }

        int n = cards.length;
        float usableW = W - pad * 2 - cardGap * (n - 1);
        float cardW = usableW / n;

        // vertical zones
        float topZone = 46f;     // date chip area (unchanged)
        float iconZone = 48f;    // weather icon line
        float percentZone = 26f; // precip %
        float barTop = pad + topZone + iconZone + percentZone + 6f;

        // Bars extend to the very bottom
        float barBottom = H - pad;
        float barH = Math.max(100f, barBottom - barTop);
        barBottom = barTop + barH;

        for (int i = 0; i < n; i++) {
            float x = pad + i * (cardW + cardGap);
            drawDayCard(
                    c,
                    cards[i],
                    x,
                    pad,
                    cardW,
                    topZone,
                    iconZone,
                    percentZone,
                    barTop,
                    barBottom,
                    gMin,
                    gMax
            );
        }
    }

    private void drawDayCard(Canvas c,
                             ForecastCard d,
                             float x,
                             float padTop,
                             float cardW,
                             float topZone,
                             float iconZone,
                             float percentZone,
                             float barTop,
                             float barBottom,
                             float gMin,
                             float gMax) {

        float centerX = x + cardW / 2f;

        // Date chip (unchanged)
        float r = 16f;
        p.setColor(Color.parseColor("#EFEFFD"));
        c.drawCircle(centerX - cardW / 2f + 18f, padTop + 18f, r, p);
        t.setColor(Color.parseColor("#4C4CFF"));
        t.setTextSize(22f);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(d.dayNum), centerX - cardW / 2f + 18f, padTop + 26f, t);

        // Icon baseline
        float iconY = padTop + topZone - 8f;

        // ▼ Week name ABOVE the icon, moved slightly more to the right to avoid clipping
        t.setColor(Color.DKGRAY);
        t.setTextSize(28f);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(d.dow, centerX + 14f, iconY - 10f, t); // ← only change from previous (+8f → +14f)

        // Weather icon (unchanged)
        drawIcon(c, centerX, iconY, d.wcode);

        // Precip % (unchanged)
        t.setColor(Color.DKGRAY);
        t.setTextSize(22f);
        String pp = (d.precip >= 0 ? d.precip + "%" : "–");
        c.drawText(pp, centerX, iconY + 26f, t);

        // Thermometer track (unchanged)
        float trackW = Math.min(30f, cardW * 0.32f);
        RectF track = new RectF(centerX - trackW / 2f, barTop, centerX + trackW / 2f, barBottom);
        p.setColor(Color.parseColor("#E6E6E6"));
        c.drawRoundRect(track, trackW / 2f, trackW / 2f, p);

        float yMax = mapTemp(d.tMax, gMin, gMax, barBottom, barTop);
        RectF fill = new RectF(track.left + 4f, yMax, track.right - 4f, barBottom - 4f);

        // Grey inside fill for better contrast
        p.setColor(Color.parseColor("#BDBDBD"));
        c.drawRoundRect(fill, (trackW / 2f) - 4f, (trackW / 2f) - 4f, p);

        // Temperature labels (unchanged)
        final float LABEL_SIZE = 34f;
        t.setColor(Color.BLACK);
        t.setTextSize(LABEL_SIZE);
        c.drawText(Math.round(d.tMax) + "°", centerX, yMax - 8f, t);
        c.drawText(Math.round(d.tMin) + "°", centerX, barBottom - 6f, t);
    }

    private float mapTemp(float v, float tMin, float tMax, float yBottom, float yTop) {
        float n = (v - tMin) / (tMax - tMin);
        n = Math.max(0f, Math.min(1f, n));
        return yBottom - n * (yBottom - yTop);
    }

    private void drawIcon(Canvas c, float cx, float cy, int code) {
        boolean isRain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean isCloud = (code == 1 || code == 2 || code == 3 || (code >= 45 && code <= 48) || isRain);
        boolean isClear = (code == 0);

        if (isClear || isCloud) {
            p.setColor(Color.YELLOW);
            c.drawCircle(cx - 18f, cy - 6f, 10f, p);
        }
        if (isCloud) {
            p.setColor(Color.WHITE);
            c.drawOval(cx - 10f, cy - 10f, cx + 22f, cy + 6f, p);
            c.drawOval(cx - 26f, cy - 6f, cx + 6f, cy + 10f, p);
        }
        if (isRain) {
            p.setColor(Color.parseColor("#66CCFF"));
            for (int i = -8; i <= 8; i += 6) {
                c.drawLine(cx + i, cy + 10f, cx + i - 4f, cy + 20f, p);
            }
        }
    }

    private void drawLegacyLine(Canvas c) {
        float w = getWidth(), h = getHeight();
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText("Legacy graph", w / 2f, h / 2f, t);
    }
}
