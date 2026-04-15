# Pure-Java SVG Rendering Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace AWT/Graphics2D-based chart rendering with pure-Java SVG generation so FastQC can compile and run as a GraalVM native-image without native AWT libraries.

**Architecture:** Create a `SVGDocument` class that provides drawing primitives (drawLine, fillRect, drawString, etc.) without any AWT dependency. Add `renderToSVG(SVGDocument)` methods to each graph class that contain the same rendering logic as their existing `paint(Graphics)` methods but target SVGDocument instead. Modify `AbstractQCModule.writeDefaultImage()` to use the direct SVG path, producing SVG-only output (no PNG) in CLI mode. Fix `base64ForIcon()` to read PNG bytes directly without ImageIO.

**Tech Stack:** Java 21, Ant build, GraalVM 25 native-image. No new dependencies.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `uk/ac/babraham/FastQC/Graphs/SVGDocument.java` | **Create** | Pure-Java SVG generator with drawing primitives and font metrics approximation |
| `uk/ac/babraham/FastQC/Graphs/LineGraph.java` | **Modify** | Add `renderToSVG(SVGDocument)` method |
| `uk/ac/babraham/FastQC/Graphs/QualityBoxPlot.java` | **Modify** | Add `renderToSVG(SVGDocument)` method |
| `uk/ac/babraham/FastQC/Graphs/TileGraph.java` | **Modify** | Add `renderToSVG(SVGDocument)` method |
| `uk/ac/babraham/FastQC/Modules/AbstractQCModule.java` | **Modify** | New `writeDefaultImageSVG()` that bypasses AWT |
| `uk/ac/babraham/FastQC/Report/HTMLReportArchive.java` | **Modify** | Fix `base64ForIcon()` to not use ImageIO |
| `uk/ac/babraham/FastQC/Modules/KmerContent.java` | **Modify** | Uses `writeSpecificImage` with inline LineGraph - adapt for SVG |
| `uk/ac/babraham/FastQC/Utilities/ImageToBase64.java` | **Modify** | Add raw-bytes PNG base64 method |
| `uk/ac/babraham/FastQC/graal/AWTFeature.java` | **Modify** | Simplify - most substitutions no longer needed |
| `test/unit/Graphs/SVGDocumentTest.java` | **Create** | Unit tests for SVGDocument |
| `test/unit/Graphs/LineGraphSVGTest.java` | **Create** | Tests for LineGraph SVG rendering |

---

### Task 1: Create SVGDocument - Pure-Java SVG Generator

**Files:**
- Create: `uk/ac/babraham/FastQC/Graphs/SVGDocument.java`
- Create: `test/unit/Graphs/SVGDocumentTest.java`

This is the core new class. It provides the same drawing primitives used by the graph classes but generates SVG XML directly without any `java.awt` dependency. It includes a simple font metrics approximation for layout calculations.

- [ ] **Step 1: Write the failing test**

Create `test/unit/Graphs/SVGDocumentTest.java`:

```java
package test.unit.Graphs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import uk.ac.babraham.FastQC.Graphs.SVGDocument;

public class SVGDocumentTest {

    @Test
    public void testEmptyDocument() {
        SVGDocument doc = new SVGDocument(800, 600);
        String svg = doc.toString();
        assertTrue(svg.contains("<svg width=\"800\" height=\"600\""));
        assertTrue(svg.contains("</svg>"));
    }

    @Test
    public void testDrawLine() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setColor(0, 0, 0);
        doc.drawLine(10, 20, 30, 40);
        String svg = doc.toString();
        assertTrue(svg.contains("<line x1=\"10\" y1=\"20\" x2=\"30\" y2=\"40\""));
        assertTrue(svg.contains("rgb(0,0,0)"));
    }

    @Test
    public void testFillRect() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setColor(255, 0, 0);
        doc.fillRect(10, 20, 50, 30);
        String svg = doc.toString();
        assertTrue(svg.contains("<rect"));
        assertTrue(svg.contains("x=\"10\""));
        assertTrue(svg.contains("y=\"20\""));
        assertTrue(svg.contains("width=\"50\""));
        assertTrue(svg.contains("height=\"30\""));
        assertTrue(svg.contains("fill:rgb(255,0,0)"));
    }

    @Test
    public void testDrawString() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setColor(0, 0, 0);
        doc.drawString("Hello", 10, 20);
        String svg = doc.toString();
        assertTrue(svg.contains("<text"));
        assertTrue(svg.contains("Hello"));
    }

    @Test
    public void testDrawStringXmlEscape() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.drawString("A&B<C>D", 10, 20);
        String svg = doc.toString();
        assertTrue(svg.contains("&amp;"));
        assertTrue(svg.contains("&lt;"));
        assertTrue(svg.contains("&gt;"));
    }

    @Test
    public void testTranslate() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setColor(0, 0, 0);
        doc.translate(10, 20);
        doc.drawLine(0, 0, 5, 5);
        String svg = doc.toString();
        assertTrue(svg.contains("x1=\"10\" y1=\"20\" x2=\"15\" y2=\"25\""));
    }

    @Test
    public void testStringWidth() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setFontSize(12, false);
        int width = doc.stringWidth("Hello");
        assertTrue(width > 0);
        // Wider string should be wider
        assertTrue(doc.stringWidth("Hello World") > width);
    }

    @Test
    public void testFillPolygon() {
        SVGDocument doc = new SVGDocument(100, 100);
        doc.setColor(0, 128, 255);
        doc.fillPolygon(new int[]{10, 20, 30}, new int[]{10, 30, 10}, 3);
        String svg = doc.toString();
        assertTrue(svg.contains("<polygon"));
        assertTrue(svg.contains("10,10"));
        assertTrue(svg.contains("fill:rgb(0,128,255)"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/opt/ant/bin/ant compile-tests 2>&1 | tail -5`
