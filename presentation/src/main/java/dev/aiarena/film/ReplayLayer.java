package dev.aiarena.film;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import org.apache.logging.log4j.LogManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.*;

/** Draw recorded local screen state into a flat, standard ReplayMod export. */
public final class ReplayLayer extends Screen {
    private static final Identifier ICONS=new Identifier("textures/gui/icons.png");
    private static final Identifier WIDGETS=new Identifier("textures/gui/widgets.png");
    private static final Identifier BAG=new Identifier("textures/gui/container/inventory.png");
    private static final Identifier CHEST=new Identifier("textures/gui/container/generic_54.png");
    private static final Identifier TABLE=new Identifier("textures/gui/container/crafting_table.png");
    private static final Identifier RECIPE=new Identifier("textures/gui/recipe_button.png");
    private static final String[] SPLITS={"进入下界","进入末地","击杀末影龙","通关"};
    private static Object renderer, file;
    private static ReplayLayer layer;
    private static boolean drawing;
    private final List<JsonObject> samples=new ArrayList<>();
    private final long[] marks={-1,-1,-1,-1}, previous={-1,-1,-1,-1};
    private final UUID owner;
    private final boolean direct;
    private final Object sender;
    private final Method senderClock;
    private final int replayStart, fromMs, toMs;
    private final RecordedDebugChat debugChat;
    private int lastIndex, atMs;
    private PlayerEntity avatar;
    private CreditsScreen credits;
    private float creditsTicks;
    private ScreenHandler handler;
    private String handlerType="";
    private ReplayLayer(JsonObject head,Object sender) throws ReflectiveOperationException {
        super(new LiteralText(""));
        this.sender=sender;this.senderClock=Class.forName("com.replaymod.replay.ReplaySender").getMethod("currentTimeStamp");
        this.direct="direct".equals(string(head,"route","nether"));
        this.owner=UUID.fromString(head.get("source_uuid").getAsString());
        this.replayStart=head.get("run_at_replay_ms").getAsInt();
        this.fromMs=head.get("from_ms").getAsInt();this.toMs=head.get("to_ms").getAsInt();
        this.debugChat=new RecordedDebugChat(head.getAsJsonArray("render_distance_feedback"));
        JsonArray slots=null;
        String lastScreen="";
        for (JsonElement val:head.getAsJsonArray("rows")) {
            JsonObject row=val.getAsJsonObject();
            int t=row.get("elapsed_ms").getAsInt();String event=string(row,"event","");
            if(event.equals("render_distance_shortcut")) continue;
            int idx=event.equals("enter_nether")?0:event.equals("enter_end")?1:
                event.equals("dragon")||event.equals("dragon_zero")||event.equals("dragon_dead")?2:event.equals("credits")?3:-1;
            if (idx>=0 && marks[idx]<0) marks[idx]=t;
            if (row.has("dragon_hp") && row.get("dragon_hp").getAsDouble()==0 && marks[2]<0) marks[2]=t;
            if (!row.has("x")) continue;
            String scr=string(row,"screen","");
            if (!scr.equals(lastScreen)) slots=null;
            lastScreen=scr;
            if (row.has("gui_slots")) slots=row.getAsJsonArray("gui_slots");
            else if (slots!=null) row.add("gui_slots",slots);
            samples.add(row);
        }
        if (head.has("current_splits_ms")) {
            JsonObject actual=head.getAsJsonObject("current_splits_ms");
            String[] keys={"nether","end","dragon"};
            for(int i=0;i<keys.length;i++) if(marks[i]<0 && actual.has(keys[i])) marks[i]=actual.get(keys[i]).getAsLong();
        }
        if (head.has("previous_best_ms")) {
            JsonObject pb=head.getAsJsonObject("previous_best_ms");String[] keys={"nether","end","dragon","finish"};
            for(int i=0;i<4;i++) if(pb.has(keys[i])) previous[i]=pb.get(keys[i]).getAsLong();
        }
    }
    private static String string(JsonObject r,String key,String fallback){return r.has(key)?r.get(key).getAsString():fallback;}
    private static int integer(JsonObject r,String key,int fallback){return r.has(key)?r.get(key).getAsInt():fallback;}
    public static void begin(Object newRenderer) {
        drawing=false;
        if (MinecraftClient.getInstance().getServer()!=null) return;
        try {
            if (renderer!=newRenderer) {
                renderer=newRenderer;
                Class<?> replay=Class.forName("com.replaymod.replay.ReplayModReplay");
                Object owner=replay.getField("instance").get(null);
                Object active=replay.getMethod("getReplayHandler").invoke(owner);
                Object next=active.getClass().getMethod("getReplayFile").invoke(active);
                if (file!=next) {
                    file=next;layer=null;
                    Object opt=Class.forName("com.replaymod.replaystudio.replay.ReplayFile").getMethod("get",String.class)
                              .invoke(next,"astra/film-overlay.json");
                    InputStream input=(InputStream)Class.forName("com.replaymod.replaystudio.lib.guava.base.Optional").getMethod("orNull").invoke(opt);
                    if (input!=null) {
                        try(Reader reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
                            JsonObject h=new JsonParser().parse(reader).getAsJsonObject();
                            if (integer(h,"schema",0)==1) layer=new ReplayLayer(h,active.getClass().getMethod("getReplaySender").invoke(active));
                        }
                    }
                }
            }
            if (layer==null || layer.samples.isEmpty()) return;
            layer.atMs=(Integer)layer.senderClock.invoke(layer.sender)-layer.replayStart;
            if (layer.atMs<0) return;
            LocalExport.progress(Math.max(0,layer.atMs-layer.fromMs),layer.toMs-layer.fromMs);
            drawing=true;
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            layer=null;LogManager.getLogger("Astra Film").error("Cannot read native replay overlay",e);
        }
    }
    public static boolean localOwner(Object p) {
        return drawing && layer!=null && p instanceof AbstractClientPlayerEntity
                 && ((AbstractClientPlayerEntity)p).getUuid().equals(layer.owner);
    }
    public static void finish() {
        if (!drawing) return;
        try { layer.paint(MinecraftClient.getInstance()); } finally { drawing=false; }
    }
    private void paint(MinecraftClient mc) {
        while(lastIndex+1<samples.size() && integer(samples.get(lastIndex+1),"elapsed_ms",0)<=atMs) lastIndex++;
        while(lastIndex>0 && integer(samples.get(lastIndex),"elapsed_ms",0)>atMs) lastIndex--;
        JsonObject row=samples.get(lastIndex);
        if(mc.world==null || mc.player==null) return;
        int w=mc.getWindow().getScaledWidth(),h=mc.getWindow().getScaledHeight();
        if(client!=mc || width!=w || height!=h) init(mc,w,h);
        avatar=mc.world.getPlayerByUuid(owner);
        if(avatar==null && (marks[3]<0 || atMs<marks[3])) return;
        RenderSystem.matrixMode(5889); RenderSystem.pushMatrix(); RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0,w,h,0.0,1000.0,3000.0);
        RenderSystem.matrixMode(5888); RenderSystem.pushMatrix(); RenderSystem.loadIdentity();
        RenderSystem.translatef(0,0,-2000); RenderSystem.clear(256,MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.enableTexture();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.color4f(1,1,1,1);
        try {
            MatrixStack m=new MatrixStack();
            if (marks[3]>=0 && atMs>=marks[3]) {
                float ticks=(atMs-marks[3])/50f;
                if (credits==null || ticks<creditsTicks) {
                    credits=new CreditsScreen(true,()->{});
                    credits.init(mc,w,h);creditsTicks=0;
                }
                credits.render(m,0,0,ticks-creditsTicks);
                creditsTicks=ticks;
                drawClocks(m);
                return;
            }
            if (row.has("hotbar_slot")) hotbar(m,row);
            debugChat.render(mc,m,atMs,integer(row,"run_tick",Math.floorDiv(atMs,50)),h);
            String screen=string(row,"screen","");
            if ((screen.equals("inventory") || screen.equals("chest") || screen.equals("crafting"))
                    && row.has("gui_slots") && row.has("mouse_x")) gui(m,row,screen);
            drawClocks(m);
        } finally {
            RenderSystem.color4f(1,1,1,1); RenderSystem.enableDepthTest();RenderSystem.depthMask(true);
            RenderSystem.matrixMode(5888);RenderSystem.popMatrix();RenderSystem.matrixMode(5889);
            RenderSystem.popMatrix();RenderSystem.matrixMode(5888);
        }
    }
    private static ItemStack stack(JsonArray a,int offset) {
        if(a==null || a.size()<offset+2) return ItemStack.EMPTY;
        Identifier id=new Identifier(a.get(offset).getAsString());
        if(!Registry.ITEM.containsId(id)) return ItemStack.EMPTY;
        ItemStack stack=new ItemStack(Registry.ITEM.get(id),a.get(offset+1).getAsInt());
        if(a.size()>offset+2) try { stack.setTag(StringNbtReader.parse(a.get(offset+2).getAsString())); }
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) {throw new IllegalArgumentException("Invalid item NBT",e);}
        return stack;
    }
    private void bind(Identifier texture){RenderSystem.color4f(1,1,1,1);client.getTextureManager().bindTexture(texture);}
    private void item(ItemStack item,int x,int y){
        if(item.isEmpty()) return;
        DiffuseLighting.enableGuiDepthLighting();RenderSystem.enableDepthTest();
        client.getItemRenderer().renderInGuiWithOverrides(item,x,y);
        client.getItemRenderer().renderGuiItemOverlay(textRenderer,item,x,y);
        RenderSystem.disableDepthTest();
    }
    private void hotbar(MatrixStack m,JsonObject row) {
        int mid=width/2,y=height-22;bind(WIDGETS);
        drawTexture(m,mid-91,y,0,0,182,22);
        drawTexture(m,mid-92+20*integer(row,"hotbar_slot",0),y-1,0,22,24,22);
        if(row.has("inventory")) for(JsonElement e:row.getAsJsonArray("inventory")){
            JsonArray a=e.getAsJsonArray();int i=a.get(0).getAsInt();
            if(i>=0 && i<9)item(stack(a,1),mid-88+i*20,y+3);
        }
        int hp=integer(row,"hp",20),food=integer(row,"hunger",20);
        bind(ICONS);
        for(int i=0;i<10;i++){
            int x=mid-91+i*8,fy=height-39,fx=mid+91-i*8-9;
            drawTexture(m,x,fy,16,0,9,9);
            if(hp>2*i)drawTexture(m,x,fy,hp==2*i+1?61:52,0,9,9);
            drawTexture(m,fx,fy,16,27,9,9);
            if(food>2*i)drawTexture(m,fx,fy,food==2*i+1?61:52,27,9,9);
        }
        int armor=integer(row,"armor",0);
        if(armor>0) for(int i=0;i<10;i++) {
            int points=armor-i*2;
            drawTexture(m,mid-91+i*8,height-49,points>=2?34:points==1?25:16,9,9,9);
        }
        int air=integer(row,"air",300);
        if(air<300 || row.has("submerged") && row.get("submerged").getAsBoolean()) {
            int full=Math.max(0,(int)Math.ceil((air-2)*10.0/300.0));
            int partial=Math.max(0,(int)Math.ceil(air*10.0/300.0)-full);
            for(int i=0;i<full+partial;i++)drawTexture(m,mid+91-9-i*8,height-49,i<full?16:25,18,9,9);
        }
        if(row.has("xp_progress")) {
            int left=mid-91,bar=height-29;
            drawTexture(m,left,bar,0,64,182,5);
            int progress=(int)(row.get("xp_progress").getAsDouble()*183);
            if(progress>0)drawTexture(m,left,bar,0,69,Math.min(182,progress),5);
            int level=integer(row,"xp_level",0);
            if(level>0) {
                String n=String.valueOf(level);
                textRenderer.drawWithShadow(m,n,mid-textRenderer.getWidth(n)/2,bar-7,0x80ff20);
            }
        }
        drawTexture(m,mid-7,height/2-7,0,0,15,15);
    }
    private void gui(MatrixStack m, JsonObject row,String type) {
        int count=integer(row,"gui_slot_count",type.equals("chest")?63:0);
        int rows=Math.max(1,Math.min(6,(count-36)/9));
        String key=type+":"+rows+":"+avatar.getEntityId();
        if(handler==null || !key.equals(handlerType)) {
            handlerType=key;
            if(type.equals("inventory"))handler=new PlayerScreenHandler(avatar.inventory,true,avatar);
            else if(type.equals("crafting"))handler=new CraftingScreenHandler(0,avatar.inventory);
            else handler=new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3,0,avatar.inventory,
                         new net.minecraft.inventory.SimpleInventory(9*rows),rows);
        }
        int boxHeight=type.equals("chest")?114+18*rows:166;
        int x=(width-176)/2,y=(height-boxHeight)/2;
        int mx=width/2+(int)Math.round(row.get("mouse_x").getAsDouble()-integer(row,"gui_width",width)/2.0);
        int my=height/2+(int)Math.round(row.get("mouse_y").getAsDouble()-integer(row,"gui_height",height)/2.0);
        RenderSystem.disableDepthTest();fillGradient(m,0,0,width,height,0xc0101010,0xd0101010);
        if(type.equals("chest")){
            bind(CHEST);int top=rows*18+17;drawTexture(m,x,y,0,0,176,top);drawTexture(m,x,y+top,0,126,176,96);
        } else { bind(type.equals("inventory")?BAG:TABLE);drawTexture(m,x,y,0,0,176,166); }
        if(type.equals("inventory")){
            InventoryScreen.drawEntity(x+51,y+75,30,x+51-mx,y+25-my,avatar);
        }
        if(!type.equals("chest")){
            bind(RECIPE);int bx=x+(type.equals("inventory")?104:5),by=y+(type.equals("inventory")?61:35);
            drawTexture(m,bx,by,0f,(mx>=bx && mx<bx+20 && my>=by && my<by+18)?19f:0f,20,18,256,256);
        }
        String title=string(row,"gui_title",type.equals("chest")?"箱子":"合成");
        textRenderer.draw(m,title,x+(type.equals("inventory")?97:type.equals("crafting")?28:8),y+6,0x404040);
        if(!type.equals("inventory"))textRenderer.draw(m,"物品栏",x+8,y+boxHeight-94,0x404040);
        ItemStack hover=ItemStack.EMPTY;
        for(JsonElement e:row.getAsJsonArray("gui_slots")){
            JsonArray a=e.getAsJsonArray();int id=a.get(0).getAsInt();
            if(id<0 || id>=handler.slots.size())continue;
            Slot slot=handler.getSlot(id);ItemStack it=stack(a,1);int sx=x+slot.x,sy=y+slot.y;
            item(it,sx,sy);
            if(mx>=sx && mx<sx+16 && my>=sy && my<sy+16) hover=it;
        }
        for(Slot slot:handler.slots){
            int sx=x+slot.x,sy=y+slot.y;
            if(mx>=sx && mx<sx+16 && my>=sy && my<sy+16){fill(m,sx,sy,sx+16,sy+16,0x80ffffff);break;}
        }
        ItemStack cursor=row.has("cursor_stack")?stack(row.getAsJsonArray("cursor_stack"),0):ItemStack.EMPTY;
        if(!cursor.isEmpty()) item(cursor,mx-8,my-8);
        else if(!hover.isEmpty()) renderTooltip(m,hover,mx,my);
        // OS pointers are outside Minecraft's framebuffer. Repaint the recorded position.
        for(int i=0;i<7;i++){int w=Math.min(i/2+1,4);fill(m,mx,my+i,mx+w+1,my+i+1,0xff202020);
            if(i>1 && w>1) fill(m,mx+1,my+i,mx+w,my+i+1,0xffffffff);}
    }
    private void drawClocks(MatrixStack m) {
        long total=marks[3]>=0?Math.min(atMs,marks[3]):atMs;int edge=width-2, y=22;
        long leg=0;for(int i=0;i<3;i++)if(marks[i]>=0 && marks[i]<=total)leg=marks[i];
        textRight(m,"总计 "+FilmClock.clock(total,true),edge,1,0xffffff);
        textRight(m,"本段 "+FilmClock.clock(total-leg,false),edge,11,0xe0e0e0);
        textRight(m,"本局",edge-39,y,0xcecece);textRight(m,"最快",edge,y,0xcecece);
        for(int i=direct?1:0;i<4;i++){y+=10;textRight(m,SPLITS[i],edge-78,y,0xe0e0e0);
            textRight(m,FilmClock.clock(marks[i]>=0 && marks[i]<=total?marks[i]:-1,false),edge-39,y,0xffffff);
            textRight(m,FilmClock.clock(previous[i],false),edge,y,0xcecece);}
    }
    private void textRight(MatrixStack m,String s,int x,int y,int color){
        textRenderer.drawWithShadow(m,s,x-textRenderer.getWidth(s),y,color);
    }
}
