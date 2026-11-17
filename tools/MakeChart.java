import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws src/main/resources/images/availability-chart.png, the raster image of the demo report.
 * Not part of the build; the PNG is committed. To regenerate it:
 *
 *     java tools/MakeChart.java src/main/resources/images/availability-chart.png
 *
 * The values drawn here are repeated in the alternate text of the image in
 * accessible-demo.jrxml; change both together. The labels use the logical "SansSerif" font,
 * so the pixels differ slightly between operating systems.
 */
public class MakeChart {
    public static void main(String[] args) throws Exception {
        int w = 960, h = 440;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun"};
        double[] values = {99.20, 99.60, 99.90, 99.70, 99.95, 99.90};
        int left = 110, right = 30, top = 40, bottom = 70;
        int plotW = w - left - right, plotH = h - top - bottom;
        double min = 99.0, max = 100.0;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            double v = min + i * 0.25;
            int y = top + plotH - (int) Math.round((v - min) / (max - min) * plotH);
            g.setColor(new Color(0xDDDDDD)); g.setStroke(new BasicStroke(1.5f));
            g.drawLine(left, y, left + plotW, y);
            g.setColor(new Color(0x222222));
            String label = String.format(java.util.Locale.ROOT, "%.2f %%", v);
            g.drawString(label, left - 12 - fm.stringWidth(label), y + 8);
        }
        int target = top + plotH - (int) Math.round((99.5 - min) / (max - min) * plotH);
        g.setColor(new Color(0xB00020));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{14f, 8f}, 0f));
        g.drawLine(left, target, left + plotW, target);
        int slot = plotW / months.length, bar = (int) (slot * 0.56);
        for (int i = 0; i < months.length; i++) {
            int bh = (int) Math.round((values[i] - min) / (max - min) * plotH);
            int x = left + i * slot + (slot - bar) / 2;
            g.setColor(i % 2 == 0 ? new Color(0x0B3C5D) : new Color(0x1D6FA5));
            g.fillRect(x, top + plotH - bh, bar, bh);
            g.setColor(new Color(0x222222));
            g.drawString(months[i], x + (bar - fm.stringWidth(months[i])) / 2, top + plotH + 34);
            String val = String.format(java.util.Locale.ROOT, "%.2f", values[i]);
            g.drawString(val, x + (bar - fm.stringWidth(val)) / 2, top + plotH - bh - 8);
        }
        g.setColor(new Color(0x222222)); g.setStroke(new BasicStroke(2.5f));
        g.drawLine(left, top, left, top + plotH); g.drawLine(left, top + plotH, left + plotW, top + plotH);
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
    }
}
