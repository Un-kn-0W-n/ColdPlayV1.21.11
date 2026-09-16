package cn.timer.coldplay.client.alt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

final class AltManagerScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final AltManager manager;
    private TokenType tokenType = TokenType.ACCESS_TOKEN;
    private UUID selectedUuid;
    private String status = "Ready";
    private boolean statusSuccess = true;
    private long proxyDirtyAt;

    private Button tokenTypeButton;
    private Button loginButton;
    private Button proxyToggleButton;
    private Button testButton;
    private MaskedEditBox tokenField;
    private EditBox clientIdField;
    private EditBox addressField;
    private EditBox usernameField;
    private MaskedEditBox passwordField;
    private AccountList accountList;
    private int contentX;
    private int contentWidth;
    private int historyTop;
    private boolean twoColumns;
    private boolean proxyEnabled;

    AltManagerScreen(Screen parent, AltManager manager) {
        super(Component.literal("Alt Manager"));
        this.parent = parent;
        this.manager = manager;
    }

    @Override
    protected void init() {
        String token = value(tokenField);
        String clientId = value(clientIdField);
        contentWidth = Math.min(900, width - 20);
        contentX = (width - contentWidth) / 2;
        twoColumns = contentWidth >= 300;

        ProxySnapshot proxy = manager.proxy();
        if (twoColumns) {
            initAuth(contentX, 32, (contentWidth - 10) / 2, token, clientId);
            initProxy(contentX + (contentWidth + 10) / 2, 32, (contentWidth - 10) / 2, proxy);
            historyTop = 144;
        } else {
            initAuth(contentX, 28, contentWidth, token, clientId);
            initProxy(contentX, 104, contentWidth, proxy);
            historyTop = 204;
        }

        int listHeight = Math.max(30, height - historyTop - 34);
        accountList = addRenderableWidget(new AccountList(minecraft, contentWidth, listHeight, historyTop, 36));
        accountList.updateSizeAndPosition(contentWidth, listHeight, contentX, historyTop);
        reloadAccounts();
        addRenderableWidget(Button.builder(Component.literal("Back"), input -> onClose())
                .bounds(contentX, height - 25, 70, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        boolean editable = !manager.busy();
        loginButton.active = editable;
        proxyToggleButton.active = editable;
        testButton.active = editable && proxyEnabled;
        tokenTypeButton.active = editable;
        tokenField.setEditable(editable);
        clientIdField.setEditable(editable);
        addressField.setEditable(editable);
        usernameField.setEditable(editable);
        passwordField.setEditable(editable);
        if (proxyDirtyAt != 0 && System.currentTimeMillis() - proxyDirtyAt >= 500) {
            proxyDirtyAt = 0;
            manager.saveProxy();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 9, 0xFFFFFFFF);
        graphics.drawString(font, "Account login", contentX, twoColumns ? 21 : 17, 0xFFA0A0A0);
        if (twoColumns) {
            graphics.drawString(font, "SOCKS5 server proxy", contentX + (contentWidth + 10) / 2,
                    21, 0xFFA0A0A0);
        } else {
            graphics.drawString(font, "SOCKS5 server proxy", contentX, 93, 0xFFA0A0A0);
        }
        graphics.drawString(font, "Saved accounts", contentX, historyTop - 12, 0xFFA0A0A0);
        graphics.drawString(font, trim(font, status, Math.max(0, contentWidth - 78)), contentX + 78, height - 19,
                statusSuccess ? 0xFF55FF55 : 0xFFFF5555);
    }

    @Override
    public void onClose() {
        syncProxy();
        manager.saveProxy();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void initAuth(int x, int y, int width, String token, String clientId) {
        int typeWidth = Math.min(125, Math.max(95, width / 3));
        tokenTypeButton = addRenderableWidget(Button.builder(Component.empty(), input -> {
            tokenType = tokenType.next();
            updateTokenType();
        }).bounds(x, y, typeWidth, 20).build());
        clientIdField = addRenderableWidget(new EditBox(font, x + typeWidth + 5, y,
                width - typeWidth - 5, 20, Component.literal("OAuth client ID")));
        clientIdField.setHint(Component.literal("OAuth client ID"));
        clientIdField.setMaxLength(64);
        clientIdField.setValue(clientId);

        tokenField = addRenderableWidget(new MaskedEditBox(font, x, y + 24, width, 20,
                Component.literal("Credential token")));
        tokenField.setHint(Component.literal("Credential token (masked)"));
        tokenField.setMaxLength(16_384);
        tokenField.setValue(token);
        loginButton = addRenderableWidget(Button.builder(Component.literal("Log in"), input -> login())
                .bounds(x, y + 48, 90, 20).build());
        updateTokenType();
    }

    private void initProxy(int x, int y, int width, ProxySnapshot proxy) {
        proxyEnabled = proxy.serverEnabled;
        addressField = addRenderableWidget(new EditBox(font, x, y, width, 20,
                 Component.literal("Proxy address")));
        addressField.setHint(Component.literal("host:port or [IPv6]:port"));
        addressField.setMaxLength(255);
        addressField.setValue(displayAddress(proxy));

        int half = (width - 5) / 2;
        usernameField = addRenderableWidget(new EditBox(font, x, y + 24, half, 20,
                Component.literal("Proxy username")));
        usernameField.setHint(Component.literal("Username (optional)"));
        usernameField.setMaxLength(255);
        usernameField.setValue(proxy.username);
        passwordField = addRenderableWidget(new MaskedEditBox(font, x + half + 5, y + 24,
                width - half - 5, 20, Component.literal("Proxy password")));
        passwordField.setHint(Component.literal("Password (masked)"));
        passwordField.setMaxLength(1024);
        passwordField.setValue(proxy.password);
        int buttonWidth = (width - 5) / 2;
        proxyToggleButton = addRenderableWidget(Button.builder(Component.empty(), input -> toggleProxy())
                .bounds(x, y + 48, buttonWidth, 20).build());
        testButton = addRenderableWidget(Button.builder(Component.literal("Test proxy"), input -> testProxy())
                .bounds(x + buttonWidth + 5, y + 48, width - buttonWidth - 5, 20).build());
        updateProxyButton();

        addressField.setResponder(value -> proxyEdited());
        usernameField.setResponder(value -> proxyEdited());
        passwordField.setResponder(value -> proxyEdited());
    }

    private void login() {
        status = "Logging in...";
        statusSuccess = true;
        manager.login(tokenType, tokenField.getValue(), clientIdField.getValue(), selectedUuid, result -> {
            finishLogin(result, true);
        });
    }

    private void loginSaved(AltAccount account) {
        if (!account.hasCredential()) {
            status = "No saved token for " + account.username + "; enter a new token.";
            statusSuccess = false;
            return;
        }
        status = "Logging in as " + account.username + "...";
        statusSuccess = true;
        manager.login(account.tokenType, "", account.clientId, account.uuid, result -> finishLogin(result, false));
    }

    private void finishLogin(ActionResult result, boolean clearToken) {
        status = result.message();
        statusSuccess = result.success();
        if (result.success()) {
            if (clearToken) {
                tokenField.setValue("");
            }
            reloadAccounts();
        }
    }

    private void testProxy() {
        syncProxy();
        status = "Testing configured routes...";
        statusSuccess = true;
        manager.testProxy(firstSavedServer(), result -> {
            status = result.message();
            statusSuccess = result.success();
        });
    }

    private String firstSavedServer() {
        if (parent instanceof JoinMultiplayerScreen multiplayer) {
            var servers = multiplayer.getServers();
            if (servers != null && servers.size() > 0) {
                return servers.get(0).ip;
            }
        }
        return null;
    }

    private void select(AltAccount account) {
        selectedUuid = account.uuid;
        tokenType = account.tokenType;
        clientIdField.setValue(account.clientId);
        updateTokenType();
        status = account.hasCredential()
                ? "Selected " + account.username + "; its saved token will be used if the token box is empty."
                : "Selected " + account.username + "; enter a new token.";
        statusSuccess = true;
    }

    private void reloadAccounts() {
        List<AccountEntry> entries = manager.accounts().stream().map(AccountEntry::new).toList();
        accountList.replaceEntries(entries);
        entries.stream().filter(entry -> entry.account.uuid.equals(selectedUuid)).findFirst()
                .ifPresent(accountList::setSelected);
    }

    private void updateTokenType() {
        tokenTypeButton.setMessage(Component.literal(tokenType.label()));
        clientIdField.setVisible(tokenType == TokenType.REFRESH_TOKEN);
    }

    private void proxyEdited() {
        if (addressField != null && passwordField != null) {
            syncProxy();
        }
    }

    private void toggleProxy() {
        proxyEnabled = !proxyEnabled;
        syncProxy();
        updateProxyButton();
        status = proxyEnabled ? "SOCKS5 proxy enabled." : "SOCKS5 proxy disabled.";
        statusSuccess = true;
    }

    private void updateProxyButton() {
        proxyToggleButton.setMessage(Component.literal(proxyEnabled ? "Proxy: ON" : "Proxy: OFF"));
        testButton.active = proxyEnabled && !manager.busy();
    }

    private void syncProxy() {
        String address = value(addressField);
        ProxySnapshot snapshot = new ProxySnapshot(false, proxyEnabled, ProxyProtocol.SOCKS5,
                address, "", value(usernameField), value(passwordField));
        manager.updateProxy(snapshot);
        proxyDirtyAt = System.currentTimeMillis();
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

    private static String value(EditBox field) {
        return field == null ? "" : field.getValue();
    }

    private final class AccountList extends ObjectSelectionList<AccountEntry> {
        AccountList(Minecraft minecraft, int width, int height, int y, int entryHeight) {
            super(minecraft, width, height, y, entryHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.max(100, contentWidth - 12);
        }
    }

    private final class AccountEntry extends ObjectSelectionList.Entry<AccountEntry> {
        private final AltAccount account;

        AccountEntry(AltAccount account) {
            this.account = account;
        }

        @Override
        public Component getNarration() {
            return Component.literal(account.username + ", " + manager.status(account).label()
                    + ", " + account.tokenType.label() + ", double-click to log in");
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX();
            int y = getContentY();
            AccountStatus accountStatus = manager.status(account);
            int statusColor = switch (accountStatus) {
                case ACTIVE -> 0xFF55FF55;
                case FAILED -> 0xFFFF5555;
                case NEEDS_TOKEN -> 0xFFFFFF55;
                case SAVED -> 0xFFAAAAAA;
            };
            String first = account.username + "  " + accountStatus.label();
            String second = account.uuid + "  " + account.tokenType.label()
                    + (account.clientId.isEmpty() ? "" : "  client " + account.clientId)
                    + "  " + TIME.format(account.lastSuccess);
            graphics.drawString(font, trim(font, first, getContentWidth() - 8), x + 3, y + 3, statusColor);
            graphics.drawString(font, trim(font, second, getContentWidth() - 8), x + 3, y + 18, 0xFFA0A0A0);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            accountList.setSelected(this);
            select(account);
            if (doubleClick) {
                loginSaved(account);
            }
            return true;
        }
    }

    private static String trim(Font font, String value, int width) {
        if (font.width(value) <= width) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }

    private static final class MaskedEditBox extends EditBox {
        MaskedEditBox(Font font, int x, int y, int width, int height, Component label) {
            super(font, x, y, width, height, label);
            addFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.literal(getMessage().getString() + ": masked value");
        }
    }
}
