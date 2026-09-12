package com.firstclient.screen;

import com.firstclient.FirstClient;
import com.firstclient.afk.CommandExecutor;
import com.firstclient.config.FirstClientConfig;
import com.firstclient.keybind.ModKeybindings;
import com.firstclient.autofarm.AutoFarmManager;
import com.firstclient.mobfarm.MobFarmManager;
import com.firstclient.screen.theme.Theme;
import com.firstclient.util.Feedback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Modern dark configuration menu for FirstClient.
 *
 * <p>Layout: centered panel, header with the First/Client title, left sidebar
 * with categories (AFK active, others reserved), scrollable content column.
 * Rendering is immediate-mode on top of a small retained set of vanilla
 * {@link TextFieldWidget}s for text input. Open animation is fade + slide-up;
 * close animation is a short fade handled by delaying the actual screen swap.
 */
public final class FirstClientScreen extends Screen {
    // Catégories de la sidebar. Pour ajouter un module, ajouter son nom ici
    // et brancher sa page dans renderContent.
    private static final String[] CATEGORIES = {"AFK", "Mob Farm", "Auto Farm"};
    private static final int PAGE_AFK = 0;
    private static final int PAGE_MOB_FARM = 1;
    private static final int PAGE_AUTO_FARM = 2;
    private static final int NO_EDIT = -2;
    private static final int ADDING = -1;
    /** Texte des champs légèrement agrandi, aligné à gauche avec marge de 10 px. */
    private static final float FIELD_TEXT_SCALE = 1.1F;

    private final FirstClientConfig config = FirstClient.getConfig();

    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget toleranceField;
    private TextFieldWidget dimensionField;
    private TextFieldWidget initialDelayField;
    private TextFieldWidget minDelayField;
    private TextFieldWidget maxDelayField;
    private TextFieldWidget cmdField;
    private TextFieldWidget cmdDelayField;
    private TextFieldWidget rangeField;
    private TextFieldWidget cpsField;
    private TextFieldWidget farmRangeField;

    private final List<Zone> zones = new ArrayList<>();
    private final List<Toast> toasts = new ArrayList<>();
    /** Tracks fields forced read-only (TextFieldWidget has no public getter). */
    private final Set<TextFieldWidget> readOnlyFields = new HashSet<>();

    private long openTimeMs;
    private boolean closing;
    private long closingStartMs;
    /**
     * Le flou "plein écran" que voyait l'utilisateur n'est pas dessiné par ce
     * mod : c'est le flou d'arrière-plan natif de Minecraft (option vidéo
     * "Menu Background Blurriness"), qui s'applique automatiquement derrière
     * n'importe quel Screen. On le coupe pendant que ce menu est ouvert et on
     * remet la valeur d'origine à la fermeture, pour ne garder que notre
     * propre voile léger dessiné juste derrière le panneau.
     */
    private Integer previousBlurriness;

    private int contentScroll;
    private int maxScroll;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    private int editingIndex = NO_EDIT;
    private int hoveredZone = -1;
    private int selectedCategory = PAGE_AFK;

    private FirstClientScreen() {
        super(Text.literal("FirstClient"));
    }

    public static void open(MinecraftClient client) {
        client.setScreen(new FirstClientScreen());
    }

    /** Starts the animated close (safe to call from keybinds and buttons). */
    public void requestClose() {
        if (closing) {
            return;
        }
        closing = true;
        closingStartMs = Util.getMeasuringTimeMs();
        config.save();
    }

    @Override
    protected void init() {
        openTimeMs = closing ? openTimeMs : Util.getMeasuringTimeMs();
        if (previousBlurriness == null && client != null) {
            try {
                previousBlurriness = client.options.getMenuBackgroundBlurriness().getValue();
                client.options.getMenuBackgroundBlurriness().setValue(0);
            } catch (Exception e) {
                // Nom de méthode différent selon la version/les mappings : on
                // ignore plutôt que de crasher l'ouverture du menu.
                previousBlurriness = null;
            }
        }
        if (xField == null) {
            xField = makeField(160, fmtCoord(config.targetX));
            xField.setChangedListener(s -> tryParseDouble(s, v -> config.targetX = v));
            yField = makeField(160, fmtCoord(config.targetY));
            yField.setChangedListener(s -> tryParseDouble(s, v -> config.targetY = v));
            zField = makeField(160, fmtCoord(config.targetZ));
            zField.setChangedListener(s -> tryParseDouble(s, v -> config.targetZ = v));
            toleranceField = makeField(64, fmtNum(config.tolerance));
            toleranceField.setChangedListener(s -> tryParseDouble(s, v -> config.tolerance = clamp(v, 0.1, 16.0)));
            dimensionField = makeField(200, config.targetDimension);
            dimensionField.setChangedListener(s -> {
                if (!s.isBlank()) config.targetDimension = s.trim();
            });
            initialDelayField = makeField(80, Integer.toString(config.delayAfterTeleportMs));
            initialDelayField.setChangedListener(s -> tryParseInt(s, v -> config.delayAfterTeleportMs = clamp(v, 0, 60000)));
            minDelayField = makeField(80, Integer.toString(config.randomDelayMinMs));
            minDelayField.setChangedListener(s -> tryParseInt(s, v -> {
                config.randomDelayMinMs = clamp(v, 0, 60000);
                if (config.randomDelayMinMs > config.randomDelayMaxMs) {
                    config.randomDelayMaxMs = config.randomDelayMinMs;
                    maxDelayField.setText(Integer.toString(config.randomDelayMaxMs));
                }
            }));
            maxDelayField = makeField(80, Integer.toString(config.randomDelayMaxMs));
            maxDelayField.setChangedListener(s -> tryParseInt(s, v -> {
                config.randomDelayMaxMs = clamp(v, 0, 60000);
                if (config.randomDelayMaxMs < config.randomDelayMinMs) {
                    config.randomDelayMinMs = config.randomDelayMaxMs;
                    minDelayField.setText(Integer.toString(config.randomDelayMinMs));
                }
            }));
            cmdField = makeField(300, "");
            cmdDelayField = makeField(80, "500");
            rangeField = makeField(80, fmtNum(config.mobFarmRange));
            rangeField.setChangedListener(s -> tryParseDouble(s, v -> config.mobFarmRange = clamp(v, 1.0, 8.0)));
            cpsField = makeField(80, Integer.toString(config.mobFarmCps));
            cpsField.setChangedListener(s -> tryParseInt(s, v -> config.mobFarmCps = clamp(v, 1, 20)));
            farmRangeField = makeField(80, fmtNum(config.autoFarmRange));
            farmRangeField.setChangedListener(s -> tryParseDouble(s, v -> config.autoFarmRange = clamp(v, 2.0, 48.0)));

            addDrawableChild(xField);
            addDrawableChild(yField);
            addDrawableChild(zField);
            addDrawableChild(toleranceField);
            addDrawableChild(dimensionField);
            addDrawableChild(initialDelayField);
            addDrawableChild(minDelayField);
            addDrawableChild(maxDelayField);
            addDrawableChild(cmdField);
            addDrawableChild(cmdDelayField);
            addDrawableChild(rangeField);
            addDrawableChild(cpsField);
            addDrawableChild(farmRangeField);
        }
        contentScroll = MathHelper.clamp(contentScroll, 0, Math.max(0, maxScroll));
    }