Expected: Compilation fails - `SVGDocument` class not found.

- [ ] **Step 3: Write the SVGDocument implementation**

Create `uk/ac/babraham/FastQC/Graphs/SVGDocument.java`:

```java
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
    // Measured from common Latin characters. Good enough for layout.
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

    // --- Color ---

    public void setColor(int r, int g, int b) {
        this.red = r;
        this.green = g;
        this.blue = b;
    }

    // --- Font ---

    public void setFontSize(int size, boolean bold) {
        this.fontSize = size;
        this.fontBold = bold;
    }

    public int getFontSize() { return fontSize; }
    public boolean isFontBold() { return fontBold; }

    /** Approximate string width in pixels for layout calculations. */
    public int stringWidth(String s) {
        if (s == null) return 0;
        return (int)(s.length() * fontSize * CHAR_WIDTH_RATIO);
    }

    /** Approximate font ascent in pixels. */
    public int fontAscent() {
        return (int)(fontSize * ASCENT_RATIO);
    }

    // --- Translation ---

    public void translate(int dx, int dy) {
        this.translateX += dx;
        this.translateY += dy;
    }

    // --- Drawing primitives ---

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
        int cx = x + w / 2, cy = y + h / 2;
        sb.append("<ellipse cx=\"").append(cx).append("\" cy=\"").append(cy);
        sb.append("\" rx=\"").append(w / 2).append("\" ry=\"").append(h / 2);
        sb.append("\" style=\"fill:none;stroke:rgb(");
        appendColor();
        sb.append(");stroke-width:1\"/>\n");
    }

    public void fillOval(int x, int y, int w, int h) {
        x = cx(x); y = cy(y);
        if (x + w > width) w = width - x;
        if (y + h > height) h = height - y;
        int cx = x + w / 2, cy = y + h / 2;
        sb.append("<ellipse cx=\"").append(cx).append("\" cy=\"").append(cy);
        sb.append("\" rx=\"").append(w / 2).append("\" ry=\"").append(h / 2);
        sb.append("\" style=\"fill:rgb(");
        appendColor();
        sb.append(");stroke:none\"/>\n");
    }

    public void drawString(String text, int x, int y) {
        x = cx(x); y = cy(y);
        // XML-escape the text
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

    // --- Output ---

    @Override
    public String toString() {
        return sb.toString() + "</svg>\n";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `GRAALVM_HOME=/Users/pditommaso/.sdkman/candidates/java/25.0.2-graal JAVA_HOME=$GRAALVM_HOME /opt/homebrew/opt/ant/bin/ant unit-test 2>&1 | tail -10`
Expected: All SVGDocumentTest tests pass.

- [ ] **Step 5: Commit**

```bash
git add uk/ac/babraham/FastQC/Graphs/SVGDocument.java test/unit/Graphs/SVGDocumentTest.java
git commit -m "Add SVGDocument: pure-Java SVG generator without AWT dependency"
```

---

### Task 2: Add renderToSVG() to LineGraph

**Files:**
- Modify: `uk/ac/babraham/FastQC/Graphs/LineGraph.java`

Add a `renderToSVG(SVGDocument doc)` method that contains the same rendering logic as `paint(Graphics g)` but uses `SVGDocument` instead of `Graphics`. The existing `paint()` method stays unchanged for GUI mode.

- [ ] **Step 1: Add renderToSVG method to LineGraph**

Add this method to `LineGraph.java` after the existing `paint()` method (after line 247, before `getY()`):

```java
public void renderToSVG(SVGDocument doc) {
    int w = doc.getWidth();
    int h = doc.getHeight();

    doc.setColor(255, 255, 255);
    doc.fillRect(0, 0, w, h);
    doc.setColor(0, 0, 0);
    doc.setFontSize(12, false);

    double yStart;
    if (minY % yInterval == 0) {
        yStart = minY;
    } else {
        yStart = yInterval * (((int) minY / yInterval) + 1);
    }

    int xOffset = 0;
    for (double i = yStart; i <= maxY; i += yInterval) {
        String label = "" + i;
        label = label.replaceAll(".0$", "");
        int labelWidth = doc.stringWidth(label);
        if (labelWidth > xOffset) xOffset = labelWidth;
        doc.drawString(label, 2, getY(i, h) + (doc.fontAscent() / 2));
    }
    xOffset += 5;

    // Graph title
    int titleWidth = doc.stringWidth(graphTitle);
    doc.drawString(graphTitle, (xOffset + ((w - (xOffset + 10)) / 2)) - (titleWidth / 2), 30);

    // Axes
    doc.drawLine(xOffset, h - 40, w - 10, h - 40);
    doc.drawLine(xOffset, h - 40, xOffset, 40);

    // X-axis label
    doc.drawString(xLabel, (w / 2) - (doc.stringWidth(xLabel) / 2), h - 5);

    int baseWidth = (w - (xOffset + 10)) / Math.max(data[0].length, 1);
    if (baseWidth < 1) baseWidth = 1;

    // Alternating background bands and x-axis labels
    int lastXLabelEnd = 0;
    for (int i = 0; i < data[0].length; i++) {
        if (i % 2 != 0) {
            doc.setColor(230, 230, 230);
            doc.fillRect(xOffset + (baseWidth * i), 40, baseWidth, h - 80);
        }
        doc.setColor(0, 0, 0);
        String baseNumber = "" + xCategories[i];
        int baseNumberWidth = doc.stringWidth(baseNumber);
        int baseNumberPosition = (baseWidth / 2) + xOffset + (baseWidth * i) - (baseNumberWidth / 2);
        if (baseNumberPosition > lastXLabelEnd) {
            doc.drawString(baseNumber, baseNumberPosition, h - 25);
            lastXLabelEnd = baseNumberPosition + baseNumberWidth + 5;
        }
    }

    // Horizontal grid lines
    doc.setColor(180, 180, 180);
    for (double i = yStart; i <= maxY; i += yInterval) {
        doc.drawLine(xOffset, getY(i, h), w - 10, getY(i, h));
    }

    // Data lines
    for (int d = 0; d < data.length; d++) {
        Color c = COLOURS[d % COLOURS.length];
        doc.setColor(c.getRed(), c.getGreen(), c.getBlue());

        int lastY = 0;
        if (data[d].length > 0) lastY = getY(data[d][0], h);
        for (int i = 1; i < data[d].length; i++) {
            int thisY = getY(data[d][i], h);
            doc.drawLine((baseWidth / 2) + xOffset + (baseWidth * (i - 1)), lastY,
                         (baseWidth / 2) + xOffset + (baseWidth * i), thisY);
            lastY = thisY;
        }
    }

    // Legend
    doc.setFontSize(12, true);
    int widestLabel = 0;
    for (int t = 0; t < xTitles.length; t++) {
        int labelWidth = doc.stringWidth(xTitles[t]);
        if (labelWidth > widestLabel) widestLabel = labelWidth;
    }
    widestLabel += 6;

    doc.setColor(255, 255, 255);
    doc.fillRect((w - 10) - widestLabel, 40, widestLabel, 3 + (20 * xTitles.length));
    doc.setColor(192, 192, 192);
    doc.drawRect((w - 10) - widestLabel, 40, widestLabel, 3 + (20 * xTitles.length));

    for (int t = 0; t < xTitles.length; t++) {
        Color c = COLOURS[t % COLOURS.length];
        doc.setColor(c.getRed(), c.getGreen(), c.getBlue());
        doc.drawString(xTitles[t], ((w - 10) - widestLabel) + 3, 35 + (20 * (t + 1)));
    }
    doc.setFontSize(12, false);
}

