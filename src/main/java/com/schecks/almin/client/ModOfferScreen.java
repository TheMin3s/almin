package com.schecks.almin.client;

import com.schecks.almin.ModOfferPayload;
import com.schecks.almin.ModResponsePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.List;

/**
 * Asks the player whether to install the mods a server offers.
 *
 * <p>The screen is deliberately blunt about what Approve means: each row names
 * the mod and the host the file would come from, and the buttons say what they
 * do. Nothing downloads until Approve is pressed, and the download runs on a
 * background thread so the game doesn't freeze.
 *
 * <p>When the server has said that declining disconnects, the Deny button says
 * so rather than letting the player find out afterwards.
 */
public final class ModOfferScreen extends Screen {
    private static final int ENTRY_HEIGHT = 24;

    private final List<ModOfferPayload.Offer> offers;
    private final boolean denyDisconnects;
    private OfferList list;
    private Button approve;
    private Button deny;
    private String status = "";
    private boolean working = false;

    public ModOfferScreen(List<ModOfferPayload.Offer> offers, boolean denyDisconnects) {
        super(Component.literal("This server suggests some mods"));
        this.offers = offers;
        this.denyDisconnects = denyDisconnects;
    }

    /**
     * Opens the prompt, skipping anything already installed. Does nothing if
     * that leaves no offers — a returning player isn't re-asked every join.
     */
    public static void offer(List<ModOfferPayload.Offer> offers, boolean denyDisconnects) {
        List<ModOfferPayload.Offer> pending = offers.stream()
            .filter(o -> !ClientModInstaller.alreadyInstalled(o))
            .toList();
        if (pending.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ModOfferScreen(pending, denyDisconnects));
    }

    @Override
    protected void init() {
        int listTop = 46;
        int listHeight = Math.max(ENTRY_HEIGHT, this.height - listTop - 62);
        list = new OfferList(this.minecraft, this.width, listHeight, listTop, ENTRY_HEIGHT);
        for (ModOfferPayload.Offer o : offers) list.add(new OfferEntry(o));
        addRenderableWidget(list);

        boolean anyRequired = offers.stream().anyMatch(ModOfferPayload.Offer::required);
        int by = this.height - 30;
        approve = Button.builder(Component.literal("Approve and install"), b -> doApprove())
            .bounds(this.width / 2 - 205, by, 200, 20).build();
        deny = Button.builder(
                Component.literal(denyDisconnects && anyRequired ? "Deny and leave server" : "Not now"),
                b -> doDeny())
            .bounds(this.width / 2 + 5, by, 200, 20).build();
        addRenderableWidget(approve);
        addRenderableWidget(deny);
    }

    private void doApprove() {
        if (working) return;
        working = true;
        approve.active = false;
        deny.active = false;
        status = "Downloading…";
        // Off the render thread: these are network downloads.
        Thread t = new Thread(() -> {
            List<ClientModInstaller.Outcome> results = ClientModInstaller.installAll(offers);
            int ok = (int) results.stream().filter(ClientModInstaller.Outcome::ok).count();
            int failed = results.size() - ok;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                ClientPlayNetworking.send(new ModResponsePayload(true, ok));
                working = false;
                if (failed == 0) {
                    mc.setScreen(new ModOfferResultScreen(results, true));
                } else {
                    mc.setScreen(new ModOfferResultScreen(results, false));
                }
            });
        }, "Almin-mod-install");
        t.setDaemon(true);
        t.start();
    }

    private void doDeny() {
        if (working) return;
        ClientPlayNetworking.send(new ModResponsePayload(false, 0));
        // The server decides whether declining ends the session; just close.
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, Component.literal(
                "Approving downloads these files and runs them next time you start Minecraft."),
            this.width / 2, 26, 0xFFAAAAAA);
        if (!status.isEmpty()) {
            g.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 44, 0xFFFFCC55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The host a URL points at, for showing the player who they'd trust. */
    static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "unknown source" : h;
        } catch (Exception e) {
            return "unknown source";
        }
    }

    private static final class OfferList extends ObjectSelectionList<OfferEntry> {
        OfferList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }
        void add(OfferEntry e) { addEntry(e); }
        @Override public int getRowWidth() { return Math.min(this.width - 20, 420); }
        @Override public int getRowLeft() { return this.getX() + (this.width - getRowWidth()) / 2; }
    }

    private static final class OfferEntry extends ObjectSelectionList.Entry<OfferEntry> {
        private final ModOfferPayload.Offer offer;
        OfferEntry(ModOfferPayload.Offer offer) { this.offer = offer; }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int x = getContentX();
            int y = getContentY() + 1;
            String title = offer.name()
                + (offer.version().isBlank() ? "" : " " + offer.version())
                + (offer.required() ? "  [required]" : "");
            g.text(mc.font, Component.literal(title), x, y,
                offer.required() ? 0xFFFFCC55 : 0xFFFFFFFF, false);
            String from = "from " + hostOf(offer.url())
                + (offer.sha256().isBlank() ? "" : "  · checksum pinned");
            g.text(mc.font, Component.literal(from), x, y + 11, 0xFF9AA3AE, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(offer.name() + " from " + hostOf(offer.url()));
        }
    }
}
