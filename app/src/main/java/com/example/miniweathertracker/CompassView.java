// CompassView.java
package com.example.miniweathertracker;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Compass dial with glow, ticks, N/E/S/W markers and red/blue needle.
 * Logic/APIs unchanged: call setHeading(degTrue) and setWindDirection(degFrom).
 *
 * NOTE: Make sure only one file named CompassView exists in this package.
 */
public class CompassView extends View {

    // public API (used by activity)
    private float headingDeg = 0f;   // TRUE heading (to)
    private int windFromDeg = 0;     // meteorological "from"

    public void setHeading(float degTrue) {
        headingDeg = (degTrue % 360f + 360f) % 360f;
        invalidate();
    }

    public void setWindDirection(int degFrom) {
        windFromDeg = ((degFrom % 360) + 360) % 360;
        invalidate();
    }

    // ---- paints (preallocated) ----
    private final Paint pDial = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNeedleRed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNeedleBlue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pWind = new Paint(Paint.ANTI_ALIAS_FLAG);

    // geometry (re-used)
    private final RectF oval = new RectF();
    private final Path needleRed = new Path();
    private final Path needleBlue = new Path();
    private final Path windArrow = new Path();

    // cached size
    private float cx, cy, rDial;

    public CompassView(Context c) {
        super(c);
        init();
    }

    public CompassView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        init();
    }

    public CompassView(Context c, @Nullable AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        // dial ring
        pDial.setStyle(Paint.Style.STROKE);
        pDial.setStrokeWidth(dp(3));
        pDial.setColor(Color.WHITE);

        // outer glow (soft)
        pGlow.setStyle(Paint.Style.STROKE);
        pGlow.setStrokeWidth(dp(18));
        pGlow.setColor(Color.WHITE);
        pGlow.setMaskFilter(new BlurMaskFilter(dp(14), BlurMaskFilter.Blur.OUTER));

        // tick marks
        pTick.setStyle(Paint.Style.STROKE);
        pTick.setStrokeCap(Paint.Cap.ROUND);
        pTick.setColor(Color.WHITE);

        // cardinal text
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setFakeBoldText(true);
        pText.setColor(Color.WHITE);

        // needles
        pNeedleRed.setStyle(Paint.Style.FILL);
        pNeedleRed.setColor(Color.parseColor("#E53935")); // red
        pNeedleRed.setShadowLayer(dp(5), 0, dp(1), 0x66000000);

        pNeedleBlue.setStyle(Paint.Style.FILL);
        pNeedleBlue.setColor(Color.parseColor("#1976D2")); // blue
        pNeedleBlue.setShadowLayer(dp(5), 0, dp(1), 0x66000000);

        // hub
        pHub.setStyle(Paint.Style.FILL);
        pHub.setColor(Color.WHITE);
        pHub.setShadowLayer(dp(6), 0, dp(2), 0x55000000);

        // wind pointer (thin)
        pWind.setStyle(Paint.Style.STROKE);
        pWind.setColor(Color.WHITE);
        pWind.setStrokeWidth(dp(2));
        pWind.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(6)}, 0));
        pWind.setAlpha(190);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        cx = w / 2f;
        cy = h / 2f;
        rDial = Math.min(w, h) * 0.45f;

        // gradient ring for subtle vignette
        pDial.setShader(new SweepGradient(
                cx,
                cy,
                new int[]{0x80FFFFFF, 0xFFFFFFFF, 0x80FFFFFF, 0x80FFFFFF},
                new float[]{0f, .25f, .5f, 1f}
        ));

        oval.set(cx - rDial, cy - rDial, cx + rDial, cy + rDial);

        buildNeedlePaths();
    }

    private void buildNeedlePaths() {
        float r = rDial * 0.88f;
        float base = r * 0.16f;
        float half = dp(3.2f);

        // North (red) triangle pointing outward
        needleRed.reset();
        needleRed.moveTo(0, -r);     // tip
        needleRed.lineTo(base, 0);
        needleRed.lineTo(-base, 0);
        needleRed.close();

        // South (blue) triangle pointing inward
        needleBlue.reset();
        needleBlue.moveTo(0, r * 0.75f); // tip towards south
        needleBlue.lineTo(-base, 0);
        needleBlue.lineTo(base, 0);
        needleBlue.close();

        // Wind arrow path (simple triangle at rim)
        float wa = r * 0.92f;
        windArrow.reset();
        windArrow.moveTo(0, -wa);
        windArrow.lineTo(-half * 1.2f, -(wa - dp(10)));
        windArrow.lineTo(half * 1.2f, -(wa - dp(10)));
        windArrow.close();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        // glow first
        c.drawOval(oval, pGlow);

        // dial circle
        c.drawOval(oval, pDial);

        drawTicks(c);
        drawCardinals(c);
        drawNeedle(c);
        drawWindFrom(c);
        drawHub(c);
    }

    private void drawTicks(Canvas c) {
        int longEvery = 15; // long tick every 15°
        int minorEvery = 5; // short tick every 5°

        float rOuter = rDial;
        float rLong = rDial * 0.92f;
        float rShort = rDial * 0.96f;

        for (int a = 0; a < 360; a += minorEvery) {
            boolean isLong = (a % longEvery == 0);
            pTick.setStrokeWidth(isLong ? dp(2.2f) : dp(1.2f));

            double rad = Math.toRadians(a);
            float sx = cx + (float) (Math.sin(rad) * (isLong ? rLong : rShort));
            float sy = cy - (float) (Math.cos(rad) * (isLong ? rLong : rShort));
            float ex = cx + (float) (Math.sin(rad) * rOuter);
            float ey = cy - (float) (Math.cos(rad) * rOuter);
            c.drawLine(sx, sy, ex, ey, pTick);
        }
    }

    private void drawCardinals(Canvas c) {
        pText.setTextSize(rDial * 0.16f);
        String[] labels = {"N", "E", "S", "W"};
        int[] angles = {0, 90, 180, 270};

        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(angles[i]);
            float r = rDial * 0.74f;
            float tx = cx + (float) (Math.sin(rad) * r);
            float ty = cy - (float) (Math.cos(rad) * r) + pText.getTextSize() / 3f;

            // small glow behind text for legibility
            pText.setShadowLayer(dp(5), 0, 0, 0x55000000);
            c.drawText(labels[i], tx, ty, pText);
        }
        pText.clearShadowLayer();
    }

    private void drawNeedle(Canvas c) {
        c.save();
        c.translate(cx, cy);
        c.rotate(headingDeg);
        c.drawPath(needleRed, pNeedleRed);
        c.drawPath(needleBlue, pNeedleBlue);
        c.restore();
    }

    private void drawWindFrom(Canvas c) {
        // dashed arrow showing the "from" direction along the rim
        c.save();
        c.translate(cx, cy);
        c.rotate(windFromDeg);
        c.drawPath(windArrow, pWind);
        c.restore();
    }

    private void drawHub(Canvas c) {
        // white circle with subtle shadow
        c.drawCircle(cx, cy, dp(6.5f), pHub);
        // tiny dot
        Paint dot = pHub;
        dot.setColor(0xFFCCCCCC);
        c.drawCircle(cx, cy, dp(2.3f), dot);
        dot.setColor(Color.WHITE);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