private int getY(double y, int height) {
    return (height - 40) - (int) (((height - 80) / (maxY - minY)) * y);
}
```

The existing `getY(double)` method uses `getHeight()` from JPanel. The new overload takes height explicitly.

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/opt/ant/bin/ant build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add uk/ac/babraham/FastQC/Graphs/LineGraph.java
git commit -m "Add renderToSVG() to LineGraph for AWT-free SVG rendering"
```

---

### Task 3: Add renderToSVG() to QualityBoxPlot

**Files:**
- Modify: `uk/ac/babraham/FastQC/Graphs/QualityBoxPlot.java`

Same pattern as LineGraph. Add `renderToSVG(SVGDocument doc)` that replicates the `paint()` logic.

- [ ] **Step 1: Add renderToSVG method**

Add after the existing `paint()` method (after line 204, before `getY()`):

```java
public void renderToSVG(SVGDocument doc) {
    int w = doc.getWidth();
    int h = doc.getHeight();

    doc.setColor(255, 255, 255);
    doc.fillRect(0, 0, w, h);
    doc.setColor(0, 0, 0);
    doc.setFontSize(12, false);

    double yStart;
    if (minY % yInterval == 0) {
        yStart = minY;
    } else {
        yStart = yInterval * (((int) minY / yInterval) + 1);
    }

    int xOffset = 0;
    for (double i = yStart; i <= maxY; i += yInterval) {
        String label = "" + i;
        label = label.replaceAll(".0$", "");
        int labelWidth = doc.stringWidth(label);
        if (labelWidth > xOffset) xOffset = labelWidth;
        doc.drawString(label, 2, getY(i, h) + (doc.fontAscent() / 2));
    }
    xOffset += 5;

    // Title
    int titleWidth = doc.stringWidth(graphTitle);
    doc.drawString(graphTitle, (xOffset + ((w - (xOffset + 10)) / 2)) - (titleWidth / 2), 30);

    int baseWidth = (w - (xOffset + 10)) / means.length;
    if (baseWidth < 1) baseWidth = 1;

    // Background quality zones and x-axis labels
    int lastXLabelEnd = 0;
    for (int i = 0; i < means.length; i++) {
        // UGLY zone (below 20)
        if (i % 2 != 0) { doc.setColor(230, 195, 195); } else { doc.setColor(230, 175, 175); }
        doc.fillRect(xOffset + (baseWidth * i), getY(20, h), baseWidth, getY(yStart, h) - getY(20, h));

        // BAD zone (20-28)
        if (i % 2 != 0) { doc.setColor(230, 220, 195); } else { doc.setColor(230, 215, 175); }
        doc.fillRect(xOffset + (baseWidth * i), getY(28, h), baseWidth, getY(20, h) - getY(28, h));

        // GOOD zone (above 28)
        if (i % 2 != 0) { doc.setColor(195, 230, 195); } else { doc.setColor(175, 230, 175); }
        doc.fillRect(xOffset + (baseWidth * i), getY(maxY, h), baseWidth, getY(28, h) - getY(maxY, h));

        doc.setColor(0, 0, 0);
        int baseNumberWidth = doc.stringWidth(xLabels[i]);
        int labelStart = ((baseWidth / 2) + xOffset + (baseWidth * i)) - (baseNumberWidth / 2);
        if (labelStart > lastXLabelEnd) {
            doc.drawString(xLabels[i], labelStart, h - 25);
            lastXLabelEnd = labelStart + doc.stringWidth(xLabels[i]) + 5;
        }
    }

    // Axes
    doc.setColor(0, 0, 0);
    doc.drawLine(xOffset, h - 40, w - 10, h - 40);
    doc.drawLine(xOffset, h - 40, xOffset, 40);
    String posLabel = "Position in read (bp)";
    doc.drawString(posLabel, (w / 2) - (doc.stringWidth(posLabel) / 2), h - 5);

    // Boxplots
    for (int i = 0; i < medians.length; i++) {
        int boxBottomY = getY(lowerQuartile[i], h);
        int boxTopY = getY(upperQuartile[i], h);
        int lowerWhiskerY = getY(lowest[i], h);
        int upperWhiskerY = getY(highest[i], h);
        int medianY = getY(medians[i], h);

        // Yellow box
        doc.setColor(240, 240, 0);
        doc.fillRect(xOffset + (baseWidth * i) + 2, boxTopY, baseWidth - 4, boxBottomY - boxTopY);
        doc.setColor(0, 0, 0);
        doc.drawRect(xOffset + (baseWidth * i) + 2, boxTopY, baseWidth - 4, boxBottomY - boxTopY);

        // Whiskers
        doc.drawLine(xOffset + (baseWidth * i) + (baseWidth / 2), upperWhiskerY,
                     xOffset + (baseWidth * i) + (baseWidth / 2), boxTopY);
        doc.drawLine(xOffset + (baseWidth * i) + 2, upperWhiskerY,
                     xOffset + (baseWidth * (i + 1)) - 2, upperWhiskerY);
        doc.drawLine(xOffset + (baseWidth * i) + (baseWidth / 2), lowerWhiskerY,
                     xOffset + (baseWidth * i) + (baseWidth / 2), boxBottomY);
        doc.drawLine(xOffset + (baseWidth * i) + 2, lowerWhiskerY,
                     xOffset + (baseWidth * (i + 1)) - 2, lowerWhiskerY);

        // Red median line
        doc.setColor(200, 0, 0);
        doc.drawLine(xOffset + (baseWidth * i) + 2, medianY,
                     (xOffset + (baseWidth * (i + 1))) - 2, medianY);
    }

    // Blue mean line
    doc.setColor(0, 0, 200);
    int lastY = getY(means[0], h);
    for (int i = 1; i < means.length; i++) {
        int thisY = getY(means[i], h);
        doc.drawLine((baseWidth / 2) + xOffset + (baseWidth * (i - 1)), lastY,
                     (baseWidth / 2) + xOffset + (baseWidth * i), thisY);
        lastY = thisY;
    }
}

private int getY(double y, int height) {
    return (height - 40) - (int) (((height - 80) / (maxY - minY)) * (y - minY));
}
```

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/opt/ant/bin/ant build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add uk/ac/babraham/FastQC/Graphs/QualityBoxPlot.java
git commit -m "Add renderToSVG() to QualityBoxPlot for AWT-free SVG rendering"
```

---

### Task 4: Add renderToSVG() to TileGraph

**Files:**
- Modify: `uk/ac/babraham/FastQC/Graphs/TileGraph.java`

- [ ] **Step 1: Add renderToSVG method**

Add after `paint()` method (after line 143, before `getColour()`):

```java
public void renderToSVG(SVGDocument doc) {
    int w = doc.getWidth();
    int h = doc.getHeight();

    doc.setColor(255, 255, 255);
    doc.fillRect(0, 0, w, h);
    doc.setColor(0, 0, 0);
    doc.setFontSize(12, false);

    int lastY = 0;
    int xOffset = 0;

    for (int i = 0; i < tiles.length; i++) {
        String label = "" + tiles[i];
        int labelWidth = doc.stringWidth(label);
        if (labelWidth > xOffset) xOffset = labelWidth;

        int thisY = getY(i, h);
        if (i > 0 && thisY + doc.fontAscent() > lastY) continue;
        doc.drawString(label, 2, getY(i, h));
        lastY = thisY;
    }
    xOffset += 5;

    // Title
    String graphTitle = "Quality per tile";
    int titleWidth = doc.stringWidth(graphTitle);
    doc.drawString(graphTitle, (xOffset + ((w - (xOffset + 10)) / 2)) - (titleWidth / 2), 30);

    // Axes
    doc.drawLine(xOffset, h - 40, w - 10, h - 40);
    doc.drawLine(xOffset, h - 40, xOffset, 40);

    String xLabel = "Position in read (bp)";
    doc.drawString(xLabel, (w / 2) - (doc.stringWidth(xLabel) / 2), h - 5);

    int baseWidth = (w - (xOffset + 10)) / xLabels.length;
    if (baseWidth < 1) baseWidth = 1;

    // X-axis labels
    int lastXLabelEnd = 0;
    doc.setColor(0, 0, 0);
    for (int base = 0; base < xLabels.length; base++) {
        String baseNumber = "" + xLabels[base];
        int baseNumberWidth = doc.stringWidth(baseNumber);
        int baseNumberPosition = (baseWidth / 2) + xOffset + (baseWidth * base) - (baseNumberWidth / 2);
        if (baseNumberPosition > lastXLabelEnd) {
            doc.drawString(baseNumber, baseNumberPosition, h - 25);
            lastXLabelEnd = baseNumberPosition + baseNumberWidth + 5;
        }
    }

    // Heatmap tiles
    for (int tile = 0; tile < tiles.length; tile++) {
        for (int base = 0; base < xLabels.length; base++) {
            Color c = getColour(tile, base);
            doc.setColor(c.getRed(), c.getGreen(), c.getBlue());
            int x = xOffset + (baseWidth * base);
            int y = getY(tile + 1, h);
            doc.fillRect(x, y, baseWidth, getY(tile, h) - getY(tile + 1, h));
        }
    }
}

