package dev.regnum.economy.client;

import dev.regnum.economy.money.*;
import dev.regnum.economy.trade.*;
import dev.regnum.economy.market.TreasuryPackets;
import dev.sakhno.regnum.nations.api.v1.*;
import dev.sakhno.regnum.nations.api.client.v1.EconomyContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.glfw.GLFW;

/** Synthetic native-client checks. No world, financial packet or server mutation is needed. */
public final class EconomyUiChecks implements ClientModInitializer {
    private static final UUID ID=new UUID(0,1);
    private int stage, layouts, captureTicks;
    private CompletableFuture<Void> reload;
    private final long started=System.nanoTime();
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                if (System.nanoTime()-started>180_000_000_000L) throw new AssertionError("UI checks timed out");
                if (mc.getOverlay()!=null) return;
                if (mc.screen instanceof AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
                if (stage>=2) {
                    if (++captureTicks<10) return;
                    Files.createDirectories(Path.of("checks/captures"));
                    try (var pixels=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                        pixels.writeToFile(Path.of("checks/captures/phase-c-"+stage+".png"));
                    }
                    if (stage==4) mc.stop();
                    else { stage++; captureTicks=0; showCapture(mc,stage); }
                    return;
                }
                if (!(mc.screen instanceof TitleScreen)) return;
                if (stage==0) {
                    if (!mc.getLanguageManager().getSelected().equals("en_us")) {
                        mc.getLanguageManager().setSelected("en_us"); mc.reloadResourcePacks(); return;
                    }
                    checkLocale(mc); mc.getLanguageManager().setSelected("uk_ua"); reload=mc.reloadResourcePacks(); stage=1;
                } else if (stage==1&&reload.isDone()) {
                    reload.join(); checkLocale(mc); checkMarketLinks();
                    Files.createDirectories(Path.of("checks"));
                    Files.writeString(Path.of("checks/passed.txt"),"PASS "+layouts+" native layouts in EN/UK; modes, safe focus, stale reviews, owner slots, market suggestions\n");
                    System.out.println("ECONOMY UI PASS: "+layouts+" layouts"); stage=2; showCapture(mc,stage);
                }
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        });
    }
    private void checkLocale(Minecraft mc) throws Exception {
        for (int[] size:List.of(new int[]{320,240},new int[]{640,360},new int[]{1100,760})) {
            Inventory inventory=new Inventory(null);
            var constructor=WalletMenu.class.getDeclaredConstructor(int.class,Inventory.class); constructor.setAccessible(true);
            var walletMenu=constructor.newInstance(1,inventory);
            walletMenu.setState(new WalletPackets.State(1,ID,4,0,Long.MAX_VALUE,-1));
            var wallet=new WalletScreen(walletMenu,inventory,Component.translatable("container.regnum-economy.wallet"));
            check(mc,wallet,size);
            require(!((EditBox)get(wallet,"amount")).visible,"Deposit mode hides withdrawal input");
            ((Button)get(wallet,"withdrawMode")).onPress();
            check(mc,wallet,size);
            require(!((Button)get(wallet,"deposit")).visible,"Withdraw mode hides deposit controls");
            var amount=(EditBox)get(wallet,"amount"); amount.setValue("15"); wallet.setFocused(amount);
            wallet.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
            require((long)get(wallet,"pending")==0,"Enter in the amount field sends no withdrawal");
            require(wallet.getFocused()==get(wallet,"withdraw"),"Enter moves to explicit withdrawal control");
            var before=walletMenu.state(); set(wallet,"latestRequest",9L);
            wallet.accept(new WalletPackets.State(1,ID,99,8,0,0)); require(walletMenu.state()==before,"Outdated wallet reply ignored");
            for (boolean own:List.of(false,true)) for (boolean other:List.of(false,true)) {
                var c=TradeMenu.class.getDeclaredConstructor(int.class,Inventory.class); c.setAccessible(true);
                var menu=c.newInstance(2,inventory); menu.setState(new TradePackets.State(2,ID,7,10,20,Long.MAX_VALUE,own,other,own&&other?3:0,0));
                var trade=new TradeScreen(menu,inventory,Component.literal("Long partner name ".repeat(4)));
                check(mc,trade,size);
                require(menu.slots.getFirst().x==33&&menu.slots.getFirst().y==31,"Trade offer slot coordinates preserved");
                set(trade,"submittedRevision",7L); set(trade,"submittedAt",System.currentTimeMillis()-20_000);
                invoke(trade,"updateControls"); require(!((Button)get(trade,"confirm")).active,"Pending confirmation cannot be submitted twice");
            }
            var entity=new EntitySnapshot(EntityRef.city(ID),"Long City ".repeat(5),ID,ID,Optional.empty(),Optional.empty(),0xffaaaaaa,Optional.empty(),ResourceLocation.withDefaultNamespace("overworld"),BlockPos.ZERO);
            for (boolean permitted:List.of(false,true)) {
                var treasury=new TreasuryScreen(new EconomyContext(entity,"owner",Set.of()),new TitleScreen());
                UUID screen=(UUID)get(treasury,"screen");
                treasury.accept(new TreasuryPackets.State(screen,0,ID,4,Long.MAX_VALUE,Long.MAX_VALUE,permitted,permitted,"ready"));
                check(mc,treasury,size); ((EditBox)get(treasury,"amount")).setValue("100");
                var review=TreasuryScreen.class.getDeclaredMethod("review",int.class); review.setAccessible(true); review.invoke(treasury,TreasuryPackets.DEPOSIT);
                require((int)get(treasury,"review")== (permitted?TreasuryPackets.DEPOSIT:-1),"Review requires current permission");
                if (permitted) {
                    check(mc,treasury,size);
                    require(((AbstractWidget)treasury.getFocused()).getMessage().getString().equals(Component.translatable("screen.regnum-economy.treasury.cancel").getString()),"Review focuses Cancel");
                    treasury.accept(new TreasuryPackets.State(screen,0,ID,5,-1,-1,false,false,"stale_context"));
                    require((int)get(treasury,"review")==-1,"Political invalidation cancels review");
                    require((long)get(treasury,"pending")==0,"Review never sends a transfer");
                }
            }
        }
    }
    private void checkMarketLinks() throws Exception {
        var c=Class.forName("dev.regnum.economy.market.Markets");
        var m=c.getDeclaredMethod("action",String.class,String.class,net.minecraft.network.chat.ClickEvent.Action.class); m.setAccessible(true);
        var action=(Component)m.invoke(null,"buy_action","/market buy 7 1",net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND);
        require(action.getStyle().getClickEvent().getAction()==net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,"Buy link suggests; does not transfer");
        require(action.getStyle().getHoverEvent()!=null,"Market link explains command");
    }
    private void check(Minecraft mc,Screen screen,int[] size) throws Exception {
        screen.init(mc,size[0],size[1]);
        var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (int i=0;i<widgets.size();i++) {
            var a=widgets.get(i); String context=screen.getClass().getSimpleName()+" "+Arrays.toString(size)+" "+a.getMessage().getString();
            require(a.getX()>=0&&a.getY()>=0&&a.getRight()<=size[0]&&a.getBottom()<=size[1],"Bounds: "+context);
            for (int j=i+1;j<widgets.size();j++) { var b=widgets.get(j);
                require(a.getX()>=b.getRight()||b.getX()>=a.getRight()||a.getY()>=b.getBottom()||b.getY()>=a.getBottom(),"Overlap: "+context+" / "+b.getMessage());
            }
        }
        layouts++;
    }
    private void showCapture(Minecraft mc,int stage) throws Exception {
        Inventory inventory=new Inventory(null);
        Screen screen;
        if (stage==2) {
            var c=WalletMenu.class.getDeclaredConstructor(int.class,Inventory.class); c.setAccessible(true);
            var menu=c.newInstance(1,inventory); menu.setState(new WalletPackets.State(1,ID,4,0,12345,-1));
            inventory.setItem(0,new net.minecraft.world.item.ItemStack(Denomination.GOLD.item(),3));
            screen=new WalletScreen(menu,inventory,Component.translatable("container.regnum-economy.wallet"));
        } else if (stage==3) {
            var c=TradeMenu.class.getDeclaredConstructor(int.class,Inventory.class); c.setAccessible(true);
            var menu=c.newInstance(2,inventory); menu.setState(new TradePackets.State(2,ID,7,100,200,12345,false,true,0,0));
            screen=new TradeScreen(menu,inventory,Component.literal("Alex ↔ Steve"));
        } else {
            var entity=new EntitySnapshot(EntityRef.city(ID),"Regnum",ID,ID,Optional.empty(),Optional.empty(),0xffaaaaaa,Optional.empty(),ResourceLocation.withDefaultNamespace("overworld"),BlockPos.ZERO);
            var treasury=new TreasuryScreen(new EconomyContext(entity,"owner",Set.of()),new TitleScreen());
            treasury.accept(new TreasuryPackets.State((UUID)get(treasury,"screen"),0,ID,4,12345,500,true,true,"ready"));
            treasury.init(mc,320,240); ((EditBox)get(treasury,"amount")).setValue("100");
            var review=TreasuryScreen.class.getDeclaredMethod("review",int.class); review.setAccessible(true); review.invoke(treasury,TreasuryPackets.DEPOSIT);
            screen=treasury;
        }
        // Render the real screen without ticking a disconnected synthetic player's container.
        mc.setScreen(new Screen(Component.literal("UI capture")) {
            @Override protected void init() { screen.init(mc,320,240); }
            @Override public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float delta) {
                g.fill(0,0,width,height,0xff171a1f); screen.render(g,-1,-1,delta);
            }
        });
    }

    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static Object get(Object o,String key) throws Exception { var f=o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); }
    private static void set(Object o,String key,Object value) throws Exception { var f=o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o,value); }
    private static void invoke(Object o,String key) throws Exception { var m=o.getClass().getDeclaredMethod(key); m.setAccessible(true); m.invoke(o); }
}
