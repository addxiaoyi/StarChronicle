import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.SqlSocialStateStorage;
import dev.starcore.starcore.social.guild.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Independent old/new product processes, no Bukkit server or world. */
public final class GuildSqlRecoveryProbe {
  static final UUID G=new UUID(1,1),H=new UUID(1,2),A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static Path root;
  static SQLiteDataSource database;
  static SqlSocialStateStorage storage;
  static void setup(Path path) {
    root=path;database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+root.resolve("guild.db"));
    DatabaseService service=mock(DatabaseService.class);when(service.dataSource()).thenReturn(Optional.of(database));when(service.isRunning()).thenReturn(true);
    storage=new SqlSocialStateStorage(service,Logger.getLogger("guild-offline"));storage.ensureTables();
  }
  static String rows(String table)throws Exception {
    StringBuilder text=new StringBuilder();
    try(var c=database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
      while(r.next()) { for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
        String value=String.valueOf(r.getObject(i));text.append(value.length()).append(':').append(value).append('|');
      }text.append('\n'); }
    }
    return text.toString();
  }
  static String originalTables()throws Exception {
    StringBuilder text=new StringBuilder();for(String table:List.of("starcore_guilds","starcore_guild_members","starcore_guild_invites"))text.append(table).append('\n').append(rows(table));return text.toString();
  }
  static String currentTables()throws Exception {return originalTables()+rows("starcore_guild_balances")+rows("starcore_guild_write_gate");}
  static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
  static void rejected(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"Expected explicit rejection");}
  static void result(String phase){System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");}
  public static void main(String[] args)throws Exception {
    String phase=args[0];setup(Path.of(args[1]));
    switch(phase) {
      case "upgrade" -> {
        require(originalTables().equals(Files.readString(root.resolve("legacy-rows.txt"))),"Original guild/member/invite row IDs, timestamps and metadata retained by schema adoption");
        String members=rows("starcore_guild_members"),invites=rows("starcore_guild_invites");
        var state=storage.loadGuildState();var guild=state.guilds().get(G).toGuild();
        require(guild.getCreatedTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Original creation time restored");
        require(guild.getBalance()==0,"Legacy SQL never stored the in-memory balance");
        guild.setBalance(42.5);guild.setDescription("durable updated description");storage.saveGuild(guild);
        require(members.equals(rows("starcore_guild_members")),"Untouched membership metadata preserved");
        require(invites.equals(rows("starcore_guild_invites")),"Untouched invitation identity/time preserved");
        var expected=storage.loadGuildState();
        var candidate=expected.guilds().get(G).toGuild();candidate.addMember(C);
        var guilds=new HashMap<>(expected.guilds());guilds.put(G,GuildState.Entry.fromGuild(candidate));
        storage.commitGuildState(expected,new GuildState(guilds,Set.of()));
        Files.writeString(root.resolve("expected-rows.txt"),currentTables());
        result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "reopen" -> {
        require(currentTables().equals(Files.readString(root.resolve("expected-rows.txt"))),"All current rows survive a separate reopen");
        var state=storage.loadGuildState();require(state.guilds().containsKey(H),"Unrelated guild retained");
        if(state.guilds().containsKey(G)) {
          var guild=state.guilds().get(G);require(guild.balance()==42.5,"Balance restored");
          require(guild.members().containsKey(C),"Accepted membership restored");require(state.invites().isEmpty(),"Consumed invite stays absent");
          require(guild.createdTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Creation time still original");
        }
      }
      case "reject" -> {
        String before=currentTables();rejected(storage::loadGuildState);
        rejected(()->storage.deleteGuild(G));require(before.equals(currentTables()),"Rejected database never rewritten");
      }
      case "disband" -> {
        var old=storage.loadGuildState().guilds().get(H);storage.deleteGuild(G);
        require(storage.loadGuildState().guilds().equals(Map.of(H,old)),"Disband removes only selected guild");
        Files.writeString(root.resolve("expected-rows.txt"),currentTables());
      }
      default -> throw new IllegalArgumentException(phase);
    }
    result(phase);
  }
}

/** Only legacy descriptors are referenced so this class runs with the actual old product JAR. */
final class LegacyGuildWriter {
  public static void main(String[] args)throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    SQLiteDataSource db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+root.resolve("guild.db"));
    var service=mock(DatabaseService.class);when(service.dataSource()).thenReturn(Optional.of(db));when(service.isRunning()).thenReturn(true);
    var storage=new SqlSocialStateStorage(service,Logger.getLogger("old-guild-offline"));storage.ensureTables();
    var first=new Guild(new UUID(1,1),"Alpha","A",new UUID(0,1));first.addMember(new UUID(0,2));first.setBalance(99);
    var other=new Guild(new UUID(1,2),"Other","O",new UUID(0,4));
    storage.saveGuild(first);storage.saveGuild(other);storage.saveGuildInvite(first.getId(),first.getLeader(),new UUID(0,3),3456);
    StringBuilder text=new StringBuilder();
    for(String table:List.of("starcore_guilds","starcore_guild_members","starcore_guild_invites")) {
      text.append(table).append('\n');
      try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
        while(r.next()) {for(int i=1;i<=r.getMetaData().getColumnCount();i++) {String value=String.valueOf(r.getObject(i));text.append(value.length()).append(':').append(value).append('|');}text.append('\n');}
      }
    }
    Files.writeString(root.resolve("legacy-rows.txt"),text.toString());Files.writeString(root.resolve("created.txt"),Long.toString(first.getCreatedTime()));
    System.out.println("{\"phase\":\"old-write\",\"verified\":true,\"server_started\":false}");
  }
}
