package com.example.miniweathertracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

public class WeatherSceneView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean isDay = true;
    private String condition = "Sunny";

    public WeatherSceneView(Context c) { super(c); }
    public WeatherSceneView(Context c, AttributeSet a) { super(c, a); }
    public WeatherSceneView(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setScene(String condition, boolean isDay) {
        this.condition = condition == null ? "Sunny" : condition;
        this.isDay = isDay;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();

        // sky
        p.setStyle(Paint.Style.FILL);
        p.setColor(isDay ? Color.parseColor("#87CEEB") : Color.parseColor("#0B1D39"));
        c.drawRect(0, 0, w, h, p);

        // sun or moon
        if (isDay) {
            p.setColor(Color.YELLOW);
            c.drawCircle((int)(w*0.15f), (int)(h*0.25f), (int)(h*0.12f), p);
        } else {
            p.setColor(Color.LTGRAY);
            c.drawCircle((int)(w*0.15f), (int)(h*0.25f), (int)(h*0.10f), p);
            p.setColor(Color.parseColor("#0B1D39"));
            c.drawCircle((int)(w*0.18f), (int)(h*0.23f), (int)(h*0.10f), p);
        }

        // ground
        p.setColor(isDay ? Color.parseColor("#5DBB63") : Color.parseColor("#174B2E"));
        c.drawRect(0, (int)(h*0.78f), w, h, p);

        // clouds / rain (very simple)
        if ("Cloudy".equalsIgnoreCase(condition) || "Rain".equalsIgnoreCase(condition)) {
            p.setColor(Color.WHITE);
            c.drawOval(w*0.50f, h*0.18f, w*0.72f, h*0.30f, p);
            c.drawOval(w*0.62f, h*0.15f, w*0.85f, h*0.28f, p);
        }
        if ("Rain".equalsIgnoreCase(condition)) {
            p.setColor(Color.CYAN);
            for (int i=0;i<12;i++) {
                float x = w*0.55f + i* (w*0.02f);
                c.drawLine(x, h*0.30f, x-8, h*0.36f, p);
            }
        }
    }
}