private int getY(double y, int height) {
    return (height - 40) - (int) (((height - 80) / (double) (tiles.length)) * y);
}
```

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/opt/ant/bin/ant build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add uk/ac/babraham/FastQC/Graphs/TileGraph.java
git commit -m "Add renderToSVG() to TileGraph for AWT-free SVG rendering"
```

---

### Task 5: Modify AbstractQCModule to use direct SVG rendering

**Files:**
- Modify: `uk/ac/babraham/FastQC/Modules/AbstractQCModule.java`
- Modify: `uk/ac/babraham/FastQC/Utilities/ImageToBase64.java`
- Modify: `uk/ac/babraham/FastQC/Report/HTMLReportArchive.java:381-390`

Replace `writeDefaultImage()` and `writeSpecificImage()` to use `SVGDocument.renderToSVG()` when the graph supports it. Fix `base64ForIcon()` to read PNG bytes directly without `ImageIO`.

- [ ] **Step 1: Add rawPngToBase64 to ImageToBase64**

Add to `uk/ac/babraham/FastQC/Utilities/ImageToBase64.java` after the existing `svgImageToBase64` method:

```java
/**
 * Converts raw PNG bytes to a base64 data URI without using ImageIO/AWT.
 */
public static String rawPngToBase64(byte[] pngBytes) {
    String b64 = java.util.Base64.getEncoder().encodeToString(pngBytes);
    return "data:image/png;base64," + b64;
}
```

