package uk.ac.babraham.FastQC.Graphs;

/**
 * Pure-Java SVG document generator. Provides drawing primitives that
 * generate SVG XML without any java.awt dependency. Used for GraalVM
 * native-image compatibility where AWT native libraries are unavailable.
 *
 * Font metrics are approximated using character-width ratios since
 * exact pixel measurements aren't needed for SVG layout (the SVG
 * viewer handles actual font rendering).
 */
public class SVGDocument {

    private final StringBuilder sb = new StringBuilder();
    private final int width;
    private final int height;
    private int translateX = 0;
    private int translateY = 0;
    private int red = 0, green = 0, blue = 0;
    private int fontSize = 12;
    private boolean fontBold = false;

    // Average character width as fraction of font size for Arial/sans-serif.
    private static final double CHAR_WIDTH_RATIO = 0.6;
    private static final double ASCENT_RATIO = 0.8;

    public SVGDocument(int width, int height) {
        this.width = width;
        this.height = height;
        sb.append("<?xml version=\"1.0\" standalone=\"no\"?>\n");
        sb.append("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n");
        sb.append("<svg width=\"").append(width).append("\" height=\"").append(height);
        sb.append("\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\">\n");
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setColor(int r, int g, int b) {
        this.red = r;
        this.green = g;
        this.blue = b;
    }

    public void setFontSize(int size, boolean bold) {
        this.fontSize = size;
        this.fontBold = bold;
    }

    public int getFontSize() { return fontSize; }
    public boolean isFontBold() { return fontBold; }

    public int stringWidth(String s) {
        if (s == null) return 0;
        return (int)(s.length() * fontSize * CHAR_WIDTH_RATIO);
    }

    public int fontAscent() {
        return (int)(fontSize * ASCENT_RATIO);
    }

    public void translate(int dx, int dy) {
        this.translateX += dx;
        this.translateY += dy;
    }

    private int cx(int x) {
        int nx = x + translateX;
        if (nx < 0) nx = 0;
        if (nx > width) nx = width;
        return nx;
    }

    private int cy(int y) {
        int ny = y + translateY;
        if (ny < 0) ny = 0;
        if (ny > height) ny = height;
        return ny;
    }

    private void appendColor() {
        sb.append(red).append(",").append(green).append(",").append(blue);
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
        sb.append("<line x1=\"").append(cx(x1));
        sb.append("\" y1=\"").append(cy(y1));
        sb.append("\" x2=\"").append(cx(x2));
        sb.append("\" y2=\"").append(cy(y2));
        sb.append("\" stroke=\"rgb(");
        appendColor();
        sb.append(")\" stroke-width=\"1\"/>\n");
    }

    public void drawRect(int x, int y, int w, int h) {
        x = cx(x); y = cy(y);
        if (x + w > width) w = width - x;
        if (y + h > height) h = height - y;
        sb.append("<rect x=\"").append(x).append("\" y=\"").append(y);
        sb.append("\" width=\"").append(w).append("\" height=\"").append(h);
        sb.append("\" style=\"fill:none;stroke-width:1;stroke:rgb(");
        appendColor();
        sb.append(")\"/>\n");
    }

    public void fillRect(int x, int y, int w, int h) {
        x = cx(x); y = cy(y);
        if (x + w > width) w = width - x;
        if (y + h > height) h = height - y;
        sb.append("<rect x=\"").append(x).append("\" y=\"").append(y);
        sb.append("\" width=\"").append(w).append("\" height=\"").append(h);
        sb.append("\" style=\"fill:rgb(");
        appendColor();
        sb.append(");stroke:none\"/>\n");
    }

    public void drawOval(int x, int y, int w, int h) {
        x = cx(x); y = cy(y);
        if (x + w > width) w = width - x;
        if (y + h > height) h = height - y;
        int cxv = x + w / 2, cyv = y + h / 2;
        sb.append("<ellipse cx=\"").append(cxv).append("\" cy=\"").append(cyv);
        sb.append("\" rx=\"").append(w / 2).append("\" ry=\"").append(h / 2);
        sb.append("\" style=\"fill:none;stroke:rgb(");
        appendColor();
        sb.append(");stroke-width:1\"/>\n");
    }

    public void fillOval(int x, int y, int w, int h) {
        x = cx(x); y = cy(y);
        if (x + w > width) w = width - x;
        if (y + h > height) h = height - y;
        int cxv = x + w / 2, cyv = y + h / 2;
        sb.append("<ellipse cx=\"").append(cxv).append("\" cy=\"").append(cyv);
        sb.append("\" rx=\"").append(w / 2).append("\" ry=\"").append(h / 2);
        sb.append("\" style=\"fill:rgb(");
        appendColor();
        sb.append(");stroke:none\"/>\n");
    }

    public void drawString(String text, int x, int y) {
        x = cx(x); y = cy(y);
        text = text.replace("&", "&amp;");
        text = text.replace("<", "&lt;");
        text = text.replace(">", "&gt;");
        sb.append("<text x=\"").append(x).append("\" y=\"").append(y);
        sb.append("\" fill=\"rgb(");
        appendColor();
        sb.append(")\" font-family=\"Arial\" font-size=\"").append(fontSize);
        if (fontBold) sb.append("\" font-weight=\"bold");
        sb.append("\">").append(text).append("</text>\n");
    }

    public void drawPolygon(int[] xPoints, int[] yPoints, int n) {
        sb.append("<polygon points=\"");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(cx(xPoints[i])).append(",").append(cy(yPoints[i]));
        }
        sb.append("\" style=\"stroke-width:1;stroke:rgb(");
        appendColor();
        sb.append(");fill:none\"/>\n");
    }

    public void fillPolygon(int[] xPoints, int[] yPoints, int n) {
        sb.append("<polygon points=\"");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(cx(xPoints[i])).append(",").append(cy(yPoints[i]));
        }
        sb.append("\" style=\"fill:rgb(");
        appendColor();
        sb.append(");stroke:none\"/>\n");
    }

    @Override
    public String toString() {
        return sb.toString() + "</svg>\n";
    }
}
