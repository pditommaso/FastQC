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
