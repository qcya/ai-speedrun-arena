package dev.aiarena.film;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.apache.logging.log4j.LogManager;
import java.io.*;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** One explicit local export job. Opens ReplayMod's native renderer with a prebuilt spectator path. */
public final class LocalExport {
    private static boolean checked, opened, running;
    private static Path input, output, configDir;
    private static int lastReport=-5000;
    private static int warm;
    private static String encoder="";
    private static int width=1920, height=1080, bitrate=20*1024*1024;
    private static Class<?> type(String name) throws ClassNotFoundException {return Class.forName(name);}
    private static Object replay() throws ReflectiveOperationException {
        Class<?> c=type("com.replaymod.replay.ReplayModReplay");return c.getField("instance").get(null);
    }
    public static void tick(MinecraftClient mc) {
        if(running)return;
        try {
            if(!checked){
                checked=true; configDir=mc.runDirectory.toPath().resolve("config");
                Path marker=configDir.resolve("astra-render-once.json");
                if(!Files.isRegularFile(marker)) return;
                Path active=configDir.resolve("astra-render-active.json");
                Files.move(marker,active,StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(configDir.resolve("astra-render-failed.txt"));
                Files.deleteIfExists(configDir.resolve("astra-render-done.json"));
                JsonObject job=new JsonParser().parse(new String(Files.readAllBytes(active),StandardCharsets.UTF_8)).getAsJsonObject();
                String resolution=job.has("resolution") ? job.get("resolution").getAsString() : "1080p";
                if (resolution.equals("2160p")) {
                    width=3840;
                    height=2160;
                    bitrate=64*1024*1024;
                } else if (!resolution.equals("1080p")) {
                    throw new IOException("Unsupported export resolution: " + resolution);
                }
                if(job.has("ffmpeg")) {
                    Path path=Paths.get(job.get("ffmpeg").getAsString());
                    if(!path.isAbsolute() || !Files.isRegularFile(path))throw new IOException("Expected an absolute FFmpeg executable");
                    encoder=path.toString();
                }
                input=Paths.get(job.get("input").getAsString()).toAbsolutePath();
                output=Paths.get(job.get("output").getAsString()).toAbsolutePath();
                if(!Files.isRegularFile(input) || Files.exists(output))throw new IOException("Choose an existing replay and a new output");
                Files.createDirectories(output.getParent());
            }
            if(input==null)return;
            if(!opened && mc.currentScreen instanceof TitleScreen && mc.overlay==null){
                opened=true;
                Object replay=replay();replay.getClass().getMethod("startReplay",File.class).invoke(replay,input.toFile());
                if(replay.getClass().getMethod("getReplayHandler").invoke(replay)==null)
                    throw new IOException("ReplayMod requests mod compatibility confirmation. Install the recorded Minecraft and mod versions (Week 113: MCSR Ranked 6.0.12) in the export instance.");
            } else if(opened && mc.world!=null && mc.player!=null && ++warm>=15){
                running=true; render(mc);
            }
        } catch(Exception e){
            running=true;LogManager.getLogger("Astra Film").error("Native video export failed",e);
            Throwable cause=e;
            while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
            try {
                if(configDir!=null) Files.write(configDir.resolve("astra-render-failed.txt"),
                        java.util.Collections.singletonList(cause.toString()),StandardCharsets.UTF_8);
            } catch(IOException reportError) {LogManager.getLogger("Astra Film").error("Cannot write failure receipt",reportError);}
            mc.scheduleStop();
        }
    }
    static void progress(int elapsed,int total) {
        if(!running || configDir==null || elapsed-lastReport<5000) return;
        lastReport=elapsed;
        JsonObject status=new JsonObject();status.addProperty("rendered_ms",elapsed);status.addProperty("duration_ms",total);
        Path tmp=configDir.resolve("astra-render-progress.tmp"),dst=configDir.resolve("astra-render-progress.json");
        try {
            Files.write(tmp,java.util.Collections.singletonList(status.toString()),StandardCharsets.UTF_8);
            Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) {LogManager.getLogger("Astra Film").warn("Cannot write rendering progress",e);}
    }
    private static void render(MinecraftClient mc) throws Exception {
        Object api=replay();Object active=api.getClass().getMethod("getReplayHandler").invoke(api);
        Class<?> replayFileClass=type("com.replaymod.replaystudio.replay.ReplayFile");
        Object file=active.getClass().getMethod("getReplayFile").invoke(active);
        Class<?> sp=type("com.replaymod.simplepathing.SPTimeline"), registry=type("com.replaymod.replaystudio.pathing.PathingRegistry");
        Object ser=type("com.replaymod.replaystudio.pathing.serialize.TimelineSerialization")
                .getConstructor(registry,replayFileClass).newInstance(sp.newInstance(),file);
        Object timeline=((Map<?,?>)ser.getClass().getMethod("load").invoke(ser)).get("Astra · 第一人称 1×");
        if(timeline==null)throw new IOException("The replay has no Astra spectator preset");
        Class<?> settings=type("com.replaymod.render.RenderSettings");
        String base=settings.getName()+"$";
        Object mode=type(base+"RenderMethod").getField("DEFAULT").get(null);
        Object preset=type(base+"EncodingPreset").getField("MP4_CUSTOM").get(null);
        Object aa=type(base+"AntiAliasing").getField("NONE").get(null);
        String args=(String)preset.getClass().getMethod("getValue").invoke(preset);
        args=args.replace("-an ","-an -threads 2 -preset veryfast ");
        Object options=null;
        for(Constructor<?> c:settings.getConstructors())if(c.getParameterCount()==22)
            options=c.newInstance(mode,preset,width,height,60,bitrate,output.toFile(),
                        false,false,false,false,false,null,360,180,false,false,false,aa,encoder,args,true);
        if(options==null)throw new IOException("Unsupported ReplayMod version");
        Object renderer=type("com.replaymod.render.rendering.VideoRenderer")
                .getConstructor(settings,type("com.replaymod.replay.ReplayHandler"),type("com.replaymod.replaystudio.pathing.path.Timeline"))
                .newInstance(options,active,timeline);
        if(!Boolean.TRUE.equals(renderer.getClass().getMethod("renderVideo").invoke(renderer)))
            throw new IOException("ReplayMod stopped the render");
        if(!Files.isRegularFile(output) || Files.size(output)==0)throw new IOException("FFmpeg returned an empty movie");
        Files.move(configDir.resolve("astra-render-active.json"),configDir.resolve("astra-render-done.json"),StandardCopyOption.REPLACE_EXISTING);
        LogManager.getLogger("Astra Film").info("Native render complete: {}",output);
        mc.scheduleStop();
    }
    private LocalExport(){}
}
