package dev.aiarena.film;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import java.io.InputStream;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FilmClock {
    private static final String[] KEYS = {"nether","end","dragon","finish"};
    private static final String[] LABELS = {"进入下界","进入末地","击杀末影龙","通关"};
    private static final long[] splits = new long[4], best = new long[4];
    public static String model;
    private static String rules;
    private static Path history;
    private static boolean configured, direct, active, failed;
    private static IntegratedServer session;
    private static long startNanos, startWallMs;
    private static String course;
    private static Path receipt;

    private static void config(MinecraftClient mc) {
        configured=true;
        Path p=mc.runDirectory.toPath().resolve("config/astra-film.json");
        if (!Files.isRegularFile(p)) return;
        try {
            JsonObject q = new JsonParser().parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).getAsJsonObject();
            rules=q.get("rules").getAsString();
            history=q.has("history") ? Paths.get(q.get("history").getAsString()) : mc.runDirectory.toPath().resolve("astra-film/history");
            direct=q.get("route").getAsString().equals("direct");
            model=q.get("model").getAsString().equals("slim") ? "slim" : "default";
        } catch (RuntimeException | IOException e) {
            LogManager.getLogger("Astra Film").warn("Cannot read local presentation options",e);
        }
    }

    public static boolean localSkin(Object player) {
        MinecraftClient mc=MinecraftClient.getInstance();
        return model!=null && ((mc.player==player && mc.getServer()!=null && !mc.getServer().isRemote()) || ReplayLayer.localOwner(player));
    }

    private static String courseId(IntegratedServer server, Path world) throws IOException {
        try (InputStream input=Files.newInputStream(world.resolve("level.dat"))) {
            CompoundTag data=NbtIo.readCompressed(input).getCompound("Data");
            CompoundTag gen=data.getCompound("WorldGenSettings");
            if (gen.isEmpty()) throw new IOException("Missing recorded generation parameters");
            CompoundTag dims=gen.getCompound("dimensions");
            StringBuilder key=new StringBuilder(rules).append('|').append(direct?"direct":"nether");
            for (String name:new String[]{"minecraft:overworld","minecraft:the_nether","minecraft:the_end"}) {
                CompoundTag dim=dims.getCompound(name).getCompound("generator");
                if (!dim.contains("seed")) throw new IOException("Missing generation seed: "+name);
                key.append('|').append(dim.getLong("seed"));
            }
            if (gen.contains("rng_seed")) key.append("|rng=").append(gen.getLong("rng_seed"));
            key.append('|').append(server.getSaveProperties().getDifficulty().getName());
            CompoundTag flags=server.getGameRules().toNbt();
            for (String name:new TreeSet<>(flags.getKeys())) {
                key.append('|').append(name).append('=').append(flags.getString(name));
            }
            return key.toString();
        }
    }

    private static void readPrior(Path dir) throws IOException {
        try (DirectoryStream<Path> past=Files.newDirectoryStream(dir,"*.jsonl")) {
            for (Path path:past) {
                List<String> lines=Files.readAllLines(path,StandardCharsets.UTF_8);
                if (lines.isEmpty()) continue;
                try {
                    JsonObject head=new JsonParser().parse(lines.get(0)).getAsJsonObject();
                    if (!course.equals(head.get("course").getAsString()) ||
                        head.get("wall_ms").getAsLong()>=startWallMs) continue;
                    for (int i=1;i<lines.size();i++) {
                        JsonObject row=new JsonParser().parse(lines.get(i)).getAsJsonObject();
                        String key=row.get("event").getAsString();
                        if ("death".equals(key)) break;
                        for (int k=0;k<KEYS.length;k++) if (KEYS[k].equals(key)) {
                            long t=row.get("ms").getAsLong();
                            if (t>=0 && (best[k]<0 || t<best[k])) best[k]=t;
                        }
                    }
                } catch (RuntimeException e) {
                    LogManager.getLogger("Astra Film").debug("Incomplete local practice receipt: {}",path);
                }
            }
        }
    }

    private static void start(MinecraftClient mc, IntegratedServer server) {
        startNanos=System.nanoTime();
        startWallMs=System.currentTimeMillis();
        Arrays.fill(splits,-1); Arrays.fill(best,-1);
        failed=false;
        Path world=server.getSavePath(WorldSavePath.ROOT).normalize();
        // An existing attempt marker keeps reopened worlds out of the fresh-run PB history.
        Path marker=world.resolve("astra-film-attempt.txt");
        if (Files.exists(marker)) return;
        try {
            course=courseId(server,world);
            Path dir=history;
            Files.createDirectories(dir);
            readPrior(dir);
            String id=UUID.randomUUID().toString();
            receipt=dir.resolve(id+".jsonl");
            JsonObject q=new JsonObject();q.addProperty("event","spawn");q.addProperty("course",course);
            q.addProperty("wall_ms",startWallMs);q.addProperty("player",mc.player.getName().getString());
            JsonObject prior=new JsonObject();
            for (int i=0;i<KEYS.length;i++) if (best[i]>=0) prior.addProperty(KEYS[i],best[i]);
            q.add("previous_best_ms",prior);
            Files.write(marker,id.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            Files.write(receipt,Collections.singletonList(q.toString()),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            active=true;
        } catch (IOException | RuntimeException e) {
            LogManager.getLogger("Astra Film").error("Cannot initialize local split receipt",e);
        }
    }

    private static long elapsed() {
        return Math.max(0,(System.nanoTime()-startNanos)/1000000L);
    }

    private static void record(String key,long ms) {
        JsonObject q=new JsonObject();
        q.addProperty("event",key);q.addProperty("ms",ms);
        try {
            Files.write(receipt,Collections.singletonList(q.toString()),StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        } catch (IOException e) {
            LogManager.getLogger("Astra Film").error("Local split receipt write failed",e);
        }
    }

    private static void mark(int i) {
        if (splits[i]>=0) return;
        splits[i]=elapsed();record(KEYS[i],splits[i]);
    }

    public static void tick(MinecraftClient mc) {
        if (!configured) config(mc);
        if (rules==null) return;
        IntegratedServer current=mc.getServer();
        if (current==null || current.isRemote()) {
            session=null; active=false; return;
        }
        if (current!=session) {
            if (mc.world==null || mc.player==null || mc.isPaused()) return;
            session=current; active=false; start(mc,current);
        }
        if (!active || splits[3]>=0) return;
        if (mc.currentScreen instanceof CreditsScreen) {
            if (splits[1]>=0) mark(3);
            return;
        }
        if (mc.player==null || mc.world==null) return;
        if (!mc.player.isAlive() && !failed) { failed=true; record("death",elapsed()); }
        if (mc.world.getRegistryKey().equals(World.NETHER)) mark(0);
        if (!mc.world.getRegistryKey().equals(World.END)) return;
        mark(1);
        if (splits[2]>=0) return;
        for (Entity e:mc.world.getEntities())
            if (e instanceof EnderDragonEntity && ((EnderDragonEntity)e).getHealth()<=0) { mark(2); break; }
    }

    public static boolean visible() {
        MinecraftClient mc=MinecraftClient.getInstance();
        return active && mc.getServer()!=null && !mc.getServer().isRemote() && !mc.options.hudHidden;
    }

    public static int height() { return direct?60:70; }

    static String clock(long ms,boolean precise) {
        if (ms<0) return "—";
        long s=ms/1000, fraction=ms%1000;
        String decimal=Long.toString(1000+fraction).substring(1,precise?4:3);
        return (s/60)+":"+(s%60<10?"0":"")+(s%60)+"."+decimal;
    }

    private static void right(TextRenderer tr,MatrixStack m,String s,int right,int y,int color) {
        tr.drawWithShadow(m,s,right-tr.getWidth(s),y,color);
    }

    public static void draw(MinecraftClient mc,MatrixStack matrix) {
        TextRenderer tr=mc.textRenderer;
        int edge=mc.getWindow().getScaledWidth()-2;
        long total=splits[3]>=0?splits[3]:elapsed();
        right(tr,matrix,"总计 "+clock(total,true),edge,1,0xffffff);
        int pending=direct?1:0;
        while (pending<KEYS.length && splits[pending]>=0) pending++;
        long legStart=0;
        for (int i=direct?1:0;i<Math.min(pending,3);i++) if (splits[i]>=0) legStart=splits[i];
        right(tr,matrix,"本段 "+clock(total-legStart,false),edge,11,0xe0e0e0);
        int y=22;
        right(tr,matrix,"本局",edge-39,y,0xcecece);
        right(tr,matrix,"最快",edge,y,0xcecece);
        for (int i=direct?1:0;i<KEYS.length;i++) {
            y+=10;
            right(tr,matrix,LABELS[i],edge-78,y,0xe0e0e0);
            right(tr,matrix,clock(splits[i],false),edge-39,y,0xffffff);
            right(tr,matrix,clock(best[i],false),edge,y,0xcecece);
        }
    }
    private FilmClock() {}
}