- [ ] **Step 2: Fix HTMLReportArchive.base64ForIcon()**

Replace the `base64ForIcon` method at line 381-390 in `HTMLReportArchive.java`:

Replace:
```java
private String base64ForIcon (String path) {
    try {
        BufferedImage b = ImageIO.read(ClassLoader.getSystemResource("Templates/"+path));
        return (ImageToBase64.imageToBase64(b));
    }
    catch (IOException ioe) {
        ioe.printStackTrace();
        return "Failed";
    }
}
```

With:
```java
private String base64ForIcon (String path) {
    try {
        java.io.InputStream in = getClass().getResourceAsStream("/Templates/"+path);
        if (in == null) return "Failed";
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = in.read(buf)) > 0) baos.write(buf, 0, len);
        in.close();
        return ImageToBase64.rawPngToBase64(baos.toByteArray());
    }
    catch (IOException ioe) {
        ioe.printStackTrace();
        return "Failed";
    }
}
```

- [ ] **Step 3: Rewrite writeDefaultImage() in AbstractQCModule**

Replace the existing `writeDefaultImage` method (lines 63-97) in `AbstractQCModule.java` with:

```java
protected void writeDefaultImage(HTMLReportArchive report, String fileName, String imageTitle, int width, int height) throws IOException, XMLStreamException {
    ZipOutputStream zip = report.zipFile();
    JPanel resultsPanel = getResultsPanel();

    // Generate SVG directly from graph data if supported
    String svgData = renderDirectSVG(resultsPanel, width, height);

    // Write SVG to zip
    String svgFilename = fileName.replaceAll("\\.png$", ".svg");
    zip.putNextEntry(new ZipEntry(report.folderName() + "/Images/" + svgFilename));
    zip.write(svgData.getBytes());
    zip.closeEntry();

    // Embed SVG in HTML (SVG-only, no PNG generation)
    XMLStreamWriter xhtml = report.xhtmlStream();
    xhtml.writeStartElement("p");
    xhtml.writeEmptyElement("img");
    xhtml.writeAttribute("class", "indented");
    xhtml.writeAttribute("src", ImageToBase64.svgImageToBase64(svgData));
    xhtml.writeAttribute("alt", imageTitle);
    xhtml.writeEndElement();
}
```

