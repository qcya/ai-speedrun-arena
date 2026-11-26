package dev.aiarena.film;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

/** Per-instance skin for the local actor. Also works when another mod selects its own resource pack list. */
public final class LocalTexture {
    private static Identifier texture;
    private static boolean tried;
    public static Identifier get() {
        if(tried)return texture;
        tried=true;
        MinecraftClient mc=MinecraftClient.getInstance();
        Path path=mc.runDirectory.toPath().resolve("resourcepacks/Astra-local-skin.zip");
        try(ZipFile zip=new ZipFile(path.toFile())) {
            ZipEntry entry=zip.getEntry("assets/astra_film/textures/local_skin.png");
            if(entry==null) return null;
            try(InputStream in=zip.getInputStream(entry)) {
                NativeImage skin=NativeImage.read(in);
                if(skin.getWidth()!=64 || skin.getHeight()!=64){skin.close();return null;}
                texture=mc.getTextureManager().registerDynamicTexture("astra_local_skin",new NativeImageBackedTexture(skin));
                LogManager.getLogger("Astra Film").info("Loaded local 64×64 skin for Astra ({})",FilmClock.model);
            }
        } catch(IOException e){LogManager.getLogger("Astra Film").warn("Cannot load this instance's local skin",e);}
        return texture;
    }
    private LocalTexture(){}
}
