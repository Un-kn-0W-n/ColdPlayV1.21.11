package cn.timer.coldplay.client.alt;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.MenuBackground;
import cn.timer.coldplay.client.gui.StyledButton;
import cn.timer.coldplay.client.gui.TextField;
import cn.timer.coldplay.client.gui.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Account and proxy editor. Premium logins run on the manager worker; results land on the client thread.
 * The column is laid out in a virtual space that is scaled down uniformly when the window is too short
 * for it, so nothing overflows the panel; mouse coordinates are mapped into that space.
 */
final class AltManagerScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int PAD = 14;
    private static final int DELETE_SIZE = 12;
    private static final int PANEL_MAX_W = 360;
    private static final int MARGIN_X = 20;
    private static final int MARGIN_Y = 24;
    private static final int MIN_MARGIN_Y = 6;
    private static final int MIN_LIST_H = ROW_H * 3;
    private static final int BACK_H = 16;
    private static final int BACK_INSET = 8;
    private static final Pattern OFFLINE_NAME = Pattern.compile("[a-zA-Z0-9_]{3,16}");

    // proxy slots must stay last and contiguous; isProxyFieldFocused relies on it
    private static final int F_NONE = -1, F_TOKEN = 0, F_NAME = 1, F_HOST = 2, F_USER = 3, F_PASS = 4;
    private static final int[] MAX_LEN = {16_384, 16, 256, 128, 256};
    private static final boolean[] MASKED = {true, false, false, false, true};

    private final Screen parent;
    private final AltManager manager;
    private final String[] fields = {"", "", "", "", ""};
    private int focused = F_TOKEN;
    private int cursorCounter;
    private boolean proxyFieldsLoaded;

    private boolean crackedTab;
    private String status = "";
    private int statusColor = Theme.TEXT_DIM;
    private int scrollOffset;

    private float scale = 1.0F;
    private int viewW, viewH; // window size in layout space
    private int left, right, top, bottom, panelW;
    private int titleY, subtitleY, tokenLabelY;
    private int tabY, tabH, tabW, crackedTabX;
    private int tokenX, tokenY, tokenW, tokenH;
    private int loginX, loginY, loginW, loginH;
    private int statusY, savedLabelY;
    private int proxyLabelY, proxyFieldH;
    private int proxyHostX, proxyHostY, proxyHostW;
    private int proxyCredY, proxyUserX, proxyUserW, proxyPassX, proxyPassW;
    private int saveProxyX, saveProxyY, saveProxyW, saveProxyH;
    private int proxyToggleX, proxyToggleW;
    private int listX, listY, listW, listBottom;
    private int backX, backY, backW, backH;

    AltManagerScreen(Screen parent, AltManager manager) {
        super(Component.literal("Account Manager"));
        this.parent = parent;
        this.manager = manager;
    }

    @Override
    protected void init() {
        // a resize re-runs init; unsaved proxy edits survive it
        if (proxyFieldsLoaded) {
            return;
        }
        proxyFieldsLoaded = true;
        ProxySnapshot proxy = manager.proxy();
        fields[F_HOST] = displayAddress(proxy);
        fields[F_USER] = proxy.username;
        fields[F_PASS] = proxy.password;
    }

    private void updateLayout() {
        // vertical fit: the margins give first, then the whole panel shrinks
        int minPanelH = layoutRows(0) + MIN_LIST_H + BACK_INSET + BACK_H + BACK_INSET;
        int marginY = Math.clamp((this.height - minPanelH) / 2, MIN_MARGIN_Y, MARGIN_Y);
        scale = Math.min(1.0F, this.height / (float) (minPanelH + MIN_MARGIN_Y * 2));
        viewW = Math.round(this.width / scale);
        viewH = Math.round(this.height / scale);

        panelW = Math.clamp(viewW - MARGIN_X * 2, 0, PANEL_MAX_W);
        left = (viewW - panelW) / 2;
        right = left + panelW;
        top = marginY;
        bottom = Math.max(marginY, viewH - marginY);

        listY = layoutRows(top);

        backX = left + PAD;
        backW = Math.max(0, panelW - PAD * 2);
        backH = BACK_H;
        backY = bottom - BACK_INSET - backH;

        listX = left + PAD;
        listW = panelW - PAD * 2;
        listBottom = backY - BACK_INSET;
    }

    /** Places every row of the column below {@code top} and returns where the list starts. */
    private int layoutRows(int top) {
        titleY = top + 10;
        subtitleY = titleY + 22;

        tabY = subtitleY + 16;
        tabH = 16;
        tabW = (panelW - PAD * 2 - 6) / 2;
        crackedTabX = left + PAD + tabW + 6;

        tokenLabelY = tabY + tabH + 8;

        tokenX = left + PAD;
        tokenW = panelW - PAD * 2;
        tokenY = tokenLabelY + 13;
        tokenH = 14;

        loginX = left + PAD;
        loginW = panelW - PAD * 2;
        loginY = tokenY + tokenH + 8;
        loginH = 16;

        statusY = loginY + loginH + 7;

        proxyFieldH = 14;
        int credGap = 6;
        proxyLabelY = statusY + 14;

        proxyHostX = left + PAD;
        proxyHostW = panelW - PAD * 2;
        proxyHostY = proxyLabelY + 12;

        proxyCredY = proxyHostY + proxyFieldH + 4;
        proxyUserX = left + PAD;
        proxyUserW = (panelW - PAD * 2 - credGap) / 2;
        proxyPassX = proxyUserX + proxyUserW + credGap;
        proxyPassW = right - PAD - proxyPassX;

        proxyToggleW = 70;
        saveProxyX = left + PAD;
        saveProxyW = panelW - PAD * 2 - proxyToggleW - 6;
        saveProxyY = proxyCredY + proxyFieldH + 6;
        saveProxyH = 16;
        proxyToggleX = saveProxyX + saveProxyW + 6;

        savedLabelY = saveProxyY + saveProxyH + 10;
        return savedLabelY + 14;
    }

    @Override
    public void tick() {
        cursorCounter++;
    }

    /** The ColdPlay backdrop replaces the panorama and blur. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuBackground.draw(graphics, this.width, this.height);
    }

    @Override
    public void render(GuiGraphics graphics, int screenMouseX, int screenMouseY, float partialTick) {
        updateLayout();
        int mouseX = (int) (screenMouseX / scale);
        int mouseY = (int) (screenMouseY / scale);
        Draw.pushScale(graphics, 0, 0, scale);

        Draw.rect(graphics, left, top, panelW, bottom - top, Theme.BODY);
        Theme.contour(graphics, left, top, panelW, bottom - top);

        MenuBackground.drawSectionTitle(graphics, font, "Account Manager", viewW / 2, titleY);
        User user = minecraft.getUser();
        if (user != null) {
            boolean premium = !AltManager.offlineSession(user);
            drawCentered(graphics, user.getName() + (premium ? " (Premium)" : " (Offline)"), subtitleY,
                    premium ? Theme.FROST : Theme.TEXT_DIM);
        }

        drawButton(graphics, "Premium", left + PAD, tabY, tabW, tabH, mouseX, mouseY, true);
        drawButton(graphics, "Cracked", crackedTabX, tabY, tabW, tabH, mouseX, mouseY, true);
        int selX = crackedTab ? crackedTabX : left + PAD;
        Draw.rect(graphics, selX, tabY + tabH - Theme.TICK_PX, tabW, Theme.TICK_PX, Theme.FROST);

        Draw.text(graphics, font, crackedTab ? "Username" : "Refresh Token (M.C...)",
                left + PAD, tokenLabelY, Theme.TEXT_DIM);
        drawField(graphics, crackedTab ? F_NAME : F_TOKEN, tokenX, tokenY, tokenW, tokenH, "");
        boolean busy = manager.busy();
        drawButton(graphics, !crackedTab && busy ? "Validating..." : "Login",
                loginX, loginY, loginW, loginH, mouseX, mouseY, !busy);

        if (!status.isEmpty()) {
            drawCentered(graphics, Draw.trimToWidth(font, status, panelW - PAD * 2, "..."), statusY, statusColor);
        }

        ProxySnapshot proxy = manager.proxy();
        boolean proxyConfigured = !proxy.address.isEmpty();
        boolean proxyOn = proxyConfigured && proxy.serverEnabled;
        Draw.text(graphics, font, "Proxy (SOCKS5)", left + PAD, proxyLabelY, Theme.TEXT_DIM);
        if (proxyConfigured) {
            String proxyState = proxyOn ? "ACTIVE" : "OFF";
            Draw.text(graphics, font, proxyState, right - PAD - font.width(proxyState), proxyLabelY,
                    proxyOn ? Theme.FROST : Theme.TEXT_MUTE);
        }
        drawField(graphics, F_HOST, proxyHostX, proxyHostY, proxyHostW, proxyFieldH, "host:port");
        drawField(graphics, F_USER, proxyUserX, proxyCredY, proxyUserW, proxyFieldH, "username");
        drawField(graphics, F_PASS, proxyPassX, proxyCredY, proxyPassW, proxyFieldH, "password");
        drawButton(graphics, "Save Proxy", saveProxyX, saveProxyY, saveProxyW, saveProxyH, mouseX, mouseY, true);
        drawButton(graphics, proxyOn ? "On" : "Off", proxyToggleX, saveProxyY, proxyToggleW, saveProxyH,
                mouseX, mouseY, proxyConfigured);

        Draw.text(graphics, font, crackedTab ? "Saved Cracked" : "Saved Premium",
                left + PAD, savedLabelY, Theme.TEXT_DIM);
        drawList(graphics, mouseX, mouseY);

        drawButton(graphics, "Back", backX, backY, backW, backH, mouseX, mouseY, true);
        Draw.popScale(graphics);
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        List<AltAccount> alts = manager.accounts(crackedTab);
        int listH = listBottom - listY;

        if (alts.isEmpty()) {
            drawCentered(graphics, "No saved alts", listY + (listH - Draw.CAP_H) / 2, Theme.TEXT_MUTE);
            scrollOffset = 0;
            Draw.outline(graphics, listX, listY, listX + listW, listBottom, 1, Theme.SEP);
            return;
        }

        int contentH = alts.size() * ROW_H;
        scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, contentH - listH));

        Draw.scissor(graphics, listX, listY, listW, listH);
        for (int i = 0; i < alts.size(); i++) {
            int rowY = rowTop(i);
            if (!rowVisible(rowY)) {
                continue;
            }
            drawRow(graphics, alts.get(i), rowY, mouseX, mouseY);
            if (i > 0) {
                Draw.rectBounds(graphics, listX, rowY, listX + listW, rowY + 1, Theme.SEP);
            }
        }
        Draw.unscissor(graphics);
        drawScrollbar(graphics, contentH, listH);
        // frame goes last, over any row hover fill
        Draw.outline(graphics, listX, listY, listX + listW, listBottom, 1, Theme.SEP);
    }

    private void drawRow(GuiGraphics graphics, AltAccount alt, int rowY, int mouseX, int mouseY) {
        int delX = delX();
        int delY = delY(rowY);
        boolean delHover = hitsDelete(rowY, mouseX, mouseY);
        if (delHover) {
            Draw.rect(graphics, delX, delY, DELETE_SIZE, DELETE_SIZE, Theme.HOVER_LIFT);
        } else if (Draw.hovered(mouseX, mouseY, listX, rowY, listW, ROW_H)) {
            Draw.rect(graphics, listX, rowY, listW, ROW_H, Theme.HOVER_LIFT);
        }

        AccountStatus accountStatus = manager.status(alt);
        if (accountStatus == AccountStatus.ACTIVE) {
            Draw.rect(graphics, listX + Theme.CONTOUR_PX, rowY, Theme.TICK_PX, ROW_H, Theme.FROST);
        }
        int nameColor = switch (accountStatus) {
            case FAILED -> Theme.DANGER;
            case NEEDS_TOKEN -> Theme.TEXT_MUTE;
            default -> Theme.TEXT;
        };
        int nameX = listX + 6;
        Draw.text(graphics, font, Draw.trimToWidth(font, alt.username, delX - 4 - nameX, "..."),
                nameX, Draw.textY(rowY, ROW_H), nameColor);
        Draw.textCentered(graphics, font, "x", delX, delY, DELETE_SIZE, DELETE_SIZE,
                delHover ? Theme.DANGER : Theme.TEXT_DIM);
    }

    /** Indicator only; the thumb is not draggable. */
    private void drawScrollbar(GuiGraphics graphics, int contentH, int listH) {
        int maxScroll = contentH - listH;
        int trackH = listH - 4;
        if (maxScroll <= 0 || trackH <= 0) {
            return;
        }
        int trackX = listX + listW - 2;
        int trackY = listY + 2;
        int thumbH = Draw.scrollThumbHeight(trackH, listH, contentH);
        Draw.rect(graphics, trackX, trackY, 1, trackH, Theme.SEP);
        Draw.rect(graphics, trackX,
                trackY + Draw.scrollThumbOffset(trackH, thumbH, scrollOffset, maxScroll),
                1, thumbH, Theme.FROST);
    }

    private int rowTop(int index) {
        return listY + index * ROW_H - scrollOffset;
    }

    private boolean rowVisible(int rowTop) {
        return rowTop + ROW_H >= listY && rowTop <= listBottom;
    }

    private int delX() {
        return listX + listW - DELETE_SIZE - 4;
    }

    private static int delY(int rowTop) {
        return rowTop + (ROW_H - DELETE_SIZE) / 2;
    }

    private boolean hitsDelete(int rowTop, double mouseX, double mouseY) {
        return Draw.hovered(mouseX, mouseY, delX(), delY(rowTop), DELETE_SIZE, DELETE_SIZE);
    }

    private void drawCentered(GuiGraphics graphics, String text, int y, int color) {
        Draw.text(graphics, font, text, viewW / 2 - font.width(text) / 2, y, color);
    }

    private void drawField(GuiGraphics graphics, int slot, int x, int y, int w, int h, String placeholder) {
        TextField.draw(graphics, font, x, y, w, h, fields[slot], focused == slot, placeholder,
                cursorCounter, MASKED[slot]);
    }

    private void drawButton(GuiGraphics graphics, String label, int x, int y, int w, int h,
                            int mouseX, int mouseY, boolean enabled) {
        StyledButton.draw(graphics, font, label, x, y, w, h, mouseX, mouseY, enabled, Theme.WELL, Theme.TEXT);
    }

    // ---- input ----

    @Override
    public boolean mouseScrolled(double screenMouseX, double screenMouseY, double horizontal, double vertical) {
        double mouseX = screenMouseX / scale;
        double mouseY = screenMouseY / scale;
        if (vertical == 0 || !Draw.hovered(mouseX, mouseY, listX, listY, listW, listBottom - listY)) {
            return false;
        }
        scrollOffset -= (vertical > 0 ? 1 : -1) * ROW_H; // clamped by drawList
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        updateLayout();
        double mouseX = event.x() / scale;
        double mouseY = event.y() / scale;

        focused = F_NONE;
        if (Draw.hovered(mouseX, mouseY, tokenX, tokenY, tokenW, tokenH)) {
            focused = crackedTab ? F_NAME : F_TOKEN;
        } else if (Draw.hovered(mouseX, mouseY, proxyHostX, proxyHostY, proxyHostW, proxyFieldH)) {
            focused = F_HOST;
        } else if (Draw.hovered(mouseX, mouseY, proxyUserX, proxyCredY, proxyUserW, proxyFieldH)) {
            focused = F_USER;
        } else if (Draw.hovered(mouseX, mouseY, proxyPassX, proxyCredY, proxyPassW, proxyFieldH)) {
            focused = F_PASS;
        }
        if (focused != F_NONE) {
            cursorCounter = 0;
            return true;
        }

        if (Draw.hovered(mouseX, mouseY, backX, backY, backW, backH)) {
            onClose();
            return true;
        }

        if (Draw.hovered(mouseX, mouseY, left + PAD, tabY, tabW, tabH)) {
            switchTab(false);
            return true;
        }
        if (Draw.hovered(mouseX, mouseY, crackedTabX, tabY, tabW, tabH)) {
            switchTab(true);
            return true;
        }

        if (Draw.hovered(mouseX, mouseY, loginX, loginY, loginW, loginH)) {
            if (crackedTab) {
                loginOffline();
            } else if (!manager.busy()) {
                startLogin();
            }
            return true;
        }

        if (Draw.hovered(mouseX, mouseY, saveProxyX, saveProxyY, saveProxyW, saveProxyH)) {
            applyProxyFromFields();
            return true;
        }

        if (Draw.hovered(mouseX, mouseY, proxyToggleX, saveProxyY, proxyToggleW, saveProxyH)) {
            toggleProxy();
            return true;
        }

        handleListClick(mouseX, mouseY);
        return true;
    }

    private void handleListClick(double mouseX, double mouseY) {
        if (mouseY < listY || mouseY > listBottom) {
            return;
        }
        List<AltAccount> alts = manager.accounts(crackedTab);
        for (int i = 0; i < alts.size(); i++) {
            int rowY = rowTop(i);
            if (!rowVisible(rowY) || !Draw.hovered(mouseX, mouseY, listX, rowY, listW, ROW_H)) {
                continue;
            }
            AltAccount alt = alts.get(i);

            if (hitsDelete(rowY, mouseX, mouseY)) {
                if (manager.remove(alt.uuid)) {
                    setStatus("Removed " + alt.username, Theme.TEXT_DIM);
                } else {
                    setStatus("Could not remove " + alt.username, Theme.DANGER);
                }
                return;
            }

            // Prevent an in-flight premium login from overwriting the session picked here.
            if (!manager.busy()) {
                if (alt.tokenType.cracked()) {
                    manager.loginOffline(alt.username, this::finishLogin);
                } else {
                    loginSaved(alt);
                }
            }
            return;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // a focused field takes every key except Enter, which submits
        if (focused != F_NONE && !event.isConfirmation()) {
            TextField.EditResult edit = TextField.keyPressed(fields[focused], event, MAX_LEN[focused]);
            fields[focused] = edit.value();
            if (!edit.focused()) {
                focused = F_NONE;
            }
            return true;
        }
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (event.isConfirmation()) {
            if (isProxyFieldFocused()) {
                applyProxyFromFields();
            } else if (crackedTab) {
                loginOffline();
            } else if (!manager.busy()) {
                startLogin();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focused == F_NONE) {
            return false;
        }
        TextField.EditResult edit = TextField.charTyped(fields[focused], event, MAX_LEN[focused]);
        fields[focused] = edit.value();
        return true;
    }

    // ---- actions ----

    private void switchTab(boolean cracked) {
        if (crackedTab == cracked) {
            return;
        }
        crackedTab = cracked;
        scrollOffset = 0;
        setStatus("", Theme.TEXT_DIM);
        focused = cracked ? F_NAME : F_TOKEN;
    }

    private void loginOffline() {
        if (manager.busy()) {
            return;
        }
        String name = fields[F_NAME].trim();
        if (!OFFLINE_NAME.matcher(name).matches()) {
            setStatus("Invalid username: 3-16 letters, numbers, _", Theme.DANGER);
            return;
        }
        manager.loginOffline(name, this::finishLogin);
    }

    private void startLogin() {
        String token = fields[F_TOKEN].trim();
        fields[F_TOKEN] = "";
        // anything that is not a JWT access token is treated as a refresh token
        runLogin(token, !looksLikeJwt(token));
    }

    private static boolean looksLikeJwt(String token) {
        return token.startsWith("eyJ") && token.indexOf('.') != token.lastIndexOf('.');
    }

    private void runLogin(String credential, boolean refreshToken) {
        setStatus(refreshToken ? "Refreshing session..." : "Validating account...", Theme.TEXT_DIM);
        manager.login(refreshToken ? TokenType.REFRESH_TOKEN : TokenType.ACCESS_TOKEN, credential,
                refreshToken ? AltManager.DEFAULT_CLIENT_ID : "", null, this::finishLogin);
    }

    private void loginSaved(AltAccount alt) {
        if (!alt.hasCredential()) {
            setStatus("No saved token for " + alt.username + "; paste a new one", Theme.DANGER);
            return;
        }
        setStatus(alt.tokenType == TokenType.REFRESH_TOKEN ? "Refreshing session..." : "Validating account...",
                Theme.TEXT_DIM);
        manager.login(alt.tokenType, "", alt.clientId, alt.uuid, this::finishLogin);
    }

    private void finishLogin(ActionResult result) {
        setStatus(result.message(), result.success() ? Theme.FROST : Theme.DANGER);
    }

    private void setStatus(String status, int color) {
        this.status = status;
        this.statusColor = color;
    }

    private void applyProxyFromFields() {
        String host = fields[F_HOST].trim();
        if (host.isEmpty()) {
            fields[F_HOST] = "";
            fields[F_USER] = "";
            fields[F_PASS] = "";
            manager.updateProxy(ProxySnapshot.EMPTY);
            ActionResult saved = manager.saveProxy();
            setStatus(saved.success() ? "Proxy cleared" : saved.message(),
                    saved.success() ? Theme.TEXT_DIM : Theme.DANGER);
            return;
        }

        ProxySnapshot snapshot = new ProxySnapshot(false, true, ProxyProtocol.SOCKS5, host, "",
                fields[F_USER], fields[F_PASS]);
        try {
            snapshot.endpoint();
        } catch (IllegalArgumentException exception) {
            setStatus("Invalid proxy: " + exception.getMessage(), Theme.DANGER);
            return;
        }
        manager.updateProxy(snapshot);
        ActionResult saved = manager.saveProxy();
        setStatus(saved.success() ? "Proxy set: " + host + " (enabled)" : saved.message(),
                saved.success() ? Theme.FROST : Theme.DANGER);
    }

    private void toggleProxy() {
        ProxySnapshot proxy = manager.proxy();
        if (proxy.address.isEmpty()) {
            setStatus("No proxy configured", Theme.TEXT_DIM);
            return;
        }
        boolean enable = !proxy.serverEnabled;
        manager.updateProxy(new ProxySnapshot(false, enable, proxy.protocol, proxy.address, proxy.port,
                proxy.username, proxy.password));
        ActionResult saved = manager.saveProxy();
        if (!saved.success()) {
            setStatus(saved.message(), Theme.DANGER);
            return;
        }
        setStatus(enable ? "Proxy enabled: " + displayAddress(proxy) : "Proxy disabled",
                enable ? Theme.FROST : Theme.TEXT_DIM);
    }

    private boolean isProxyFieldFocused() {
        return focused >= F_HOST;
    }

    private static String displayAddress(ProxySnapshot proxy) {
        if (proxy.port.isBlank() || hasEmbeddedPort(proxy.address)) {
            return proxy.address;
        }
        return proxy.address + ":" + proxy.port;
    }

    private static boolean hasEmbeddedPort(String address) {
        if (address.startsWith("[")) {
            int close = address.lastIndexOf(']');
            return close >= 0 && close + 1 < address.length() && address.charAt(close + 1) == ':';
        }
        int colon = address.indexOf(':');
        return colon >= 0 && colon == address.lastIndexOf(':');
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
