import dev.starcore.starcore.social.emote.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Calls the original public API and the original module's YAML format; never starts a server. */
public final class CustomEmoteLegacyProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    var manager=new CustomEmoteManager(new EmoteService());
    var created=manager.createCustomEmote(new UUID(0,1),"旧动作","原说明","pose","BOW",50,7,true);
    var yaml=new YamlConfiguration();yaml.set("customEmotes",manager.serialize().get("customEmotes"));
    Files.writeString(root.resolve("custom-emotes.yml"),"# exact old source\n"+yaml.saveToString(),StandardOpenOption.CREATE_NEW);
    Files.writeString(root.resolve("id.txt"),created.getId(),StandardOpenOption.CREATE_NEW);
    System.out.println("{\"verified\":true,\"phase\":\"legacy\",\"server_started\":false}");
  }
}
