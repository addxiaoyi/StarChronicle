import static org.mockito.Mockito.*;
import dev.starcore.starcore.social.SocialImportExportService;
import dev.starcore.starcore.social.friend.FriendService;
import dev.starcore.starcore.social.guild.*;
import dev.starcore.starcore.social.party.PartyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** File transfer verification through real old/new service classes, never a server runtime. */
public final class SocialExportFilesProbe {
  static final UUID A=new UUID(0,1), B=new UUID(0,2), C=new UUID(0,3);
  static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
  static JavaPlugin plugin(Path root){var p=mock(JavaPlugin.class);when(p.getDataFolder()).thenReturn(root.toFile());when(p.getLogger()).thenReturn(Logger.getLogger("social-export-offline"));return p;}
  static String digest(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
  static SocialImportExportService service(Path root,FriendService friends,GuildService guilds){return new SocialImportExportService(plugin(root),friends,guilds,new PartyService());}
  public static void main(String[] args)throws Exception {
    String phase=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);
    var friends=new FriendService();var guilds=new GuildService();var transfer=service(root,friends,guilds);
    switch(phase){
      case "old-write" -> {
        friends.loadFriendship(A,B);friends.loadFriendRequest(A,C);
        Guild g=new Guild(new UUID(1,1),"Legacy Export","L",A,1234);g.addMember(B);g.setMemberRole(B,GuildRole.OFFICER);g.setBalance(77);guilds.importGuilds(List.of(g));
        String all=transfer.exportAll("legacy");String one=transfer.exportPlayerData(A);require(all!=null&&one!=null,"Old exports exist");
        Files.writeString(root.resolve("old-player-name.txt"),Path.of(one).getFileName().toString());
        Files.writeString(root.resolve("old-guild-state.txt"),guilds.snapshot().toString());
        Files.writeString(root.resolve("legacy-hash.txt"),digest(Path.of(all)));
      }
      case "upgrade" -> {
        require(transfer.importAll(root.resolve("social_exports/legacy.json").toString()),"Old full JSON accepted");
        require(friends.isFriend(A,B),"Old friendship restored");
        Guild g=guilds.getPlayerGuild(A);require(g!=null&&g.getId().equals(new UUID(1,1))&&g.getCreatedTime()==1234,"Guild identity/time restored");
        require(g.getMemberRole(B)==GuildRole.OFFICER&&g.getBalance()==77,"Guild roles and balance restored");
        require(transfer.importPlayerData(Files.readString(root.resolve("old-player-name.txt")),A),"Old player file accepted");
        require(friends.getIncomingFriendRequests(A).equals(Set.of(C)),"Directed request retained");
        require(digest(root.resolve("social_exports/legacy.json")).equals(Files.readString(root.resolve("legacy-hash.txt"))),"Import retains exact original bytes");
        String current=transfer.exportAll("new-export");require(current!=null,"Current export committed");
        String one=transfer.exportPlayerData(A);require(one!=null,"Current player export committed");
        Files.writeString(root.resolve("new-player-name.txt"),Path.of(one).getFileName().toString());
        Files.writeString(root.resolve("new-hash.txt"),digest(Path.of(current)));
        System.out.println("{\"phase\":\"upgrade\",\"verified\":true}");System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "reopen" -> {
        require(transfer.importAll("new-export.json"),"Independent process imports committed export");
        require(transfer.importPlayerData(Files.readString(root.resolve("new-player-name.txt")),A),"Independent player import");
        require(friends.isFriend(A,B)&&friends.getIncomingFriendRequests(A).equals(Set.of(C)),"Friendship/request round trip");
        require(guilds.getPlayerGuild(A).getBalance()==77,"Guild round trip");
        require(transfer.exportAll("new-export")==null,"Existing export not replaced");
        require(digest(root.resolve("social_exports/new-export.json")).equals(Files.readString(root.resolve("new-hash.txt"))),"Original new bytes retained");
        require(transfer.listExports().size()==4,"Four committed JSON files listed");
      }
      case "reject" -> {
        Path path=root.resolve("social_exports/reject.json");require(!transfer.importAll(path.toString()),"Invalid copy rejected");
        require(friends.exportFriendships().isEmpty()&&guilds.getAllGuilds().isEmpty(),"No service mutation after invalid input");
        require(transfer.exportAll("../outside")==null,"Export traversal rejected");
        Path outside=root.resolve("outside.json");Files.writeString(outside,"{}");require(!transfer.importAll(outside.toString()),"Outside import rejected");
        require(Files.readString(outside).equals("{}"),"Outside file unchanged");
      }
      default -> throw new IllegalArgumentException(phase);
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
