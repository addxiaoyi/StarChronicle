import dev.starcore.starcore.social.*;
import dev.starcore.starcore.social.friend.FriendService;
import dev.starcore.starcore.social.guild.*;
import dev.starcore.starcore.social.party.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import static org.mockito.Mockito.*;

/** Runs against the frozen version-1 exporter, with no Minecraft implementation. */
public final class SocialImportLegacyProbe {
  public static void main(String[] args) throws Exception {
    Path folder=Path.of(args[0]);Files.createDirectories(folder);
    JavaPlugin plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    UUID a=new UUID(0,1),b=new UUID(0,2),c=new UUID(0,3),d=new UUID(0,4);
    FriendService friends=new FriendService();friends.loadFriendship(a,b);friends.loadFriendRequest(a,c);friends.loadLegacyFriendRequest(c,d);friends.loadBlacklistEntry(d,a);friends.loadOnlineStatus(a,true);
    GuildService guilds=new GuildService();Guild guild=new Guild(new UUID(1,1),"Alpha","A",a,1234);guild.addMember(b);guild.setMemberRole(b,GuildRole.OFFICER);guild.setBalance(12.5);guilds.importGuilds(List.of(guild));
    PartyService parties=new PartyService();parties.importParties(List.of(new PartyState.Entry(new UUID(2,1),c,Set.of(c,d),2345,true,false,1).toParty()));
    String file=new SocialImportExportService(plugin,friends,guilds,parties).exportAll("legacy");
    if(file==null||!Files.readString(Path.of(file)).contains("1.0"))throw new AssertionError("Actual old export missing");
    System.out.println("{\"verified\":true,\"phase\":\"legacy-export\",\"server_started\":false}");
  }
}
