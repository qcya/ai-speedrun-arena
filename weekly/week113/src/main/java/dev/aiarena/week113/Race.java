package dev.aiarena.week113;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.item.ItemStack;
import com.google.gson.*;

/** Positions in the reference guide keyboard controls. All movement and actions resolve in game. */
public final class Race {
    private static class Frame {
        final String dimension;
        final double x,y,z;
        final float yaw,pitch;
        Frame(String[] parts) {
            dimension=parts[1]; x=Double.parseDouble(parts[2]); y=Double.parseDouble(parts[3]);
            z=Double.parseDouble(parts[4]); yaw=Float.parseFloat(parts[5]); pitch=Float.parseFloat(parts[6]);
        }
    }
    private static final int LAST=6720;
    private static final Frame[] path=new Frame[LAST+1];
    private static IntegratedServer session;
    private static int cursor,limit,ticks,dimensionPhase;
    private static Path directory;
    private static long start;
    private static BufferedWriter journal;
    private static boolean opened,active,finished;
    private static String lastScreen="", lastSlots="";
    static {
        try(BufferedReader stream=new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Race.class.getResourceAsStream("/xiamoys-113.csv.gz")),StandardCharsets.UTF_8))) {
            String line;
            while ((line=stream.readLine())!=null) {
                String[] p=line.split(","); int i=Integer.parseInt(p[0]); if(i<=LAST) path[i]=new Frame(p);
            }
            for(int i=1;i<=LAST;i++) if(path[i]==null) path[i]=path[i-1];
        } catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    public static int referenceTick(){return cursor;}
    public static void cap(int max){limit=Math.min(limit,max);}
    public static void advanceTo(int tick){cursor=Math.max(cursor, Math.min(tick,LAST));}
    public static void stop(MinecraftClient mc){
        mc.options.keyForward.setPressed(false); mc.options.keyBack.setPressed(false);
        mc.options.keyLeft.setPressed(false); mc.options.keyRight.setPressed(false);
        mc.options.keyJump.setPressed(false); mc.options.keySneak.setPressed(false);
        mc.options.keySprint.setPressed(false);
    }
    private static void turn(ClientPlayerEntity player,float yaw,float pitch,float max) {
        player.yaw += MathHelper.clamp(MathHelper.wrapDegrees(yaw-player.yaw),-max,max);
        player.pitch += MathHelper.clamp(pitch-player.pitch,-max,max);
    }
    public static void aim(MinecraftClient mc,Vec3d target) {
        Vec3d eye=mc.player.getCameraPosVec(1F); double dx=target.x-eye.x,dz=target.z-eye.z,dy=target.y-eye.y;
        turn(mc.player,(float)Math.toDegrees(Math.atan2(-dx,dz)),
                (float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))),32);
    }
    public static double distanceToReference(MinecraftClient mc, int t) {
        Frame f=path[MathHelper.clamp(t,0,LAST)];return mc.player.squaredDistanceTo(f.x,f.y,f.z);
    }
    public static Vec3d stance(int tick) {
        Frame f=path[MathHelper.clamp(tick,0,LAST)];
        return new Vec3d(f.x,f.y,f.z);
    }
    private static String dimension(MinecraftClient mc){
        return mc.world.getRegistryKey()==World.NETHER?"nether":mc.world.getRegistryKey()==World.END?"end":"overworld";
    }
    private static void follow(MinecraftClient mc,String dim) {
        ClientPlayerEntity p=mc.player;
        stop(mc);
        if(mc.currentScreen!=null)return;
        int end=Math.min(limit,dim.equals("end")?LAST:dim.equals("nether")?5411:dimensionPhase==0?1404:5805);
        if(cursor>end)return;
        int closest=cursor;double best=distanceToReference(mc,cursor);
        for(int i=cursor+1;i<=Math.min(cursor+8,end);i++){
            if(!path[i].dimension.equals(dim))break;
            double dist=distanceToReference(mc,i);
            if(dist<best-.002){best=dist;closest=i;}
        }
        cursor=closest;
        if(cursor<end&&best<1.45)cursor++;
        if(dim.equals("nether")&&cursor>2089&&cursor<3226&&p.isOnGround()&&path[cursor].y>p.getY()+1.7) {
            // A different approach to a bastion ledge can land on the floor below it.
            // Rejoin the first reachable descending footstep before the pending interaction.
            for(int i=cursor+1;i<=Math.min(cursor+72,end);i++) {
                Frame candidate=path[i];
                if(candidate.y<=p.getY()+1.05 && candidate.y>=p.getY()-1.7
                        && Math.hypot(candidate.x-p.getX(),candidate.z-p.getZ())<6) {
                    cursor=i;
                    break;
                }
            }
        }
        if (dim.equals("nether") && cursor>=2298 && cursor<2380
                && Middle.climbLedge(mc,path[cursor].y)) return;
        int target=cursor;
        while(target<end&&target<cursor+6&&path[target+1].dimension.equals(dim)
                && Math.hypot(path[target].x-p.getX(),path[target].z-p.getZ())<1.45
                && Math.abs(path[target].y-p.getY())<1.35)target++;
        Frame f=path[target];
        double dx=f.x-p.getX(),dz=f.z-p.getZ(),dy=f.y-p.getY(),flat=Math.hypot(dx,dz);
        if(!f.dimension.equals(dim)) return;
        boolean wet=p.isTouchingWater();
        boolean moving=flat>.19;
        float wanted=moving?(float)Math.toDegrees(Math.atan2(-dx,dz)):f.yaw;
        float pitch=wet?(float)-Math.toDegrees(Math.atan2(dy,Math.max(.1,flat))):MathHelper.clamp(f.pitch,-48,48);
        turn(p,wanted,MathHelper.clamp(pitch,-86,86),20);
        float err=MathHelper.wrapDegrees(wanted-p.yaw);
        mc.options.keyForward.setPressed(moving&&Math.abs(err)<64);
        mc.options.keyLeft.setPressed(moving&&err< -28);
        mc.options.keyRight.setPressed(moving&&err>28);
        mc.options.keyBack.setPressed(moving&&Math.abs(err)>122);
        mc.options.keySprint.setPressed(moving&&(flat>1||wet));
        mc.options.keyJump.setPressed(wet ? dy>.30 : dy>.5 || (moving&&p.horizontalCollision));
        mc.options.keySneak.setPressed(wet && dy<-.5);
    }
    private static void closeLog() {
        if(journal!=null) try { journal.close(); } catch(IOException ignored) {}
        journal=null;
    }
    private static JsonArray stack(ItemStack s) {
        JsonArray value=new JsonArray();
        if(!s.isEmpty()){
            value.add(Registry.ITEM.getId(s.getItem()).toString());value.add(s.getCount());
            if(s.hasTag())value.add(s.getTag().toString());
        }
        return value;
    }
    static void recordDistance(MinecraftClient mc,int before,int after) {
        JsonObject details=new JsonObject();
        details.addProperty("before",before);
        details.addProperty("after",after);
        details.addProperty("shortcut",after<before?"Shift+F3+F":"F3+F");
        record(mc,"render_distance_shortcut",details);
    }

    private static void record(MinecraftClient mc,String kind) {
        record(mc,kind,new JsonObject());
    }

    private static void record(MinecraftClient mc,String kind,JsonObject j) {
        if(journal==null)return;
        try {
            j.addProperty("event",kind);
            j.addProperty("elapsed_ms",(System.nanoTime()-start)/1000000);j.addProperty("run_tick",ticks);
            j.addProperty("reference_tick",cursor);
            j.addProperty("opening_action",Opening.status);
            j.addProperty("middle_action",Middle.status);
            j.addProperty("end_action",FinalFight.status);
            if(mc.player!=null&&mc.world!=null) {
                ClientPlayerEntity p=mc.player; j.addProperty("dim",dimension(mc));
                j.addProperty("x",p.getX());j.addProperty("y",p.getY());j.addProperty("z",p.getZ());
                j.addProperty("yaw",p.yaw);j.addProperty("pitch",p.pitch);j.addProperty("hp",p.getHealth());
                String gui=mc.currentScreen instanceof InventoryScreen?"inventory":mc.currentScreen instanceof GenericContainerScreen?"chest":
                        mc.currentScreen instanceof CraftingScreen?"crafting":mc.currentScreen==null?"":mc.currentScreen.getClass().getName();
                j.addProperty("screen",gui);j.addProperty("epoch_ms",System.currentTimeMillis());
                j.addProperty("hotbar_slot",p.inventory.selectedSlot);j.addProperty("hunger",p.getHungerManager().getFoodLevel());
                j.addProperty("eye_height",p.getCameraPosVec(1F).y-p.getY());j.addProperty("air",p.getAir());
                j.addProperty("armor",p.getArmor());j.addProperty("xp_level",p.experienceLevel);j.addProperty("xp_progress",p.experienceProgress);
                j.addProperty("submerged",p.isSubmergedIn(net.minecraft.tag.FluidTags.WATER));j.addProperty("absorption",p.getAbsorptionAmount());
                j.addProperty("hurt_ticks",p.hurtTime);j.addProperty("vehicle",p.getVehicle()==null?"":Registry.ENTITY_TYPE.getId(p.getVehicle().getType()).toString());
                if(mc.currentScreen instanceof HandledScreen){
                    j.addProperty("gui_title",mc.currentScreen.getTitle().getString());j.addProperty("gui_slot_count",p.currentScreenHandler.slots.size());
                    int width=mc.getWindow().getScaledWidth(),height=mc.getWindow().getScaledHeight();
                    j.addProperty("gui_width",width);j.addProperty("gui_height",height);
                    j.addProperty("mouse_x",mc.mouse.getX()*width/mc.getWindow().getWidth());
                    j.addProperty("mouse_y",mc.mouse.getY()*height/mc.getWindow().getHeight());
                    JsonArray slots=new JsonArray();
                    for(Slot slot:p.currentScreenHandler.slots){
                        if(slot.hasStack()){JsonArray v=new JsonArray();v.add(slot.id);for(JsonElement q:stack(slot.getStack()))v.add(q);slots.add(v);}
                    }
                    String signature=p.currentScreenHandler.syncId+"/"+slots+"/"+gui;
                    if(!signature.equals(lastSlots)){j.add("gui_slots",slots);lastSlots=signature;}
                    j.add("cursor_stack",stack(p.inventory.getCursorStack()));
                } else {lastSlots="";}

                JsonArray inv=new JsonArray();
                for(int i=0;i<p.inventory.size();i++){ItemStack s=p.inventory.getStack(i);if(!s.isEmpty()){
                    JsonArray a=new JsonArray();a.add(i);a.add(Registry.ITEM.getId(s.getItem()).toString());a.add(s.getCount());inv.add(a);}}
                j.add("inventory",inv);j.add("offhand",stack(p.getOffHandStack()));j.addProperty("blocking",p.isBlocking());
            } journal.write(j.toString());journal.newLine();journal.flush();
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static void tick(MinecraftClient mc) {
        // The instance-scoped option permits a launcher to enter exactly one freshly prepared local save.
        if(!opened&&mc.currentScreen instanceof TitleScreen&&mc.overlay==null) {
            Path marker=mc.runDirectory.toPath().resolve("astra-local-autostart.txt");
            if(Files.isRegularFile(marker))try{
                String name=new String(Files.readAllBytes(marker),StandardCharsets.UTF_8).trim();
                if(name.matches("[a-zA-Z0-9_-]{1,80}") && Files.isRegularFile(mc.runDirectory.toPath().resolve("saves").resolve(name).resolve("astra-113.local"))){
                    opened=true;mc.startIntegratedServer(name);return;
                }
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
        IntegratedServer srv=mc.getServer();
        if(session!=srv){
            closeLog(); session=srv; directory=srv==null?null:srv.getSavePath(WorldSavePath.ROOT);
            active=directory!=null&&!srv.isRemote()&&Files.isRegularFile(directory.resolve("astra-113.local"));
            cursor=0;ticks=0;dimensionPhase=0;finished=false;lastScreen="";Opening.reset();Middle.reset();FinalFight.reset();
        }
        if(!active||finished)return;
        if(mc.currentScreen instanceof CreditsScreen){record(mc,"credits");finished=true;closeLog();return;}
        if(mc.player==null||mc.world==null||mc.isPaused())return;
        if(ticks==0){try{journal=Files.newBufferedWriter(directory.resolve("astra-run.jsonl"),StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);}catch(IOException e){active=false;return;}
            start=System.nanoTime();lastSlots="";record(mc,"spawn");}
        ticks++;
        String dim=dimension(mc);
        if(dim.equals("nether")&&dimensionPhase==0){dimensionPhase=1;cursor=1405;record(mc,"enter_nether");}
        if(dim.equals("overworld")&&dimensionPhase==1){dimensionPhase=2;cursor=5412;record(mc,"return_overworld");}
        if(dim.equals("end")&&dimensionPhase<3){dimensionPhase=3;cursor=5806;record(mc,"enter_end");}
        String screen=mc.currentScreen==null?"":mc.currentScreen.getClass().getName();
        record(mc,"state");lastScreen=screen;
        if(!mc.player.isAlive()){record(mc,"death");stop(mc);finished=true;closeLog();return;}
        limit=LAST;
        boolean controlled=dimensionPhase==0?Opening.tick(mc,cursor):
                dimensionPhase==3?FinalFight.tick(mc,cursor):Middle.tick(mc,cursor);
        if(!controlled) follow(mc,dim);
    }
}