- [ ] **Step 4: Rewrite writeSpecificImage() similarly**

Replace `writeSpecificImage` (lines 99-131) with:

```java
protected void writeSpecificImage(HTMLReportArchive report, JPanel resultsPanel, String fileName, String imageTitle, int width, int height) throws IOException, XMLStreamException {
    ZipOutputStream zip = report.zipFile();

    String svgData = renderDirectSVG(resultsPanel, width, height);

    String svgFilename = fileName.replaceAll("\\.png$", ".svg");
    zip.putNextEntry(new ZipEntry(report.folderName() + "/Images/" + svgFilename));
    zip.write(svgData.getBytes());
    zip.closeEntry();

    XMLStreamWriter xhtml = report.xhtmlStream();
    xhtml.writeStartElement("p");
    xhtml.writeEmptyElement("img");
    xhtml.writeAttribute("class", "indented");
    xhtml.writeAttribute("src", ImageToBase64.svgImageToBase64(svgData));
    xhtml.writeAttribute("alt", imageTitle);
    xhtml.writeEndElement();
}
```

- [ ] **Step 5: Add renderDirectSVG helper method**

Add this private helper to `AbstractQCModule`:

```java
private String renderDirectSVG(JPanel panel, int width, int height) {
    SVGDocument doc = new SVGDocument(width, height);
    if (panel instanceof LineGraph) {
        ((LineGraph) panel).renderToSVG(doc);
    } else if (panel instanceof QualityBoxPlot) {
        ((QualityBoxPlot) panel).renderToSVG(doc);
    } else if (panel instanceof TileGraph) {
        ((TileGraph) panel).renderToSVG(doc);
    }
    return doc.toString();
}
```

