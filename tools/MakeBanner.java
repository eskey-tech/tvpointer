import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Writes the 320x180 Android TV banner.
 * Usage: java tools/MakeBanner.java app/src/main/res/drawable-xhdpi/banner.png
 */
public class MakeBanner {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java tools/MakeBanner.java <out.png>");
            System.exit(2);
        }
        System.setProperty("java.awt.headless", "true");
        int w = 320, h = 180;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B2433), w, h, new Color(0x14161C)));
        g.fillRect(0, 0, w, h);

        // Same arrow as the in-app pointer (12x19 grid), tip at (34, 50).
        double u = 4.2, ox = 34, oy = 50;
        double[][] pts = { {0, 0}, {0, 17}, {4.2, 13}, {7, 19}, {9.6, 17.9}, {6.8, 12}, {12, 12} };
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(ox + pts[0][0] * u, oy + pts[0][1] * u);
        for (int i = 1; i < pts.length; i++) arrow.lineTo(ox + pts[i][0] * u, oy + pts[i][1] * u);
        arrow.closePath();
        g.setColor(Color.WHITE);
        g.fill(arrow);
        g.setColor(new Color(0x4FC3F7));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);

        // Largest title size (up to 34) that fits between the arrow and the right margin.
        int textX = 104, maxWidth = w - textX - 16, size = 34;
        Font title;
        do {
            title = new Font(Font.SANS_SERIF, Font.BOLD, size--);
        } while (g.getFontMetrics(title).stringWidth("TV Pointer") > maxWidth);
        g.setColor(Color.WHITE);
        g.setFont(title);
        g.drawString("TV Pointer", textX, 96);
        g.setColor(new Color(0xAAB1BF));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        g.drawString("by ESK Tech", textX + 2, 124);
        g.dispose();

        File out = new File(args[0]).getAbsoluteFile();
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }
}
