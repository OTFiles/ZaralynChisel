import java.io.File;
import java.util.zip.ZipFile;
public class ZipProbe {
    public static void main(String[] args) throws Exception {
        File jar = new File("/storage/emulated/0/FCL/.minecraft/versions/1.21.1-NeoForge/1.21.1-NeoForge.jar");
        try (ZipFile z = new ZipFile(jar)) {
            System.out.println("entries=" + z.size());
            String[] probes = {
                "assets/minecraft/textures/block/stone.png",
                "assets/minecraft/textures/block/grass_block_top.png",
                "assets/minecraft/textures/block/stone_top.png"
            };
            for (String p : probes) {
                System.out.println(p + " -> " + (z.getEntry(p) != null ? "FOUND" : "null"));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