Add the necessary imports at the top of `AbstractQCModule.java`:
```java
import uk.ac.babraham.FastQC.Graphs.SVGDocument;
import uk.ac.babraham.FastQC.Graphs.LineGraph;
import uk.ac.babraham.FastQC.Graphs.QualityBoxPlot;
import uk.ac.babraham.FastQC.Graphs.TileGraph;
```

Remove unused imports: `BufferedImage`, `ImageIO`, `Graphics`, `SVGImageSaver`, `FastQCConfig`.

- [ ] **Step 6: Remove simpleXhtmlReport since it's no longer called**

Remove the `simpleXhtmlReport` method (lines 42-61) from `AbstractQCModule.java` as `writeDefaultImage` and `writeSpecificImage` now write XHTML inline.

- [ ] **Step 7: Verify compilation**

Run: `/opt/homebrew/opt/ant/bin/ant build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add uk/ac/babraham/FastQC/Modules/AbstractQCModule.java \
        uk/ac/babraham/FastQC/Utilities/ImageToBase64.java \
        uk/ac/babraham/FastQC/Report/HTMLReportArchive.java
git commit -m "Replace AWT-based image rendering with direct SVG generation"
```

---

### Task 6: Simplify AWTFeature and rebuild native image

**Files:**
- Modify: `uk/ac/babraham/FastQC/graal/AWTFeature.java`

With the rendering layer no longer using `BufferedImage`, `Graphics2D`, `ImageIO`, or `JPanel.paint()`, most of the AWT substitutions in AWTFeature are still needed because the graph classes still extend JPanel (constructor calls). Keep the minimal set: `Toolkit.loadLibraries`, `Toolkit.initIDs`, `GraphicsEnvironment.isHeadless`, `JPanel.updateUI`, `JComponent.updateUI`, and the `PlatformGraphicsInfo` substitutions.

Remove the many `initIDs` substitutions for image/raster/SurfaceData classes that are no longer reached.

- [ ] **Step 1: Simplify AWTFeature.java**

Replace the entire file with:

