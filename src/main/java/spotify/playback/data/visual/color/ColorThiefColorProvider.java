package spotify.playback.data.visual.color;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import de.androidpit.colorthief.ColorThief;
import de.androidpit.colorthief.MMCQ.VBox;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import spotify.playback.data.visual.color.DominantRGBs.RGB;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Implementation of the dominant color finding algorithm using ColorThief. This
 * implementation has been fine-tuned and tested with hundreds of album cover
 * arts. It's not always perfect, such as when dealing with very colorful images
 * that don't necessarily have one particular color stand out, but it should
 * still do a decent job.
 */
@Primary // as of now, simply the more reliable implementation, all downsides considered
@Component
public class ColorThiefColorProvider implements ColorProvider {

	private static final int PALETTE_SAMPLE_SIZE = 10;
	private static final int PALETTE_SAMPLE_QUALITY = 5;
	private static final double MIN_BRIGHTNESS = 0.075;
	private static final double MIN_COLORFULNESS = 0.1;
	private static final int MIN_POPULATION = 1000;
	private static final int MIN_COLORED_PIXELS = 3000;
	private static final double EPSILON = 0.001;

	private final LoadingCache<String, DominantRGBs> cachedDominantColorsForUrl;

	public ColorThiefColorProvider() {
		this.cachedDominantColorsForUrl = CacheBuilder.newBuilder()
			.build(CacheLoader.from((imageUrl) -> {
				try {
					DominantRGBs colors = getDominantColors(imageUrl);
					ColorUtil.normalizeAllForReadability(colors);
					return colors;
				} catch (IOException e) {
					e.printStackTrace();
					return null;
				}
			}));
	}

	@Override
	public DominantRGBs getDominantColorFromImageUrl(String imageUrl) {
		if (imageUrl != null) {
			try {
				return cachedDominantColorsForUrl.get(imageUrl);
			} catch (ExecutionException e) {
				e.printStackTrace();
			}
		}
		return DominantRGBs.FALLBACK;
	}

	private DominantRGBs getDominantColors(String imageUrl) throws IOException {
		BufferedImage img = ImageIO.read(new URL(imageUrl));

		List<VBox> vBoxes = new ArrayList<>(ColorThief.getColorMap(img, PALETTE_SAMPLE_SIZE, PALETTE_SAMPLE_QUALITY, true).vboxes);
		vBoxes.removeIf(vBox -> !isValidVbox(vBox));
		vBoxes.sort(Comparator.comparingInt(this::calculateWeightedPopulation));
		Collections.reverse(vBoxes);

		int totalPopulationOfColor = 0;
		for (VBox vBox2 : vBoxes) {
			totalPopulationOfColor += vBox2.count(false);
		}
		if (totalPopulationOfColor < MIN_COLORED_PIXELS) {
			vBoxes.clear();
		}

		double borderBrightness = calculateAvgBorderBrightness(img);
		if (vBoxes.isEmpty()) {
			// Grayscale image
			RGB textColor = DominantRGBs.RGB.DEFAULT_RGB;
			RGB backgroundOverlay = RGB.of(
				(int) (textColor.getR() * borderBrightness),
				(int) (textColor.getG() * borderBrightness),
				(int) (textColor.getB() * borderBrightness));
			return DominantRGBs.of(textColor, backgroundOverlay, borderBrightness);
		} else if (vBoxes.size() == 1) {
			// Monochrome image
			int[] pal = vBoxes.get(0).avg(false);
			RGB rgb = RGB.of(pal[0], pal[1], pal[2]);
			return DominantRGBs.of(rgb, rgb, borderBrightness);
		} else {
			// Normal image (at least two colors)
			int[] pal1 = vBoxes.get(0).avg(false);
			int[] pal2 = vBoxes.get(1).avg(false);
			RGB rgb1 = RGB.of(pal1[0], pal1[1], pal1[2]);
			RGB rgb2 = RGB.of(pal2[0], pal2[1], pal2[2]);
			if (ColorUtil.calculatePerceivedBrightness(rgb1) > ColorUtil.calculatePerceivedBrightness(rgb2)) {
				return DominantRGBs.of(rgb1, rgb2, borderBrightness);
			} else {
				return DominantRGBs.of(rgb2, rgb1, borderBrightness);
			}
		}
	}

	private boolean isValidVbox(VBox vBox) {
		int[] pal = vBox.avg(false);
		int r = pal[0];
		int g = pal[1];
		int b = pal[2];

		double brightness = ColorUtil.calculatePerceivedBrightness(RGB.of(r, g, b));
		int population = vBox.count(false);
		double colorfulness = ColorUtil.calculateColorfulness(r, g, b);

		return (population > MIN_POPULATION && brightness > MIN_BRIGHTNESS && colorfulness > MIN_COLORFULNESS);
	}

	private int calculateWeightedPopulation(VBox vBox) {
		int[] pal = vBox.avg(false);
		int r = pal[0];
		int g = pal[1];
		int b = pal[2];
		int population = vBox.count(false);
		double brightness = ColorUtil.calculatePerceivedBrightness(RGB.of(r, g, b));
		return (int) (population * Math.pow(brightness, 2.0));
	}

	private double calculateAvgBorderBrightness(BufferedImage img) {
		final int sampleRange = img.getWidth() / 10;
		double acc = 0;
		long samples = 0;

		for (int x = 0; x < img.getWidth(); x += sampleRange) {
			acc += calcWeightedBrightnessAtLocation(img, x, 0);
			acc += calcWeightedBrightnessAtLocation(img, x, img.getHeight() - 1);
			samples += 2;
		}

		for (int y = 0; y < img.getHeight(); y += sampleRange) {
			acc += calcWeightedBrightnessAtLocation(img, 0, y);
			acc += calcWeightedBrightnessAtLocation(img, img.getWidth() - 1, y);
			samples += 2;
		}

		return (acc / (double) samples);
	}

	private double calcWeightedBrightnessAtLocation(BufferedImage img, int x, int y) {
		Color color = new Color(img.getRGB(x, y));
		int r = color.getRed();
		int g = color.getGreen();
		int b = color.getBlue();
		double brightnessAtLocation = ColorUtil.calculateBrightness(r, g, b);
		double brightnessAfterMin = Math.max(brightnessAtLocation, EPSILON);
		return Math.pow(brightnessAfterMin, 2);
	}
}
