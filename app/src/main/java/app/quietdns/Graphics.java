package app.quietdns;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.view.View;

final class Graphics {
    static final int PAPER = 0xfff6f7f1, SURFACE = 0xfffcfdf8, INK = 0xff1b3027,
            MUTED = 0xff54685c, LINE = 0xffd9dfd3, GREEN = 0xff276547,
            FOREST = 0xff153e32, LIME = 0xffcee9a8, TINT = 0xffe5efd9;

    @SuppressLint("ViewConstructor") // Constructed in code with an explicit glyph.
    static final class Icon extends View {
        private final String name;
        private final int color, size;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(Context context, String name, int color, int size) {
            super(context);
            this.name = name; this.color = color; this.size = size;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private void line(Canvas c, float... points) {
            path.rewind();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            c.drawPath(path, p);
        }
        private void shield(Canvas c) {
            Path s = path; s.rewind();
            s.moveTo(12, 2); s.lineTo(20, 5.2f); s.lineTo(20, 10.5f);
            s.cubicTo(20, 15.7f, 16.4f, 19.7f, 12, 22);
            s.cubicTo(7.6f, 19.7f, 4, 15.7f, 4, 10.5f); s.lineTo(4, 5.2f); s.close();
            c.drawPath(s, p);
        }
        @Override protected void onDraw(Canvas c) {
            float dim = Math.min(Math.min(getWidth(), getHeight()), size * getResources().getDisplayMetrics().density);
            c.save(); c.translate((getWidth() - dim) / 2, (getHeight() - dim) / 2); c.scale(dim / 24, dim / 24);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f); p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND); p.setColor(color);
            switch (name) {
                case "brand":
                    shield(c);
                    for (int y = 10; y <= 14; y += 4) {
                        Path wave = path; wave.rewind(); wave.moveTo(8, y + 1); wave.quadTo(12, y - 2, 16, y); c.drawPath(wave, p);
                    }
                    break;
                case "shield": shield(c); line(c, 8.7f, 11.5f, 11.3f, 14, 15.8f, 9.3f); break;
                case "shield-off": shield(c); line(c, 8.5f, 11.8f, 15.5f, 11.8f); break;
                case "home": line(c, 3, 10, 12, 3, 21, 10); line(c, 5, 9, 5, 20, 10, 20, 10, 14, 14, 14, 14, 20, 19, 20, 19, 9); break;
                case "rules":
                    line(c, 3, 7, 10, 7); line(c, 15, 7, 21, 7); c.drawCircle(12.5f, 7, 2.5f, p);
                    line(c, 3, 17, 6, 17); line(c, 11, 17, 21, 17); c.drawCircle(8.5f, 17, 2.5f, p); break;
                case "activity": line(c, 2.5f, 12, 7, 12, 10, 5, 14, 19, 17, 12, 21.5f, 12); break;
                case "layers": line(c, 2.5f, 8, 12, 3.5f, 21.5f, 8, 12, 12.8f, 2.5f, 8);
                    line(c, 3, 12, 12, 16.5f, 21, 12); line(c, 3, 16, 12, 20.5f, 21, 16); break;
                case "info": c.drawCircle(12, 12, 9, p); line(c, 12, 11, 12, 16); p.setStyle(Paint.Style.FILL); c.drawCircle(12, 7.7f, 1, p); break;
                case "power": c.drawArc(4.5f, 4.5f, 19.5f, 19.5f, -50, 280, false, p); line(c, 12, 2.5f, 12, 11); break;
                case "pause": line(c, 9, 5, 9, 19); line(c, 15, 5, 15, 19); break;
                case "right": line(c, 9, 5, 16, 12, 9, 19); break;
                case "back": line(c, 14, 5, 7, 12, 14, 19); break;
                case "check": line(c, 4, 12, 9, 17, 20, 6); break;
                case "ban": c.drawCircle(12, 12, 8, p); line(c, 6.3f, 6.3f, 17.7f, 17.7f); break;
                case "plus": line(c, 12, 4, 12, 20); line(c, 4, 12, 20, 12); break;
                case "delete": line(c, 4, 6, 20, 6); line(c, 8, 6, 9, 3, 15, 3, 16, 6);
                    line(c, 6, 6, 7, 20, 17, 20, 18, 6); line(c, 10, 10, 10, 16); line(c, 14, 10, 14, 16); break;
                case "refresh": c.drawArc(4, 4, 20, 20, 22, 286, false, p); line(c, 17, 3.5f, 17, 8.5f, 12, 8.5f); break;
                case "clock": c.drawCircle(12, 12, 8.5f, p); line(c, 12, 7, 12, 12, 15.5f, 14); break;
                case "search": c.drawCircle(10.5f, 10.5f, 6.3f, p); line(c, 15.5f, 15.5f, 20.5f, 20.5f); break;
            }
            c.restore();
        }
    }

    // Contours follow the shield silhouette and are clipped by the hero card.
    @SuppressLint("ViewConstructor") // Constructed in code with the current protection state.
    static final class Contours extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean on;
        private LinearGradient fade;
        @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
            if (w > 0) fade = new LinearGradient(0, 0, w * .73f, 0,
                    new int[]{FOREST, FOREST, 0x00153e32}, new float[]{0, .47f, 1}, Shader.TileMode.CLAMP);
        }
        Contours(Context c, boolean on) { super(c); this.on = on; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        private Path contour(float k) {
            path.rewind();
            path.moveTo(0, -36*k);
            path.cubicTo(9*k, -31*k, 22*k, -26*k, 30*k, -25*k);
            path.cubicTo(32*k, 2*k, 27*k, 18*k, 0, 35*k);
            path.cubicTo(-27*k, 18*k, -32*k, 2*k, -30*k, -25*k);
            path.cubicTo(-20*k, -27*k, -9*k, -31*k, 0, -36*k);
            path.close(); return path;
        }
        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            c.save(); c.translate(getWidth() * .805f, getHeight() * .49f);
            c.scale(d, d); c.rotate(-12);
            for (int i = 15; i > 1; i--) {
                float scale = 1 + i * .19f;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(i % 4 == 0 ? 1.1f : .7f);
                p.setColor(Color.argb(Math.max(15, 95 - i * 4), 151, 192, 146));
                c.drawPath(contour(scale), p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xff42674a : 0xff2e5541);
            c.drawPath(contour(1.18f), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f);
            p.setColor(on ? 0xffd1efa6 : 0xffabd297); c.drawPath(contour(1.18f), p);
            p.setStrokeWidth(1); p.setColor(0xff719e73); c.drawPath(contour(.97f), p);
            p.setStrokeWidth(2.7f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(on ? LIME : 0xffa6cb92);
            Path mark = path; mark.rewind();
            if (on) { mark.moveTo(-12, -2); mark.lineTo(-3, 7); mark.lineTo(13, -11); }
            else { mark.moveTo(-5,-10); mark.lineTo(-5,6); mark.moveTo(5,-10); mark.lineTo(5,6); }
            c.drawPath(mark, p); c.restore();

            // Fade linework behind the status and keep the type legible.
            p.setStyle(Paint.Style.FILL);
            p.setShader(fade);
            c.drawRect(0,0,getWidth()*.73f,getHeight(),p); p.setShader(null);
        }
    }
    private Graphics() {}
}
