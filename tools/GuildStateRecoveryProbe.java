import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.SqlSocialStateStorage;
import dev.starcore.starcore.social.guild.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.sqlite.SQLiteDataSource;

/** Actual service layer across independent JVMs. No server implementation is created. */
public final class GuildStateRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
  static void rejected(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"Expected explicit rejection");}
  static Plugin plugin(Path root){var plugin=mock(Plugin.class);when(plugin.getDataFolder()).thenReturn(root.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("guild-state-offline"));return plugin;}
  static SqlSocialStateStorage storage(Path root){var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+root.resolve("guild.db"));var api=mock(DatabaseService.class);when(api.dataSource()).thenReturn(Optional.of(database));when(api.isRunning()).thenReturn(true);var storage=new SqlSocialStateStorage(api,Logger.getLogger("guild-state-sql"));storage.ensureTables();return storage;}
  static String snapshot(GuildService service){
    StringBuilder text=new StringBuilder();service.getAllGuilds().stream().sorted(Comparator.comparing(Guild::getId)).forEach(g->{
      text.append(g.getId()).append('|').append(g.getName()).append('|').append(g.getTag()).append('|').append(g.getLeader()).append('|').append(g.getCreatedTime()).append('|').append(g.getLevel()).append('|').append(g.getExperience()).append('|').append(g.getBalance()).append('|').append(g.getDescription()).append('\n');
      for(UUID id:new TreeSet<>(g.getMembers()))text.append(id).append(':').append(g.getMemberRole(id)).append('\n');
    });
    service.snapshot().invites().stream().sorted(Comparator.comparing(GuildService.GuildInvite::guildId).thenComparing(GuildService.GuildInvite::targetId)).forEach(invite->text.append(invite).append('\n'));return text.toString();
  }
  static void result(String phase){System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");}
  public static void main(String[] args)throws Exception {
    String phase=args[0],mode=args[1];Path root=Path.of(args[2]);Files.createDirectories(root);var service=new GuildService();if(mode.equals("sql"))service.setSqlStorage(storage(root));service.initialize(plugin(root));
    if(phase.equals("reject")) {rejected(service::loadGuilds);require(!service.isReady(),"Rejected state not available");rejected(service::saveGuilds);rejected(()->service.createGuild(D,"must-not-create","N"));service.close();result(phase);return;}
    service.loadGuilds();
    switch(phase) {
      case "upgrade" -> {
        var guild=service.getPlayerGuild(A);require(guild!=null,"Legacy guild restored");require(guild.getId().toString().equals(Files.readString(root.resolve("guild-id.txt"))),"Legacy identity retained");
        require(guild.getCreatedTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Original creation time retained");require(guild.getMemberRole(B)==GuildRole.OFFICER,"Officer role retained");require(guild.getBalance()==(mode.equals("file")?99:0),"Balance matches actual old persistence capability");
        service.inviteMember(guild.getId(),B,C);service.addGuildExperience(guild.getId(),1500);
        Files.writeString(root.resolve("expected.txt"),snapshot(service));result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "reopen" -> {require(snapshot(service).equals(Files.readString(root.resolve("expected.txt"))),"Exact state survives independent service reopen");service.close();}
      case "accept" -> {
        var guild=service.getPlayerGuild(A);require(service.getGuildInvites(C).size()==1,"Invite restored");require(service.acceptInvite(C,guild.getId()),"Acceptance committed");
        require(service.getGuildInvites(C).isEmpty(),"Invite consumed");require(service.getPlayerGuild(C).getId().equals(guild.getId()),"Member published");Files.writeString(root.resolve("expected.txt"),snapshot(service));result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "disband" -> {
        var guild=service.getPlayerGuild(A);rejected(()->service.disbandGuild(guild.getId(),A));require(service.disbandGuild(guild.getId(),A,true),"Confirmed disband committed");
        require(service.getPlayerGuild(A)==null&&service.getPlayerGuild(B)==null&&service.getPlayerGuild(C)==null,"Membership removed");Files.writeString(root.resolve("expected.txt"),snapshot(service));service.close();
      }
      default -> throw new IllegalArgumentException(phase);
    }
    result(phase);
  }
}

final class LegacyGuildStateWriter {
  public static void main(String[] args)throws Exception {
    String mode=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);var plugin=mock(Plugin.class);when(plugin.getDataFolder()).thenReturn(root.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("legacy-guild-state"));
    var service=new GuildService();service.initialize(plugin);var guild=service.createGuild(new UUID(0,1),"Legacy","L");guild.addMember(new UUID(0,2));guild.setMemberRole(new UUID(0,2),GuildRole.OFFICER);guild.setBalance(99);
    if(mode.equals("file"))service.saveToYaml();else {
      var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+root.resolve("guild.db"));var api=mock(DatabaseService.class);when(api.dataSource()).thenReturn(Optional.of(database));when(api.isRunning()).thenReturn(true);var storage=new SqlSocialStateStorage(api,Logger.getLogger("legacy-guild-sql"));storage.ensureTables();storage.saveGuild(guild);
    }
    Files.writeString(root.resolve("guild-id.txt"),guild.getId().toString());Files.writeString(root.resolve("created.txt"),Long.toString(guild.getCreatedTime()));
    System.out.println("{\"phase\":\"old-write\",\"verified\":true,\"server_started\":false}");
  }
}
