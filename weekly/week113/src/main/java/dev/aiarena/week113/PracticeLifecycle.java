package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Optional local supervisor. The OS releases the lock only when this JVM closes. */
public final class PracticeLifecycle {
    private static boolean checked, closing;
    private static Path request;
    private static FileChannel owner;
    private static FileLock ownership;
    private static long nextCheck;

    private PracticeLifecycle() {}

    public static void tick(MinecraftClient mc) {
        if (!checked) {
            checked=true;
            Path dir=mc.runDirectory.toPath().resolve("astra-practice");
            Path tokenFile=dir.resolve("enabled.txt");
            if (!Files.isRegularFile(tokenFile)) return;
            try {
                String id=UUID.fromString(new String(Files.readAllBytes(tokenFile),StandardCharsets.UTF_8).trim()).toString();
                owner=FileChannel.open(dir.resolve(id+".lock"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                ownership=owner.tryLock(0,1,false);
                if (ownership==null) return;
                Files.createFile(dir.resolve(id+".ready"));
                request=dir.resolve(id+".close");
            } catch (IOException | RuntimeException ex) {
                System.err.println("Astra practice supervisor: "+ex);
            }
        }
        if (request==null || closing || System.nanoTime()<nextCheck) return;
        nextCheck=System.nanoTime()+250000000L;
        if (Files.isRegularFile(request)) {
            closing=true;
            // Same normal shutdown entry as the game window's Close button.
            mc.scheduleStop();
        }
    }
}
