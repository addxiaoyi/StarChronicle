import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.SqlSocialStateStorage;
import dev.starcore.starcore.social.party.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Real old/new product JARs on offline SQLite copies, without a Bukkit server. */
public final class PartySqlRecoveryProbe {
  static final UUID P=new UUID(1,1),Q=new UUID(1,2),A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4),E=new UUID(0,5);
  static Path root;
  static SQLiteDataSource db;
  static SqlSocialStateStorage storage;
  static void setup(Path folder) {
    root=folder;db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+root.resolve("party.db"));
    var service=mock(DatabaseService.class);when(service.dataSource()).thenReturn(Optional.of(db));when(service.isRunning()).thenReturn(true);
    storage=new SqlSocialStateStorage(service,Logger.getLogger("party-offline"));storage.ensureTables();
  }
  static String rows(String table)throws Exception {
    StringBuilder result=new StringBuilder();
    try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
      while(r.next()) {for(int i=1;i<=r.getMetaData().getColumnCount();i++) {String value=String.valueOf(r.getObject(i));result.append(value.length()).append(':').append(value).append('|');}result.append('\n');}
    }
    return result.toString();
  }
  static String originals()throws Exception {
    StringBuilder result=new StringBuilder();for(String table:List.of("starcore_parties","starcore_party_members","starcore_party_invites"))result.append(table).append('\n').append(rows(table));return result.toString();
  }
  static String current()throws Exception{return originals()+rows("starcore_party_invite_bindings")+rows("starcore_party_write_gate");}
  static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void rejected(Runnable action){boolean refused=false;try{action.run();}catch(IllegalStateException expected){refused=true;}require(refused,"Expected explicit rejection");}
  static void result(String phase){System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");}
  public static void main(String[] args)throws Exception {
    String phase=args[0];setup(Path.of(args[1]));
    switch(phase) {
      case "upgrade" -> {
        require(originals().equals(Files.readString(root.resolve("legacy-rows.txt"))),"Schema adoption preserves every original row and extra column");
        var before=storage.loadPartyState();var party=before.parties().get(P);
        require(party.createdTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Original creation time restored");
        var legacy=before.invites().iterator().next();require(legacy.party()==null&&legacy.timestamp()==3456,"Unproven old binding stays unknown with original timestamp");
        String members=rows("starcore_party_members"),invites=rows("starcore_party_invites");
        var change=new PartyState.Entry(P,A,party.members(),party.createdTime(),true,false,8);
        storage.commitPartyState(before,before.withParty(change));
        require(members.equals(rows("starcore_party_members")),"Setting change preserves member IDs, times and metadata");
        require(invites.equals(rows("starcore_party_invites")),"Setting change preserves old invitation identity, timestamp and metadata");
        var expected=storage.loadPartyState();var pending=new HashSet<>(expected.invites());pending.add(new PartyState.Invite(P,A,E,4567));
        storage.commitPartyState(expected,new PartyState(expected.parties(),pending));
        expected=storage.loadPartyState();var all=new HashMap<>(expected.parties());var joined=new HashSet<>(change.members());joined.add(E);
        all.put(P,new PartyState.Entry(P,A,joined,change.createdTime(),true,false,8));
        storage.commitPartyState(expected,new PartyState(all,Set.of(legacy)));
        require(invites.equals(rows("starcore_party_invites")),"Accepted fresh invite removed; old unbound evidence retained exactly");
        Files.writeString(root.resolve("expected-rows.txt"),current());result(phase);System.out.flush();Runtime.getRuntime().halt(0);
      }
      case "reopen" -> {
        require(current().equals(Files.readString(root.resolve("expected-rows.txt"))),"Committed rows survive independent reopen");
        var state=storage.loadPartyState();require(state.parties().containsKey(Q),"Unrelated party retained");
        if(state.parties().containsKey(P)) {
          var party=state.parties().get(P);require(party.members().equals(Set.of(A,B,E)),"Accepted membership retained");
          require(party.friendlyFire()&&!party.expShare(),"Settings survive process halt");
          require(party.createdTime()==Long.parseLong(Files.readString(root.resolve("created.txt"))),"Creation time still original");
          require(state.invites().equals(Set.of(new PartyState.Invite(null,A,C,3456))),"Original unknown binding retained without fabricated party");
        }
      }
      case "reject" -> {String before=current();rejected(storage::loadPartyState);rejected(()->storage.deleteParty(P));require(before.equals(current()),"Rejected state not rewritten");}
      case "disband" -> {
        var old=storage.loadPartyState().parties().get(Q);storage.deleteParty(P);
        require(storage.loadPartyState().parties().equals(Map.of(Q,old)),"Disband preserves unrelated party");
        Files.writeString(root.resolve("expected-rows.txt"),current());
      }
      default -> throw new IllegalArgumentException(phase);
    }
    result(phase);
  }
}

/** Only legacy descriptors are used by this writer. */
final class LegacyPartyWriter {
  public static void main(String[] args)throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    SQLiteDataSource db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+root.resolve("party.db"));
    var service=mock(DatabaseService.class);when(service.dataSource()).thenReturn(Optional.of(db));when(service.isRunning()).thenReturn(true);
    var storage=new SqlSocialStateStorage(service,Logger.getLogger("old-party-offline"));storage.ensureTables();
    Party first=new Party(new UUID(1,1),new UUID(0,1));first.addMember(new UUID(0,2));
    storage.saveParty(first);storage.saveParty(new Party(new UUID(1,2),new UUID(0,4)));
    storage.savePartyInvite(new UUID(0,1),new UUID(0,3),3456);
    for(String table:List.of("starcore_parties","starcore_party_members","starcore_party_invites"))
      try(var c=db.getConnection();var s=c.createStatement()) {s.execute("ALTER TABLE "+table+" ADD COLUMN legacy_memo TEXT");s.execute("UPDATE "+table+" SET legacy_memo='untouched legacy metadata'");}
    try(var c=db.getConnection();var s=c.createStatement()){s.execute("UPDATE starcore_party_members SET joined_at=123");}
    StringBuilder text=new StringBuilder();
    for(String table:List.of("starcore_parties","starcore_party_members","starcore_party_invites")) {
      text.append(table).append('\n');
      try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
        while(r.next()) {for(int i=1;i<=r.getMetaData().getColumnCount();i++) {String value=String.valueOf(r.getObject(i));text.append(value.length()).append(':').append(value).append('|');}text.append('\n');}
      }
    }
    Files.writeString(root.resolve("legacy-rows.txt"),text.toString());Files.writeString(root.resolve("created.txt"),Long.toString(first.getCreatedTime()));
    System.out.println("{\"phase\":\"old-write\",\"verified\":true,\"server_started\":false}");
  }
}
