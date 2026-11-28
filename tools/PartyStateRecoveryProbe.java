import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.SqlSocialStateStorage;
import dev.starcore.starcore.social.party.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.sqlite.SQLiteDataSource;

/** Real legacy/current service processes on file and SQLite copies, without a Bukkit server. */
public final class PartyStateRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void rejected(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"Expected explicit rejection");}
  static Plugin plugin(Path root){var plugin=mock(Plugin.class);when(plugin.getDataFolder()).thenReturn(root.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("party-state-offline"));return plugin;}
  static SqlSocialStateStorage storage(Path root){var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("party.db"));var db=mock(DatabaseService.class);when(db.dataSource()).thenReturn(Optional.of(source));when(db.isRunning()).thenReturn(true);var store=new SqlSocialStateStorage(db,Logger.getLogger("party-state-sql"));store.ensureTables();return store;}
  static String snapshot(PartyService service) {
    StringBuilder text=new StringBuilder();service.getAllParties().stream().sorted(Comparator.comparing(Party::getId)).forEach(p->{
      text.append(p.getId()).append('|').append(p.getLeader()).append('|').append(p.getCreatedTime()).append('|').append(p.isFriendlyFire()).append('|').append(p.isExpShare()).append('|').append(p.getMaxMembers()).append('\n');
      new TreeSet<>(p.getMembers()).forEach(id->text.append(id).append('\n'));
    });service.snapshot().invites().stream().sorted(Comparator.comparing(PartyState.Invite::inviter).thenComparing(PartyState.Invite::target)).forEach(invite->text.append(invite).append('\n'));return text.toString();
  }
  static void result(String phase){System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");}
  public static void main(String[] args)throws Exception {
    String phase=args[0],mode=args[1];Path root=Path.of(args[2]);Files.createDirectories(root);
    var service=new PartyService();if(mode.equals("sql"))service.setSqlStorage(storage(root));service.initialize(plugin(root));
    if(phase.equals("reject")){rejected(service::loadData);require(!service.isReady(),"Invalid state isolated");rejected(service::saveData);rejected(()->service.createParty(D));rejected(service::getAllParties);service.close();result(phase);return;}
    service.loadData();
    switch(phase) {
      case "upgrade" -> {
        var party=service.getPlayerParty(A);require(party!=null,"Legacy party restored");require(party.getId().toString().equals(Files.readString(root.resolve("party-id.txt"))),"Legacy identity retained");
        require(party.getCreatedTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Original creation time retained");require(party.getMembers().equals(Set.of(A,B)),"All original members retained");
        require(service.getPartyInvites(C).isEmpty(),"Unknown legacy party binding cannot grant membership");rejected(()->service.acceptInvite(C,A));
        var legacy=service.snapshot().invites().iterator().next();require(legacy.party()==null&&legacy.target().equals(C),"Unproven old invitation retained as unknown");
        require(legacy.timestamp()==Long.parseLong(Files.readString(root.resolve("invite-time.txt"))),"Original invitation time retained");
        service.setFriendlyFire(party.getId(),A,true);service.setExpShare(party.getId(),A,false);service.inviteMember(party.getId(),A,D);
        Files.writeString(root.resolve("expected.txt"),snapshot(service));result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "reopen" -> {require(snapshot(service).equals(Files.readString(root.resolve("expected.txt"))),"Exact complete state survives independent reopen");service.close();}
      case "accept" -> {
        var party=service.getPlayerParty(A);require(service.getPartyInvites(D).equals(Set.of(A)),"Fresh bound invitation restored");require(service.acceptInvite(D,A),"Acceptance committed");
        require(service.getPartyInvites(D).isEmpty(),"Fresh invite consumed");require(service.getPlayerParty(D).getId().equals(party.getId()),"Member published");require(service.snapshot().invites().size()==1,"Unbound old invitation retained as evidence");
        Files.writeString(root.resolve("expected.txt"),snapshot(service));result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "disband" -> {
        var party=service.getPlayerParty(A);require(service.disbandParty(party.getId(),A),"Disband committed");require(service.getAllParties().isEmpty()&&service.snapshot().invites().isEmpty(),"Members and outgoing old invitations removed together");
        Files.writeString(root.resolve("expected.txt"),snapshot(service));service.close();
      }
      default -> throw new IllegalArgumentException(phase);
    }
    result(phase);
  }
}

/** Only descriptors available in the legacy product are used. */
final class LegacyPartyStateWriter {
  public static void main(String[] args)throws Exception {
    String mode=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);var plugin=mock(Plugin.class);when(plugin.getDataFolder()).thenReturn(root.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("legacy-party-state"));
    var service=new PartyService();service.initialize(plugin);
    SqlSocialStateStorage store=null;
    if(mode.equals("sql")){var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("party.db"));var db=mock(DatabaseService.class);when(db.dataSource()).thenReturn(Optional.of(source));when(db.isRunning()).thenReturn(true);store=new SqlSocialStateStorage(db,Logger.getLogger("legacy-party-sql"));store.ensureTables();service.setSqlStorage(store);}
    service.loadData();var party=service.createParty(new UUID(0,1));service.inviteMember(party.getId(),new UUID(0,1),new UUID(0,2));service.acceptInvite(new UUID(0,2),new UUID(0,1));service.inviteMember(party.getId(),new UUID(0,1),new UUID(0,3));service.saveData();
    long timestamp;
    if(store!=null){Map<UUID,Set<UUID>> pending=new HashMap<>();Map<UUID,Map<UUID,Long>> times=new HashMap<>();store.loadAllPartyInvites(pending,times);timestamp=times.get(new UUID(0,3)).get(new UUID(0,1));}
    else {var yaml=new org.bukkit.configuration.file.YamlConfiguration();yaml.load(root.resolve("social/party-invites.yml").toFile());timestamp=yaml.getLong(new UUID(0,3)+"."+new UUID(0,1));}
    Files.writeString(root.resolve("party-id.txt"),party.getId().toString());Files.writeString(root.resolve("created.txt"),Long.toString(party.getCreatedTime()));Files.writeString(root.resolve("invite-time.txt"),Long.toString(timestamp));
    System.out.println("{\"phase\":\"old-write\",\"verified\":true,\"server_started\":false}");
  }
}