    private TextFieldWidget makeField(int width, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, -1000, width, 30, Text.empty());
        field.setMaxLength(64);
        field.setDrawsBackground(false);
        field.setEditableColor(0xFFFFFFFF);
        field.setUneditableColor(0xFF6B7484);
        field.setText(value);
        return field;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing) {
            return true;
        }
        boolean childHandled = super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            for (int i = zones.size() - 1; i >= 0; i--) {
                Zone zone = zones.get(i);
                if (zone.contains(mouseX, mouseY)) {
                    defocusAllFields();
                    zone.action.run();
                    return true;
                }
            }
        }
        return childHandled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (closing) {
            return true;
        }
        if (mouseX >= contentX && mouseX <= contentX + contentW && mouseY >= contentY && mouseY <= contentY + contentH) {
            contentScroll = MathHelper.clamp(contentScroll - (int) Math.round(verticalAmount * 18.0), 0, Math.max(0, maxScroll));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            requestClose();
            return true;
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            config.save();
            addToast("Configuration enregistrée", Theme.SUCCESS);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (closing) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void close() {
        requestClose();
    }

    @Override
    public void removed() {
        config.save();
        restoreBlur();
    }

    /** Remet la flou d'arrière-plan vanilla tel qu'il était avant l'ouverture. */
    private void restoreBlur() {
        if (previousBlurriness != null && client != null) {
            try {
                client.options.getMenuBackgroundBlurriness().setValue(previousBlurriness);
            } catch (Exception ignored) {
                // Best effort.
            }
            previousBlurriness = null;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void defocusAllFields() {
        for (TextFieldWidget field : allFields()) {
            field.setFocused(false);
        }
    }

    private TextFieldWidget[] allFields() {
        return new TextFieldWidget[]{xField, yField, zField, toleranceField, dimensionField,
                initialDelayField, minDelayField, maxDelayField, cmdField, cmdDelayField,
                rangeField, cpsField, farmRangeField};
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();

        float alpha = 1.0F;
        float slide = 0.0F;
        if (closing) {
            float progress = (now - closingStartMs) / (float) Theme.CLOSE_MS;
            if (progress >= 1.0F) {
                if (client != null) {
                    client.setScreen(null);
                }
                return;
            }
            alpha = 1.0F - progress;
            slide = progress * 8.0F;
        } else {
            float progress = (now - openTimeMs) / (float) Theme.OPEN_MS;
            float eased = Theme.easeOutCubic(MathHelper.clamp(progress, 0.0F, 1.0F));
            alpha = eased;
            slide = (1.0F - eased) * Theme.SLIDE_PX;
        }

        // Pas de flou plein écran : le monde reste net et visible derrière le menu.
        // Voile global très léger + ombre portée autour du panneau uniquement.
        // (Un vrai flou localisé n'est pas possible avec l'API vanilla 1.21.1
        // sans shader custom : le flou vanilla ne s'applique qu'à tout l'écran.)
        context.fill(0, 0, width, height, Theme.withAlpha(0xB4000000, alpha * 0.30F));

        int panelW = Math.min(530, width - 24);
        int panelH = Math.min(365, height - 24);
        int panelX = (width - panelW) / 2;
        int panelY = (int) ((height - panelH) / 2 + slide);

        zones.clear();
        hoveredZone = -1;

        // Ombre portée douce en deux couches, autour du panneau uniquement.
        context.fill(panelX + 6, panelY + 10, panelX + panelW + 6, panelY + panelH + 10, Theme.withAlpha(0x50000000, alpha));
        context.fill(panelX + 2, panelY + 4, panelX + panelW + 2, panelY + panelH + 4, Theme.withAlpha(0xA0000000, alpha));
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, Theme.withAlpha(Theme.PANEL, alpha));
        context.drawBorder(panelX, panelY, panelW, panelH, Theme.withAlpha(Theme.PANEL_BORDER, alpha));

        int headerH = 40;
        int sidebarW = 132;
        renderHeader(context, panelX, panelY, panelW, headerH, alpha, mouseX, mouseY);
        renderSidebar(context, panelX, panelY + headerH, sidebarW, panelH - headerH, alpha, mouseX, mouseY);

        contentX = panelX + sidebarW;
        contentY = panelY + headerH;
        contentW = panelW - sidebarW;
        contentH = panelH - headerH;
        renderContent(context, contentX, contentY, contentW, contentH, alpha, mouseX, mouseY, now);

        // Les champs sont rendus séparément avec une légère réduction du texte.
        // Leurs dimensions réelles restent inchangées pour les clics/focus.
        context.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        renderFields(context, mouseX, mouseY, delta);
        context.disableScissor();

        // Dernier rendu : les toasts passent au-dessus des champs et de tous
        // les textes du contenu.
        renderToasts(context, panelX + panelW - 12, panelY + headerH + 10, alpha, now);
    }

    /**
     * Rend les champs avec un texte légèrement agrandi, aligné à gauche avec
     * 10 px de marge (et 10 px depuis le haut). La géométrie des widgets reste
     * inchangée pour conserver clics et focus ; seul le rendu est transformé.
     * Le point d'ancrage est l'origine du texte vanilla (marge interne ~4 px,
     * texte de 8 px centré verticalement), repositionnée à (x+10, y+10).
     */
    private void renderFields(DrawContext context, int mouseX, int mouseY, float delta) {
        for (TextFieldWidget field : allFields()) {
            if (field == null || !field.isVisible()) {
                continue;
            }

            float originX = field.getX() + 4.0F;
            float originY = field.getY() + (field.getHeight() - 8) / 2.0F;
            float targetX = field.getX() + 10.0F;
            float targetY = field.getY() + 10.0F;

            context.getMatrices().push();
            context.getMatrices().translate(targetX, targetY, 0.0F);
            context.getMatrices().scale(FIELD_TEXT_SCALE, FIELD_TEXT_SCALE, 1.0F);
            context.getMatrices().translate(-originX, -originY, 0.0F);
            field.render(context, mouseX, mouseY, delta);
            context.getMatrices().pop();
        }
    }

    private void renderHeader(DrawContext context, int x, int y, int w, int h, float alpha, int mouseX, int mouseY) {
        int firstW = textRenderer.getWidth("First");
        int titleX = x + 18;
        int titleY = y + (h - 12) / 2 - 2;
        context.drawText(textRenderer, Text.literal("First"), titleX, titleY, Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        context.drawText(textRenderer, Text.literal("Client"), titleX + firstW, titleY, Theme.withAlpha(Theme.ACCENT, alpha), false);
        context.drawText(textRenderer, Text.literal("v" + FirstClient.MOD_VERSION),
                titleX, titleY + 11, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);

        // Close button.
        int bx = x + w - 30;
        int by = y + (h - 22) / 2;
        boolean hover = inBox(mouseX, mouseY, bx, by, 22, 22);
        context.fill(bx, by, bx + 22, by + 22, Theme.withAlpha(hover ? 0xFF2A3140 : 0xFF1C212B, alpha));
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("x"), bx + 11, by + 7,
                Theme.withAlpha(hover ? Theme.DANGER : Theme.TEXT_SECONDARY, alpha));
        addZone(bx, by, 22, 22, this::requestClose, mouseX, mouseY);

        // Divider.
        context.fill(x + 1, y + h - 1, x + w - 1, y + h, Theme.withAlpha(Theme.PANEL_BORDER, alpha * 0.7F));
    }

    private void renderSidebar(DrawContext context, int x, int y, int w, int h, float alpha, int mouseX, int mouseY) {
        context.fill(x, y, x + w, y + h, Theme.withAlpha(Theme.SIDEBAR, alpha));
        context.fill(x + w - 1, y, x + w, y + h, Theme.withAlpha(Theme.PANEL_BORDER, alpha * 0.7F));

        int rowY = y + 10;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int page = i;
            String name = CATEGORIES[page];
            boolean active = page == selectedCategory;
            boolean hover = inBox(mouseX, mouseY, x + 8, rowY, w - 16, 30);
            if (active) {
                context.fill(x + 8, rowY, x + w - 8, rowY + 30, Theme.withAlpha(Theme.ACCENT_DIM, alpha));
                context.fill(x + 8, rowY, x + 10, rowY + 30, Theme.withAlpha(Theme.ACCENT, alpha));
            } else if (hover) {
                context.fill(x + 8, rowY, x + w - 8, rowY + 30, Theme.withAlpha(Theme.CARD_HOVER, alpha));
            }
            int dotColor = active ? Theme.ACCENT : Theme.TEXT_MUTED;
            context.fill(x + 20, rowY + 12, x + 26, rowY + 18, Theme.withAlpha(dotColor, alpha));
            context.drawText(textRenderer, Text.literal(name), x + 32, rowY + 11,
                    Theme.withAlpha(active ? Theme.TEXT_PRIMARY : Theme.TEXT_SECONDARY, alpha), false);
            addZone(x + 8, rowY, w - 16, 30, () -> {
                selectedCategory = page;
                contentScroll = 0;
            }, mouseX, mouseY);
            rowY += 34;
        }
        String keyHint;
        try {
            keyHint = "Touche : " + ModKeybindings.openMenu.getBoundKeyLocalizedText().getString();
        } catch (Exception e) {
            keyHint = "Maj droite : ouvrir / fermer";
        }
        context.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(keyHint, w - 28)),
                x + 14, y + h - 16, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
    }

    private void renderContent(DrawContext context, int x, int y, int w, int h, float alpha, int mouseX, int mouseY, long now) {
        int pad = 16;
        int innerX = x + pad;
        int innerW = w - pad * 2;

        context.enableScissor(x, y, x + w, y + h);
        int cursor = y + 12 - contentScroll;

        if (selectedCategory == PAGE_MOB_FARM) {
            hideFields(xField, yField, zField, toleranceField, dimensionField,
                    initialDelayField, minDelayField, maxDelayField, cmdField, cmdDelayField,
                    farmRangeField);
            cursor = renderMobFarmPage(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
        } else if (selectedCategory == PAGE_AUTO_FARM) {
            hideFields(xField, yField, zField, toleranceField, dimensionField,
                    initialDelayField, minDelayField, maxDelayField, cmdField, cmdDelayField,
                    rangeField, cpsField);
            cursor = renderAutoFarmPage(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
        } else {
            hideFields(rangeField, cpsField, farmRangeField);
            cursor = renderGeneralCard(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
            cursor = renderTargetCard(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
            cursor = renderCommandsCard(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
            cursor = renderTimingCard(context, innerX, cursor, innerW, alpha, mouseX, mouseY);
            cursor += 12;
        }

        // Le footer inférieur a été retiré : les actions principales restent
        // accessibles via l'interface, sans bloc inutile en bas du contenu.

        // `cursor` inclut déjà -contentScroll (voir ligne du dessus) : il ne
        // faut donc pas s'en servir tel quel pour calculer maxScroll, sinon
        // la limite de scroll se recalcule plus petite à chaque fois qu'on
        // scrolle, et on n'atteint jamais le vrai bas du contenu ("Délais"
        // qui semblait coupé). On annule ce décalage avant de calculer.
        int unscrolledBottom = cursor + contentScroll;
        maxScroll = Math.max(0, unscrolledBottom - 6 - (y + h));
        contentScroll = MathHelper.clamp(contentScroll, 0, maxScroll);
        context.disableScissor();

        // Scrollbar.
        if (maxScroll > 0) {
            int trackX = x + w - 7;
            int trackY = y + 8;
            int trackH = h - 16;
            context.fill(trackX, trackY, trackX + 3, trackY + trackH, Theme.withAlpha(0xFF232A38, alpha));
            int thumbH = Math.max(24, trackH * trackH / (trackH + maxScroll));
            int thumbY = trackY + (trackH - thumbH) * contentScroll / maxScroll;
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, Theme.withAlpha(Theme.ACCENT, alpha * 0.8F));
        }
    }

    private int renderGeneralCard(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        sectionTitle(context, x, y, "GÉNÉRAL", alpha);
        y += 18;
        int cardH = 48;
        drawCard(context, x, y, w, cardH, alpha);
        context.drawText(textRenderer, Text.literal("Automatisation AFK"), x + 14, y + 12,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        String hint = config.enabled ? "Détecte les téléports et exécute les commandes" : "Automatisation en pause";
        context.drawText(textRenderer, Text.literal(hint), x + 14, y + 25,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        drawToggle(context, x + w - 14 - 40, y + 14, 40, 20, config.enabled, alpha, mouseX, mouseY,
                () -> {
                    config.enabled = !config.enabled;
                    config.save();
                    addToast(config.enabled ? "Automatisation AFK activée" : "Automatisation AFK en pause", Theme.ACCENT);
                    Feedback.actionBar(client, config.enabled ? "FirstClient activé" : "FirstClient en pause");
                });
        // Le champ de commande est géré entièrement par renderCommandsCard ;
        // le cacher ici aussi lui retirait le focus à chaque frame et
        // empêchait d'y taper du texte.
        return y + cardH;
    }

    private int renderTargetCard(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        sectionTitle(context, x, y, "POSITION CIBLE", alpha);
        y += 18;
        int top = y;
        int cardH = 168;
        drawCard(context, x, top, w, cardH, alpha);

        int colW = (w - 28 - 16) / 3;
        int fx = x + 14;
        context.drawText(textRenderer, Text.literal("X"), fx, top + 12, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Y"), fx + colW + 8, top + 12, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Z"), fx + 2 * (colW + 8), top + 12, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        placeField(context, xField, fx, top + 24, colW, 30, alpha);
        placeField(context, yField, fx + colW + 8, top + 24, colW, 30, alpha);
        placeField(context, zField, fx + 2 * (colW + 8), top + 24, colW, 30, alpha);

        int row2 = top + 62;
        context.drawText(textRenderer, Text.literal("Tolérance (blocs)"), fx, row2, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Dimension"), fx + 150, row2, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        placeField(context, toleranceField, fx, row2 + 12, 130, 30, alpha);
        placeField(context, dimensionField, fx + 150, row2 + 12, w - 28 - 150, 30, alpha);

        int row3 = top + 112;
        drawButton(context, fx, row3, 170, 22, "Utiliser ma position", true, alpha, mouseX, mouseY, this::fillCurrentPosition);
        drawButton(context, fx + 178, row3, 104, 22, "Réinitialiser", false, alpha, mouseX, mouseY,
                () -> {
                    config.resetTargetToDefault();
                    refreshCoordFields();
                    addToast("Position cible réinitialisée", Theme.ACCENT);
                });
        // Sur sa propre ligne : ne peut jamais déborder du panneau, quelle que
        // soit sa largeur.
        String chunkHint = String.format(Locale.ROOT, "Chunk actuel : %d, %d",
                ((int) Math.floor(config.targetX)) >> 4, ((int) Math.floor(config.targetZ)) >> 4);
        context.drawText(textRenderer, Text.literal(chunkHint), fx, row3 + 32,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        return top + cardH;
    }

    private int renderCommandsCard(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        sectionTitle(context, x, y, "COMMANDES  (" + config.commands.size() + ")", alpha);
        int addW = 100;
        drawButton(context, x + w - addW, y - 4, addW, 20, "+ Ajouter", true, alpha, mouseX, mouseY, this::beginAddCommand);
        y += 18;
        int top = y;

        int listY = y + 12;
        if (config.commands.isEmpty()) {
            drawCard(context, x, y, w, 52, alpha);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Aucune commande pour le moment. Ajoutez-en une pour commencer."),
                    x + w / 2, y + 22, Theme.withAlpha(Theme.TEXT_MUTED, alpha));
            listY = y + 52;
        } else {
            for (int i = 0; i < config.commands.size(); i++) {
                final int index = i;
                FirstClientConfig.CommandEntry entry = config.commands.get(index);
                int cardH = 50;
                boolean selected = index == editingIndex;
                boolean visible = listY + cardH >= contentY && listY <= contentY + contentH;
                if (visible) {
                    context.fill(x + 1, listY + 2, x + w + 1, listY + cardH + 2, Theme.withAlpha(0x80000000, alpha * 0.6F));
                    context.fill(x, listY, x + w, listY + cardH,
                            Theme.withAlpha(selected ? 0xFF222B3D : (inBox(mouseX, mouseY, x, listY, w, cardH) ? Theme.CARD_HOVER : Theme.CARD), alpha));
                    if (selected) {
                        context.drawBorder(x, listY, w, cardH, Theme.withAlpha(Theme.CARD_SELECTED_BORDER, alpha));
                    }
                    String name = CommandExecutor.displayName(entry.command.isEmpty() ? "(vide)" : entry.command);
                    if (textRenderer.getWidth(name) > w - 200) {
                        name = textRenderer.trimToWidth(name, w - 200) + "…";
                    }
                    context.drawText(textRenderer, Text.literal(name), x + 12, listY + 9,
                            Theme.withAlpha(entry.enabled ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED, alpha), false);
                    context.drawText(textRenderer, Text.literal("Délai avant : " + entry.delayBeforeMs + " ms"), x + 12, listY + 24,
                            Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);

                    drawToggle(context, x + w - 14 - 36, listY + 8, 36, 18, entry.enabled, alpha, mouseX, mouseY,
                            indexedAction(index, () -> {
                                FirstClientConfig.CommandEntry e = config.commands.get(index);
                                e.enabled = !e.enabled;
                                config.save();
                            }));
                    int btnY = listY + 28;
                    drawMiniButton(context, x + w - 14 - 44, btnY, 44, 14, "Éditer", alpha, mouseX, mouseY,
                            indexedAction(index, () -> beginEditCommand(index)));
                    drawMiniButton(context, x + w - 14 - 92, btnY, 44, 14, "Suppr.", alpha, mouseX, mouseY,
                            indexedAction(index, () -> {
                                config.commands.remove(index);
                                if (editingIndex == index) {
                                    editingIndex = NO_EDIT;
                                } else if (editingIndex > index) {
                                    editingIndex--;
                                }
                                config.save();
                                addToast("Commande supprimée", Theme.TEXT_SECONDARY);
                            }));
                    drawMiniButton(context, x + 12 + textRenderer.getWidth(name) + 8, listY + 7, 18, 14, "^", alpha, mouseX, mouseY,
                            indexedAction(index, () -> moveCommand(index, -1)));
                    drawMiniButton(context, x + 12 + textRenderer.getWidth(name) + 32, listY + 7, 18, 14, "v", alpha, mouseX, mouseY,
                            indexedAction(index, () -> moveCommand(index, 1)));
                }
                listY += cardH + 8;
            }
        }

        // Inline editor.
        if (editingIndex != NO_EDIT) {
            int editorH = 88;
            boolean visible = listY + editorH >= contentY && listY <= contentY + contentH;
            if (visible) {
                context.fill(x, listY, x + w, listY + editorH, Theme.withAlpha(0xFF202839, alpha));
                context.drawBorder(x, listY, w, editorH, Theme.withAlpha(Theme.ACCENT, alpha * 0.7F));
                String title = editingIndex == ADDING ? "Nouvelle commande" : "Commande n°" + (editingIndex + 1);
                context.drawText(textRenderer, Text.literal(title), x + 12, listY + 8,
                        Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
                placeField(context, cmdField, x + 12, listY + 22, w - 140, 30, alpha);
                placeField(context, cmdDelayField, x + w - 116, listY + 22, 60, 30, alpha);
                context.drawText(textRenderer, Text.literal("ms"), x + w - 50, listY + 33,
                        Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
                drawButton(context, x + 12, listY + 58, 100, 20, "Enregistrer", true, alpha, mouseX, mouseY, this::saveEditor);
                drawButton(context, x + 120, listY + 58, 90, 20, "Annuler", false, alpha, mouseX, mouseY,
                        () -> editingIndex = NO_EDIT);
            } else {
                hideFields(cmdField, cmdDelayField);
            }
            listY += editorH + 8;
        } else {
            hideFields(cmdField, cmdDelayField);
        }

        int bottom = Math.max(listY, y + 12);
        return bottom;
    }

    private int renderTimingCard(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        sectionTitle(context, x, y, "DÉLAIS", alpha);
        y += 18;
        int top = y;
        int cardH = 140;
        drawCard(context, x, top, w, cardH, alpha);

        context.drawText(textRenderer, Text.literal("Délai après téléportation"), x + 14, top + 12,
                Theme.withAlpha(Theme.TEXT_SECONDARY, alpha), false);
        placeField(context, initialDelayField, x + 14, top + 26, 90, 30, alpha);
        context.drawText(textRenderer, Text.literal("ms  (0 - 60000)"), x + 112, top + 31,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);

        context.drawText(textRenderer, Text.literal("Délai aléatoire"), x + 14, top + 64,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        drawToggle(context, x + w - 14 - 40, top + 60, 40, 20, config.randomDelayEnabled, alpha, mouseX, mouseY,
                () -> {
                    config.randomDelayEnabled = !config.randomDelayEnabled;
                    config.save();
                });

        boolean randomOn = config.randomDelayEnabled;
        int dimText = randomOn ? Theme.TEXT_MUTED : 0xFF4A5262;
        context.drawText(textRenderer, Text.literal("Minimum"), x + 14, top + 88,
                Theme.withAlpha(dimText, alpha), false);
        context.drawText(textRenderer, Text.literal("Maximum"), x + 150, top + 88,
                Theme.withAlpha(dimText, alpha), false);
        setFieldEditable(minDelayField, randomOn);
        setFieldEditable(maxDelayField, randomOn);
        placeField(context, minDelayField, x + 14, top + 100, 90, 30, alpha);
        placeField(context, maxDelayField, x + 150, top + 100, 90, 30, alpha);
        return top + cardH;
    }

    private int renderMobFarmPage(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        MobFarmManager mobFarm = MobFarmManager.getInstance();

        sectionTitle(context, x, y, "MOB FARM", alpha);
        y += 18;
        int cardH = 48;
        drawCard(context, x, y, w, cardH, alpha);
        context.drawText(textRenderer, Text.literal("Mob Farm"), x + 14, y + 12,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        String hint = config.mobFarmEnabled ? "Frappe le mob verrouillé en boucle" : "Module coupé";
        context.drawText(textRenderer, Text.literal(hint), x + 14, y + 25,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        drawToggle(context, x + w - 14 - 40, y + 14, 40, 20, config.mobFarmEnabled, alpha, mouseX, mouseY,
                () -> {
                    mobFarm.toggle();
                    addToast(config.mobFarmEnabled ? "Mob Farm activé" : "Mob Farm coupé", Theme.ACCENT);
                });
        y += cardH + 12;

        sectionTitle(context, x, y, "CIBLAGE", alpha);
        y += 18;
        int targetingH = 106;
        drawCard(context, x, y, w, targetingH, alpha);
        context.drawText(textRenderer, Text.literal("Portée (blocs)"), x + 14, y + 12,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Coups par seconde"), x + 150, y + 12,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        placeField(context, rangeField, x + 14, y + 26, 110, 30, alpha);
        placeField(context, cpsField, x + 150, y + 26, 110, 30, alpha);
        context.drawText(textRenderer, Text.literal("Acquisition du mob le plus proche, puis verrouillage."),
                x + 14, y + 64, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Les autres mobs sont ignorés tant que la cible vit."),
                x + 14, y + 76, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("L'épée de la barre d'action est sélectionnée auto."),
                x + 14, y + 88, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        y += targetingH + 12;

        sectionTitle(context, x, y, "ÉTAT", alpha);
        y += 18;
        int statusH = 56;
        drawCard(context, x, y, w, statusH, alpha);
        int dot = config.mobFarmEnabled ? (mobFarm.isLocked() ? Theme.ACCENT : Theme.SUCCESS) : Theme.TEXT_MUTED;
        context.fill(x + 14, y + 14, x + 20, y + 20, Theme.withAlpha(dot, alpha));
        context.drawText(textRenderer, Text.literal(mobFarm.getStatusLine()), x + 26, y + 12,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        context.drawText(textRenderer, Text.literal(mobFarm.getTargetLine()), x + 26, y + 26,
                Theme.withAlpha(Theme.TEXT_SECONDARY, alpha), false);
        String farmKey;
        try {
            farmKey = "Touche " + ModKeybindings.toggleMobFarm.getBoundKeyLocalizedText().getString() + " : activer / couper";
        } catch (Exception e) {
            farmKey = "Touche G : activer / couper";
        }
        context.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(farmKey, w - 28)),
                x + 14, y + 42, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        y += statusH;
        return y;
    }

    private int renderAutoFarmPage(DrawContext context, int x, int y, int w, float alpha, int mouseX, int mouseY) {
        AutoFarmManager autoFarm = AutoFarmManager.getInstance();

        sectionTitle(context, x, y, "AUTO FARM", alpha);
        y += 18;
        int cardH = 48;
        drawCard(context, x, y, w, cardH, alpha);
        context.drawText(textRenderer, Text.literal("Auto Farm"), x + 14, y + 12,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        String hint = config.autoFarmEnabled ? "Récolte les cultures mûres" : "Module coupé";
        context.drawText(textRenderer, Text.literal(hint), x + 14, y + 25,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        drawToggle(context, x + w - 14 - 40, y + 14, 40, 20, config.autoFarmEnabled, alpha, mouseX, mouseY,
                () -> {
                    autoFarm.toggle();
                    addToast(config.autoFarmEnabled ? "Auto Farm activé" : "Auto Farm coupé", Theme.ACCENT);
                });
        y += cardH + 12;

        sectionTitle(context, x, y, "RÉGLAGES", alpha);
        y += 18;
        int settingsH = 92;
        drawCard(context, x, y, w, settingsH, alpha);
        context.drawText(textRenderer, Text.literal("Portée (blocs)"), x + 14, y + 12,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        placeField(context, farmRangeField, x + 14, y + 26, 110, 30, alpha);
        context.drawText(textRenderer, Text.literal("blocs  (2 - 48)"), x + 132, y + 31,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Ne cible qu'à la hauteur du joueur (champ plat)."),
                x + 14, y + 64, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        context.drawText(textRenderer, Text.literal("Se coupe quand il n'y a plus rien à récolter."),
                x + 14, y + 76, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        y += settingsH + 12;

        sectionTitle(context, x, y, "ÉTAT", alpha);
        y += 18;
        int statusH = 56;
        drawCard(context, x, y, w, statusH, alpha);
        int dot = config.autoFarmEnabled ? Theme.SUCCESS : Theme.TEXT_MUTED;
        context.fill(x + 14, y + 13, x + 20, y + 19, Theme.withAlpha(dot, alpha));
        context.drawText(textRenderer, Text.literal(autoFarm.getStatusLine()), x + 26, y + 10,
                Theme.withAlpha(Theme.TEXT_PRIMARY, alpha), false);
        context.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(autoFarm.getTargetLine(), w - 40)),
                x + 26, y + 24, Theme.withAlpha(Theme.TEXT_SECONDARY, alpha), false);
        String farmKey;
        try {
            farmKey = "Touche " + ModKeybindings.toggleAutoFarm.getBoundKeyLocalizedText().getString() + " : activer / couper";
        } catch (Exception e) {
            farmKey = "Touche H : activer / couper";
        }
        context.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(farmKey, w - 28)),
                x + 14, y + 38, Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
        y += statusH;
        return y;
    }

    // ---------------------------------------------------------------- helpers

    private void sectionTitle(DrawContext context, int x, int y, String title, float alpha) {
        context.drawText(textRenderer, Text.literal(title), x + 2, y + 2,
                Theme.withAlpha(Theme.TEXT_MUTED, alpha), false);
    }

    private void drawCard(DrawContext context, int x, int y, int w, int h, float alpha) {
        context.fill(x + 1, y + 2, x + w + 1, y + h + 2, Theme.withAlpha(0x80000000, alpha * 0.6F));
        context.fill(x, y, x + w, y + h, Theme.withAlpha(Theme.CARD, alpha));
        context.drawBorder(x, y, w, h, Theme.withAlpha(Theme.FIELD_BORDER, alpha * 0.8F));
    }

    private void drawToggle(DrawContext context, int x, int y, int w, int h, boolean on, float alpha,
                            int mouseX, int mouseY, Runnable action) {
        boolean hover = inBox(mouseX, mouseY, x - 4, y - 4, w + 8, h + 8);
        int track = on ? (hover ? Theme.ACCENT_HOVER : Theme.TOGGLE_ON) : (hover ? 0xFF454F63 : Theme.TOGGLE_OFF);
        context.fill(x, y, x + w, y + h, Theme.withAlpha(track, alpha));
        int knobD = h - 6;
        int knobX = on ? x + w - 3 - knobD : x + 3;
        context.fill(knobX, y + 3, knobX + knobD, y + 3 + knobD, Theme.withAlpha(Theme.TOGGLE_KNOB, alpha));
        String label = on ? "OUI" : "NON";
        int labelColor = on ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED;
        context.drawText(textRenderer, Text.literal(label), x - textRenderer.getWidth(label) - 8, y + (h - 8) / 2,
                Theme.withAlpha(labelColor, alpha), false);
        addZone(x - 4, y - 4, w + 8, h + 8, action, mouseX, mouseY);
    }

    private void drawButton(DrawContext context, int x, int y, int w, int h, String label, boolean primary,
                            float alpha, int mouseX, int mouseY, Runnable action) {
        boolean hover = inBox(mouseX, mouseY, x, y, w, h);
        int bg = primary ? (hover ? Theme.ACCENT_HOVER : Theme.ACCENT) : (hover ? Theme.CARD_HOVER : Theme.CARD);
        int border = primary ? bg : Theme.FIELD_BORDER;
        int text = primary ? Theme.TEXT_PRIMARY : Theme.TEXT_SECONDARY;
        context.fill(x, y, x + w, y + h, Theme.withAlpha(bg, alpha));
        context.drawBorder(x, y, w, h, Theme.withAlpha(border, alpha));
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + w / 2, y + (h - 8) / 2,
                Theme.withAlpha(text, alpha));
        addZone(x, y, w, h, action, mouseX, mouseY);
    }

    private void drawMiniButton(DrawContext context, int x, int y, int w, int h, String label,
                                float alpha, int mouseX, int mouseY, Runnable action) {
        boolean hover = inBox(mouseX, mouseY, x, y, w, h);
        context.fill(x, y, x + w, y + h, Theme.withAlpha(hover ? 0xFF2E3547 : 0xFF232A38, alpha));
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + w / 2, y + (h - 8) / 2 + 1,
                Theme.withAlpha(Theme.TEXT_SECONDARY, alpha));
        addZone(x, y, w, h, action, mouseX, mouseY);
    }

    /**
     * Positionne un champ dans le contenu scrollé. Le fond est dessiné d'après
     * la position réelle du widget (getters), donc le texte, le clic et le
     * fond restent toujours parfaitement alignés. Hors zone visible : widget
     * parqué hors écran.
     */
    private void placeField(DrawContext context, TextFieldWidget field, int x, int scrolledY, int w, int h,
                            float alpha) {
        boolean visible = scrolledY + h >= contentY && scrolledY <= contentY + contentH && alpha > 0.5F;
        if (!visible) {
            field.setVisible(false);
            field.setX(-1000);
            field.setY(-1000);
            if (field.isFocused()) {
                field.setFocused(false);
            }
            return;
        }
        field.setVisible(true);
        field.setX(x);
        // Léger décalage pour mieux centrer visuellement le texte vanilla
        // dans les zones noires, sans modifier leur largeur ni leur taille.
        field.setY(scrolledY + 1);
        field.setWidth(w);
        int fx = field.getX();
        int fy = field.getY();
        int fw = field.getWidth();
        boolean editable = !readOnlyFields.contains(field);
        context.fill(fx - 1, fy - 1, fx + fw + 1, fy + h + 1,
                Theme.withAlpha(editable ? 0xFF0B0E13 : Theme.FIELD_DISABLED, alpha));
        context.drawBorder(fx - 1, fy - 1, fw + 2, h + 2,
                Theme.withAlpha(field.isFocused() ? Theme.ACCENT : (editable ? Theme.FIELD_BORDER : 0xFF222834), alpha));
    }

    private void setFieldEditable(TextFieldWidget field, boolean editable) {
        field.setEditable(editable);
        if (editable) {
            readOnlyFields.remove(field);
        } else {
            readOnlyFields.add(field);
            if (field.isFocused()) {
                field.setFocused(false);
            }
        }
    }

    private void hideFields(TextFieldWidget... fields) {
        for (TextFieldWidget field : fields) {
            field.setVisible(false);
            field.setX(-1000);
            field.setY(-1000);
            if (field.isFocused()) {
                field.setFocused(false);
            }
        }
    }

    private void renderToasts(DrawContext context, int rightX, int y, float alpha, long now) {
        toasts.removeIf(toast -> now > toast.expiryMs);
        int toastY = y;
        for (int i = 0; i < Math.min(3, toasts.size()); i++) {
            Toast toast = toasts.get(toasts.size() - 1 - i);
            float remaining = MathHelper.clamp((toast.expiryMs - now) / 2500.0F, 0.0F, 1.0F);
            float toastAlpha = Math.min(alpha, Math.min(1.0F, remaining * 3.0F));
            int tw = textRenderer.getWidth(toast.text) + 20;
            int tx = rightX - tw;
            context.fill(tx, toastY, tx + tw, toastY + 20, Theme.withAlpha(0xF01A2030, toastAlpha));
            context.drawBorder(tx, toastY, tw, 20, Theme.withAlpha(toast.color, toastAlpha * 0.8F));
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(toast.text), tx + tw / 2, toastY + 6,
                    Theme.withAlpha(0xFFFFFFFF, toastAlpha));
            toastY += 24;
        }
    }

    private void addToast(String text, int color) {
        toasts.add(new Toast(text, color, Util.getMeasuringTimeMs() + 2500L));
        while (toasts.size() > 5) {
            toasts.remove(0);
        }
    }

    private void addZone(int x, int y, int w, int h, Runnable action, int mouseX, int mouseY) {
        Zone zone = new Zone(x, y, x + w, y + h, action);
        if (zone.contains(mouseX, mouseY)) {
            hoveredZone = zones.size();
        }
        zones.add(zone);
    }

    private Runnable indexedAction(int index, Runnable action) {
        return () -> {
            if (index >= 0 && index < config.commands.size()) {
                action.run();
            }
        };
    }

    // ---------------------------------------------------------------- actions

    private void fillCurrentPosition() {
        if (client != null && client.player != null && client.world != null) {
            config.targetX = client.player.getX();
            config.targetY = client.player.getY();
            config.targetZ = client.player.getZ();
            try {
                config.targetDimension = client.world.getRegistryKey().getValue().toString();
            } catch (Exception ignored) {
                // Keep previous dimension on failure.
            }
            config.save();
            refreshCoordFields();
            addToast("Position cible mise à jour", Theme.SUCCESS);
        } else {
            addToast("Position du joueur indisponible", Theme.DANGER);
        }
    }

    private void refreshCoordFields() {
        xField.setText(fmtCoord(config.targetX));
        yField.setText(fmtCoord(config.targetY));
        zField.setText(fmtCoord(config.targetZ));
        toleranceField.setText(fmtNum(config.tolerance));
        dimensionField.setText(config.targetDimension);
    }

    private void beginAddCommand() {
        if (config.commands.size() >= 64) {
            addToast("Limite de commandes atteinte (64)", Theme.DANGER);
            return;
        }
        editingIndex = ADDING;
        cmdField.setText("");
        cmdDelayField.setText("500");
        cmdField.setFocused(true);
    }

    private void beginEditCommand(int index) {
        if (index < 0 || index >= config.commands.size()) {
            return;
        }
        editingIndex = index;
        FirstClientConfig.CommandEntry entry = config.commands.get(index);
        cmdField.setText(entry.command);
        cmdDelayField.setText(Integer.toString(entry.delayBeforeMs));
        cmdField.setFocused(true);
    }

    private void saveEditor() {
        String clean = CommandExecutor.sanitize(cmdField.getText());
        if (clean == null) {
            addToast("La commande ne peut pas être vide", Theme.DANGER);
            return;
        }
        int delay = parseBoundedInt(cmdDelayField.getText(), 0, 60000, -1);
        if (delay < 0) {
            addToast("Le délai doit être entre 0 et 60000 ms", Theme.DANGER);
            return;
        }
        if (editingIndex == ADDING) {
            config.commands.add(new FirstClientConfig.CommandEntry(clean, delay, true));
            addToast("Commande ajoutée", Theme.SUCCESS);
        } else if (editingIndex >= 0 && editingIndex < config.commands.size()) {
            FirstClientConfig.CommandEntry entry = config.commands.get(editingIndex);
            entry.command = clean;
            entry.delayBeforeMs = delay;
            addToast("Commande mise à jour", Theme.SUCCESS);
        }
        editingIndex = NO_EDIT;
        config.save();
    }

    private void moveCommand(int index, int direction) {
        int other = index + direction;
        if (index < 0 || index >= config.commands.size() || other < 0 || other >= config.commands.size()) {
            return;
        }
        FirstClientConfig.CommandEntry entry = config.commands.remove(index);
        config.commands.add(other, entry);
        if (editingIndex == index) {
            editingIndex = other;
        }
        config.save();
    }

    // ---------------------------------------------------------------- parsing

    private interface DoubleConsumer {
        void accept(double value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private static void tryParseDouble(String text, DoubleConsumer consumer) {
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isFinite(value)) {
                consumer.accept(value);
            }
        } catch (NumberFormatException ignored) {
            // Partial input while typing; keep the last valid value.
        }
    }

    private static void tryParseInt(String text, IntConsumer consumer) {
        try {
            consumer.accept(Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {
            // Partial input while typing; keep the last valid value.
        }
    }

    private static int parseBoundedInt(String text, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value < min || value > max) {
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String fmtCoord(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String fmtNum(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean inBox(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static final class Zone {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final Runnable action;

        Zone(double x1, double y1, double x2, double y2, Runnable action) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.action = action;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
        }
    }

    private static final class Toast {
        final String text;
        final int color;
        final long expiryMs;

        Toast(String text, int color, long expiryMs) {
            this.text = text;
            this.color = color;
            this.expiryMs = expiryMs;
        }
    }
}