```java
package uk.ac.babraham.FastQC.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/**
 * GraalVM native-image Feature for headless AWT support.
 * 
 * FastQC graph classes extend JPanel, which triggers AWT toolkit
 * initialization even though we never render to screen. These
 * substitutions prevent the native AWT library from being loaded.
 */
public class AWTFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        System.setProperty("java.awt.headless", "true");
        RuntimeClassInitialization.initializeAtBuildTime("sun.awt.PlatformGraphicsInfo");
    }

    @Override
    public String getDescription() {
        return "Substitutes AWT native library loading for headless native-image";
    }
}

@TargetClass(java.awt.Toolkit.class)
final class Target_java_awt_Toolkit {
    @Substitute
    private static void loadLibraries() {}

    @Substitute
    private static void initIDs() {}

    @Substitute
    public static synchronized java.awt.Toolkit getDefaultToolkit() {
        return null;
    }
}

@TargetClass(java.awt.GraphicsEnvironment.class)
final class Target_java_awt_GraphicsEnvironment {
    @Substitute
    public static boolean isHeadless() { return true; }

    @Substitute
    private static boolean getHeadlessProperty() { return true; }
}

@TargetClass(className = "sun.awt.PlatformGraphicsInfo")
final class Target_sun_awt_PlatformGraphicsInfo {
    @Substitute
    public static boolean isInAquaSession() { return false; }
}

@TargetClass(javax.swing.JPanel.class)
final class Target_javax_swing_JPanel {
    @Substitute
    public void updateUI() {}
}

@TargetClass(javax.swing.JComponent.class)
final class Target_javax_swing_JComponent {
    @Substitute
    public void updateUI() {}
}
```

- [ ] **Step 2: Recompile AWTFeature and rebuild native image**

```bash
rm -f uk/ac/babraham/FastQC/graal/*.class
export GRAALVM_HOME=/Users/pditommaso/.sdkman/candidates/java/25.0.2-graal
$GRAALVM_HOME/bin/javac \
  --add-exports org.graalvm.nativeimage/org.graalvm.nativeimage.hosted=ALL-UNNAMED \
  --add-exports org.graalvm.nativeimage/com.oracle.svm.core.annotate=ALL-UNNAMED \
  -cp "$GRAALVM_HOME/lib/svm/builder/svm.jar" \
  uk/ac/babraham/FastQC/graal/AWTFeature.java
/opt/homebrew/opt/ant/bin/ant clean native-image
```

Expected: BUILD SUCCESSFUL, native image generated.

- [ ] **Step 3: Test the native binary**

```bash
rm -f test/data/minimal_fastqc*
./fastqc test/data/minimal.fastq
```

Expected: "Started analysis... Analysis complete..." with no exceptions. Check output:
```bash
ls -la test/data/minimal_fastqc.zip
unzip -l test/data/minimal_fastqc.zip | head -20
```

Should contain SVG files in `Images/` directory and `fastqc_report.html`.

- [ ] **Step 4: Test with the complex FASTQ file**

```bash
rm -f test/data/complex_fastqc*
./fastqc test/data/complex.fastq
ls -la test/data/complex_fastqc.zip
```

Expected: Completes without errors.

- [ ] **Step 5: Commit**

```bash
git add uk/ac/babraham/FastQC/graal/AWTFeature.java
git commit -m "Simplify AWTFeature after removing AWT rendering dependency"
```

---

### Task 7: Run unit tests and verify JVM mode still works

**Files:** (no changes, verification only)

- [ ] **Step 1: Run unit tests**

```bash
export GRAALVM_HOME=/Users/pditommaso/.sdkman/candidates/java/25.0.2-graal
export JAVA_HOME=$GRAALVM_HOME
/opt/homebrew/opt/ant/bin/ant unit-test
```

Expected: All tests pass.

- [ ] **Step 2: Verify JVM mode still works**

```bash
rm -f test/data/minimal_fastqc*
java -Djava.awt.headless=true -cp "bin:jbzip2-0.9.jar:htsjdk.jar:cisd-jhdf5.jar" \
  uk.ac.babraham.FastQC.FastQCApplication test/data/minimal.fastq
ls -la test/data/minimal_fastqc.zip
```

Expected: Completes successfully. Report zip is generated.

- [ ] **Step 3: Compare native vs JVM output**

```bash
mkdir -p /tmp/fastqc-native /tmp/fastqc-jvm
unzip -o test/data/minimal_fastqc.zip -d /tmp/fastqc-native
rm -f test/data/minimal_fastqc*
java -Djava.awt.headless=true -cp "bin:jbzip2-0.9.jar:htsjdk.jar:cisd-jhdf5.jar" \
  uk.ac.babraham.FastQC.FastQCApplication test/data/minimal.fastq
unzip -o test/data/minimal_fastqc.zip -d /tmp/fastqc-jvm
diff /tmp/fastqc-native/*/fastqc_data.txt /tmp/fastqc-jvm/*/fastqc_data.txt
```

Expected: Data files should be identical. SVG files may differ slightly in font metric calculations.

- [ ] **Step 4: Final commit with all changes**

```bash
git add -A
git status
git commit -m "Pure-Java SVG rendering for GraalVM native-image support"
```
