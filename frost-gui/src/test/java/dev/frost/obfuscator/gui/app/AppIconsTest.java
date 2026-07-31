package dev.frost.obfuscator.gui.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconsTest {
    @Test
    void runtimeIconsUseTheSupplied128PixelArtwork() throws Exception {
        BufferedImage window = read(AppIcons.WINDOW_ICON_RESOURCE);
        BufferedImage transparent = read(AppIcons.TRANSPARENT_ICON_RESOURCE);

        assertEquals(128, window.getWidth());
        assertEquals(128, window.getHeight());
        assertEquals(128, transparent.getWidth());
        assertEquals(128, transparent.getHeight());

        assertEquals(255, alpha(window, 0, 0), "the OS icon keeps its solid black tile");
        assertEquals(0, alpha(transparent, 0, 0), "the in-app icon keeps a transparent canvas");
        assertTrue(hasVisiblePixel(transparent), "the transparent icon must retain the supplied blue mark");
    }

    private static BufferedImage read(String resource) throws Exception {
        try (InputStream input = AppIconsTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, resource + " must be a decodable image");
            return image;
        }
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getColorModel().hasAlpha() ? (image.getRGB(x, y) >>> 24) & 0xff : 255;
    }

    private static boolean hasVisiblePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image, x, y) > 0 && (image.getRGB(x, y) & 0x00ffffff) != 0) return true;
            }
        }
        return false;
    }
}
