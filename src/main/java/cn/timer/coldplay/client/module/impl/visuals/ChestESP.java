package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.util.Gizmo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public final class ChestESP extends Module {
    private static final String TAG = "Tag";
    private static final String BOX = "Box";

    // Label metrics are the design's CSS px, one per GUI px; a label is drawn at the GUI scale's screen px.
    private static final float CARD_W = 204.0F;
    private static final float CARD_H = 58.0F;
    private static final float CARD_R = 11.0F;
    private static final float CARD_PAD = 12.0F; // 1px border plus 11px padding
    private static final float TAG_H = 24.0F;
    private static final float ICON = 14.0F;
    private static final float CHIP = 8.0F;
    private static final float CHIP_GAP = 5.0F;
    private static final float COVER_GAP = 12.0F;
    private static final int MARGIN = 24; // room for the drop shadow
    private static final int PANEL = 0x9E0E1015;
    private static final int BORDER = 0x29FFFFFF;
    private static final int SHADOW = 0x080A10; // at 22% alpha
    private static final int TEXT = 0xFFF4F6F8;
    private static final int DIM = 0xBDF4F6F8;
    private static final int EDGE = 0x66000000; // the icons' face outline
    private static final float EMBOLDEN = 0.3F; // screen px of stroke around each glyph
    private static final double TAG_LIFT = 0.35; // blocks above the box
    private static final double CARD_LIFT = 2.8; // clear of a usual bed defense
    private static final String NO_DEFENSE = "No defense";
    private static final Map<List<Float>, BufferedImage> SHADOWS = new HashMap<>();
    private static int textures;

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", TAG, TAG, BOX));
    private final NumberSetting range = addSetting(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 5.0));
    private final NumberSetting width = addSetting(new NumberSetting("Width", 2.0, 0.0, 5.0, 0.5));
    private final ColorSetting color = addSetting(new ColorSetting("Color", 0xFFC8AA00));
    private final List<Label> labels = new ArrayList<>();
    private final Map<Key, Raster> rasters = new HashMap<>();

    public ChestESP() {
        super("ChestESP", "Highlights chests through walls", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        labels.clear();
        boolean tag = mode.get().equals(TAG);
        draw(ChestBlockEntity.class, ChestESP::box, range.get(), Gizmo.style(color.get(), width.get(), opacity.get()),
                (state, box) -> {
                    if (tag) {
                        labels.add(new Label(box.getCenter().with(Direction.Axis.Y, box.maxY), state.getBlock(),
                                state.getValue(ChestBlock.TYPE) == ChestType.SINGLE ? "Chest" : "Double chest", null));
                    }
                });
    }

    public void render2D(GuiGraphics graphics, DeltaTracker ignored) {
        drawLabels(graphics, enabled() ? labels : List.of(), rasters);
    }

    /** A double chest is one box, drawn from its LEFT half; the RIGHT half draws nothing. */
    static AABB box(BlockPos pos, BlockState state) {
        return switch (state.getValue(ChestBlock.TYPE)) {
            case SINGLE -> bounds(pos, state);
            case LEFT -> bounds(pos, state).minmax(bounds(ChestBlock.getConnectedBlockPos(pos, state),
                    state.setValue(ChestBlock.TYPE, ChestType.RIGHT)));
            case RIGHT -> null;
        };
    }

    /**
     * Draws boxOf for every live {@code type} block entity whose centre is within range, and hands each drawn
     * box to {@code found}; BedESP shares it.
     */
    static void draw(Class<? extends BlockEntity> type, BiFunction<BlockPos, BlockState, AABB> boxOf,
                     double range, GizmoStyle style, BiConsumer<BlockState, AABB> found) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        // ceil(range / 16) chunk columns each side is exactly enough for every block centre within range.
        ChunkPos.rangeClosed(player.chunkPosition(), Mth.ceil(range / 16.0))
                .map(column -> level.getChunkSource().getChunk(column.x, column.z, ChunkStatus.FULL, false))
                .filter(Objects::nonNull)
                .flatMap(chunk -> chunk.getBlockEntities().values().stream())
                .filter(entity -> type.isInstance(entity) && !entity.isRemoved()
                        && player.distanceToSqr(Vec3.atCenterOf(entity.getBlockPos())) <= range * range)
                .forEach(entity -> {
                    AABB box = boxOf.apply(entity.getBlockPos(), entity.getBlockState());
                    if (box != null) {
                        Gizmos.cuboid(box, style).setAlwaysOnTop();
                        found.accept(entity.getBlockState(), box);
                    }
                });
    }

    /** Chest and bed shapes depend on the state alone and are never empty. */
    static AABB bounds(BlockPos pos, BlockState state) {
        return state.getShape(EmptyBlockGetter.INSTANCE, pos).bounds().move(pos);
    }

    /**
     * Each label's picture standing above its box, one screen px per texel. {@code rasters} holds the module's
     * textures from the last frame; it leaves with this frame's, and the rest are released.
     */
    static void drawLabels(GuiGraphics graphics, List<Label> labels, Map<Key, Raster> rasters) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        int scale = Projection.guiScale();
        Map<Key, Raster> drawn = new HashMap<>();
        for (Label label : labels) {
            Vec3 anchor = label.top().add(0.0, label.covers() == null ? TAG_LIFT : CARD_LIFT, 0.0);
            Vec3 screen = Projection.toGui(anchor, graphics.guiWidth(), graphics.guiHeight());
            if (screen == null) {
                continue;
            }
            String distance = Math.round(camera.position().distanceTo(label.top())) + "m";
            Raster raster = drawn.computeIfAbsent(new Key(label.icon(), label.title(), label.covers(), distance, scale),
                    key -> Objects.requireNonNullElseGet(rasters.remove(key), () -> raster(key)));
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) Projection.snap(screen.x, scale), (float) Projection.snap(screen.y, scale));
            graphics.pose().scale(1.0F / scale, 1.0F / scale);
            graphics.blit(raster.id(), -raster.originX(), -raster.originY(),
                    raster.width() - raster.originX(), raster.height() - raster.originY(), 0.0F, 1.0F, 0.0F, 1.0F);
            graphics.pose().popMatrix();
        }
        // The GUI draws after this pass, but these were last submitted in a frame it has already drawn.
        rasters.values().forEach(raster -> minecraft.getTextureManager().release(raster.id()));
        rasters.clear();
        rasters.putAll(drawn);
    }

    /** The label's picture as a texture. */
    private static Raster raster(Key key) {
        Picture picture = picture(key);
        int width = picture.image().getWidth();
        int height = picture.image().getHeight();
        int[] argb = picture.image().getRGB(0, 0, width, height, null, 0, width);
        NativeImage pixels = new NativeImage(width, height, false);
        for (int i = 0; i < argb.length; i++) {
            pixels.setPixel(i % width, i / width, argb[i]);
        }
        Identifier id = Identifier.fromNamespaceAndPath("coldplay", "esp_label/" + textures++);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, pixels));
        return new Raster(id, width, height, picture.originX(), picture.originY());
    }

    /**
     * The label drawn as the design draws it, at {@code key.scale()} screen px per CSS px and shadow included,
     * with the bottom centre of its panel, where it stands, in image px.
     */
    static Picture picture(Key key) {
        int scale = key.scale();
        FontRenderContext context = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, true);
        boolean card = key.covers() != null;
        String title = sentence(key.title());
        Font titleFont = Fonts.SEMIBOLD.deriveFont(card ? 12.5F : 12.0F);
        Font distanceFont = Fonts.MONO.deriveFont(11.0F);
        Font nameFont = Fonts.REGULAR.deriveFont(10.5F);
        Font countFont = Fonts.MONO.deriveFont(10.5F);
        float titleWidth = width(titleFont, title, context);
        float distanceWidth = width(distanceFont, key.distance(), context);
        float width = card
                ? Math.max(CARD_W, CARD_PAD + Math.max(ICON + 7 + titleWidth + 7 + distanceWidth,
                        coversWidth(key.covers(), nameFont, countFont, context)) + CARD_PAD)
                : 1 + 6 + ICON + 6 + titleWidth + 6 + distanceWidth + 10 + 1;
        width = (float) Math.ceil(width * scale) / scale; // whole screen px keep the border sharp
        float height = card ? CARD_H : TAG_H;
        float radius = card ? CARD_R : TAG_H / 2;

        BufferedImage image = new BufferedImage((int) Math.ceil((width + 2 * MARGIN) * scale),
                (int) Math.ceil((height + 2 * MARGIN) * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(shadow(width, height, radius, scale, image.getWidth(), image.getHeight()), 0, 0, null);
        hints(g);
        g.scale(scale, scale);
        g.translate(MARGIN, MARGIN);
        fill(g, PANEL, box(0, 0, width, height, radius));
        fill(g, BORDER, ring(box(0, 0, width, height, radius), box(1, 1, width - 2, height - 2, radius - 1)));
        if (card) {
            icon(g, key.icon(), CARD_PAD, 11);
            text(g, titleFont, title, CARD_PAD + ICON + 7, 18, TEXT);
            text(g, distanceFont, key.distance(), width - CARD_PAD - distanceWidth, 18, DIM);
            float x = CARD_PAD;
            if (key.covers().isEmpty()) {
                text(g, nameFont, NO_DEFENSE, x, 40, DIM);
            }
            for (Cover cover : key.covers()) {
                swatch(g, cover.color(), x, 36);
                x = text(g, nameFont, sentence(cover.name()), x + CHIP + CHIP_GAP, 40, DIM);
                x = text(g, countFont, Long.toString(cover.count()), x + CHIP_GAP, 40, TEXT) + COVER_GAP;
            }
        } else {
            icon(g, key.icon(), 7, 5);
            float x = text(g, titleFont, title, 7 + ICON + 6, 12, TEXT);
            text(g, distanceFont, key.distance(), x + 6, 12, DIM);
        }
        g.dispose();
        return new Picture(image, Math.round((MARGIN + width / 2) * scale), Math.round((MARGIN + height) * scale));
    }

    private static float coversWidth(List<Cover> covers, Font nameFont, Font countFont, FontRenderContext context) {
        if (covers.isEmpty()) {
            return width(nameFont, NO_DEFENSE, context);
        }
        float width = -COVER_GAP;
        for (Cover cover : covers) {
            width += CHIP + CHIP_GAP + width(nameFont, sentence(cover.name()), context)
                    + CHIP_GAP + width(countFont, Long.toString(cover.count()), context) + COVER_GAP;
        }
        return width;
    }

    /**
     * The panel's {@code 0 6px 18px rgba(8,10,16,.22)} shadow in image px, cut away inside the panel as CSS cuts
     * it. Three box blurs each way at radius 9 stand in for the Gaussian of sigma 9.
     */
    private static BufferedImage shadow(float width, float height, float radius, int scale, int imageWidth, int imageHeight) {
        return SHADOWS.computeIfAbsent(List.of(width, height, (float) scale), size -> {
            BufferedImage shadow = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = shadow.createGraphics();
            hints(g);
            g.scale(scale, scale);
            g.translate(MARGIN, MARGIN);
            fill(g, 0xFF000000, box(0, 6, width, height, radius));
            int[] pixels = shadow.getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (pixels[i] >>> 24) << 8; // alpha with 8 fraction bits
            }
            int[] line = new int[Math.max(imageWidth, imageHeight)];
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < imageHeight; y++) {
                    boxBlur(pixels, y * imageWidth, 1, imageWidth, 9 * scale, line);
                }
                for (int x = 0; x < imageWidth; x++) {
                    boxBlur(pixels, x, imageWidth, imageHeight, 9 * scale, line);
                }
            }
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = Math.round(pixels[i] / 256.0F * 0.22F) << 24 | SHADOW;
            }
            shadow.setRGB(0, 0, imageWidth, imageHeight, pixels, 0, imageWidth);
            g.setComposite(AlphaComposite.DstOut);
            fill(g, 0xFF000000, box(0, 0, width, height, radius));
            g.dispose();
            return shadow;
        });
    }

    /** One box blur over {@code count} samples {@code step} apart from {@code start}; outside counts as 0. */
    private static void boxBlur(int[] data, int start, int step, int count, int radius, int[] line) {
        for (int i = 0; i < count; i++) {
            line[i] = data[start + i * step];
        }
        int sum = 0;
        for (int i = 0; i < Math.min(radius, count); i++) {
            sum += line[i];
        }
        for (int i = 0; i < count; i++) {
            if (i + radius < count) {
                sum += line[i + radius];
            }
            if (i - radius > 0) {
                sum -= line[i - radius - 1];
            }
            data[start + i * step] = sum / (2 * radius + 1);
        }
    }

    /** The design's isometric bed, in the bed's dye, or its chest, drawn from a 16-unit box at 14px. */
    private static void icon(Graphics2D g, Block block, float x, float y) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.scale(ICON / 16.0, ICON / 16.0);
        if (block instanceof BedBlock bed) {
            int top = bed.getColor().getTextureDiffuseColor();
            face(g, top, 8, 5, 15, 8.5F, 8, 12, 1, 8.5F);
            face(g, ARGB.scaleRGB(top, 0.78F), 1, 8.5F, 8, 12, 8, 15, 1, 11.5F);
            face(g, ARGB.scaleRGB(top, 0.61F), 8, 12, 15, 8.5F, 15, 11.5F, 8, 15);
            fill(g, 0xFFF2EFE6, path(true, 11.2F, 6.6F, 15, 8.5F, 13.1F, 9.45F, 9.3F, 7.55F));
        } else {
            face(g, 0xFFB98543, 8, 1.5F, 15, 5, 8, 8.5F, 1, 5);
            face(g, 0xFF8E5F25, 1, 5, 8, 8.5F, 8, 15, 1, 11.5F);
            face(g, 0xFF744C1B, 8, 8.5F, 15, 5, 15, 11.5F, 8, 15);
            g.setColor(new Color(0xCC1E1204, true));
            g.setStroke(new BasicStroke(0.8F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4.0F));
            g.draw(path(false, 1, 7.6F, 8, 11.1F, 15, 7.6F));
            fill(g, 0xFFD9DCDF, path(true, 3.6F, 8.1F, 5.2F, 8.9F, 5.2F, 11.1F, 3.6F, 10.3F));
        }
        g.setTransform(saved);
    }

    private static void face(Graphics2D g, int argb, float... points) {
        Path2D face = path(true, points);
        fill(g, argb, face);
        g.setColor(new Color(EDGE, true));
        g.setStroke(new BasicStroke(0.6F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(face);
    }

    private static Path2D path(boolean closed, float... points) {
        Path2D path = new Path2D.Float();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        if (closed) {
            path.closePath();
        }
        return path;
    }

    /** A cover's chip with the design's 1px light ring outside and 1px dark ring inside. */
    private static void swatch(Graphics2D g, int argb, float x, float y) {
        RoundRectangle2D chip = box(x, y, CHIP, CHIP, 2);
        fill(g, 0x1FFFFFFF, ring(box(x - 1, y - 1, CHIP + 2, CHIP + 2, 3), chip));
        fill(g, argb, chip);
        fill(g, 0x4D000000, ring(chip, box(x + 1, y + 1, CHIP - 2, CHIP - 2, 1)));
    }

    /**
     * Draws text from x with its line box centred on centreY, as CSS centres a line-height 1 span, with the
     * ascent and descent rounded to whole px as Blink rounds them; returns its end.
     */
    private static float text(Graphics2D g, Font font, String text, float x, float centreY, int color) {
        TextLayout layout = new TextLayout(text, font, g.getFontRenderContext());
        float baseline = centreY + (Math.round(layout.getAscent()) - Math.round(layout.getDescent())) / 2.0F;
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));
        // Chrome on Windows sets these glyphs about 12% heavier than Java2D; a hairline around each one matches it.
        Area glyphs = new Area(outline);
        glyphs.add(new Area(new BasicStroke(EMBOLDEN / (float) g.getTransform().getScaleX(),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(outline)));
        fill(g, color, glyphs);
        return x + layout.getAdvance();
    }

    private static float width(Font font, String text, FontRenderContext context) {
        return (float) font.getStringBounds(text, context).getWidth();
    }

    /** "Red Bed" as the design writes it: "Red bed". */
    private static String sentence(String text) {
        return text.isEmpty() ? text : text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT);
    }

    private static RoundRectangle2D box(float x, float y, float width, float height, float radius) {
        return new RoundRectangle2D.Float(x, y, width, height, 2 * radius, 2 * radius);
    }

    private static Area ring(Shape outer, Shape inner) {
        Area ring = new Area(outer);
        ring.subtract(new Area(inner));
        return ring;
    }

    private static void fill(Graphics2D g, int argb, Shape shape) {
        g.setColor(new Color(argb, true));
        g.fill(shape);
    }

    private static void hints(Graphics2D g) {
        g.addRenderingHints(Map.of(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON,
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON,
                RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE,
                RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY));
    }

    /** One box's HUD label, from the top centre of its box; covers is null for a chest tag, the defense row for a bed card. */
    record Label(Vec3 top, Block icon, String title, List<Cover> covers) {
    }

    /** A defense block on a bed card: its swatch colour, its name and how many there are. */
    record Cover(int color, String name, long count) {
    }

    /** Everything a label shows at one GUI scale; equal keys share a texture. */
    record Key(Block icon, String title, List<Cover> covers, String distance, int scale) {
    }

    /** A label's texture, its size in screen px, and where its panel stands in it. */
    record Raster(Identifier id, int width, int height, int originX, int originY) {
    }

    record Picture(BufferedImage image, int originX, int originY) {
    }

    /** Geist, loaded with the first label rather than with the module, and kerned as a browser kerns it. */
    private static final class Fonts {
        static final Font REGULAR = load("geist-regular.ttf");
        static final Font SEMIBOLD = load("geist-semibold.ttf");
        static final Font MONO = load("geist-mono-medium.ttf");

        private static Font load(String file) {
            try (InputStream input = ChestESP.class.getResourceAsStream("/assets/coldplay/font/" + file)) {
                return Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(input, file)).deriveFont(Map.of(
                        TextAttribute.KERNING, TextAttribute.KERNING_ON,
                        TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
            } catch (IOException | FontFormatException failure) {
                throw new IllegalStateException(file, failure);
            }
        }
    }
}
