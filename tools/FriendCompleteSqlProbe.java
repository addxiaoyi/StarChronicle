import static org.mockito.Mockito.*;
import dev.starcore.starcore.social.*;
import dev.starcore.starcore.social.friend.FriendService.State;
import dev.starcore.starcore.core.database.DatabaseService;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Complete SQL snapshots on independent product JVMs, never a Minecraft server. */
public final class FriendCompleteSqlProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static SqlSocialStateStorage storage(SQLiteDataSource source){
    var db=mock(DatabaseService.class);when(db.dataSource()).thenReturn(Optional.of(source));when(db.isRunning()).thenReturn(true);
    return new SqlSocialStateStorage(db,Logger.getAnonymousLogger());
  }
  static void sql(SQLiteDataSource db,String statement)throws Exception{
    try(var c=db.getConnection();var s=c.createStatement()){s.execute(statement);}
  }
  static State accepted(State original){
    Map<UUID,Set<UUID>> friends=new HashMap<>(original.friends());friends.put(A,Set.of(B));friends.put(B,Set.of(A));
    return new State(friends,Map.of(),Map.of(C,Set.of(A)),original.blocked(),original.online());
  }
  public static void main(String[] args)throws Exception{
    String phase=args[0];Path folder=Path.of(args[1]);var db=new SQLiteDataSource();db.setUrl("jdbc:sqlite:"+folder.resolve("social.db"));
    var store=storage(db);
    if(phase.equals("reject")){
      boolean refused=false;try{store.loadFriendState();}catch(IllegalStateException expected){refused=true;}
      require(refused,"Malformed complete state must reject");
    }else{
      store.ensureTables();State before=store.loadFriendState();
      require(before.friends().get(C).contains(D),"Original friend pair recovered");
      switch(phase){
        case "upgrade" -> {
          require(before.incoming().equals(Map.of(B,Set.of(A))),"Original explicit incoming consent recovered");
          store.commitFriendState(before,accepted(before));
          require(store.loadFriendState().equals(accepted(before)),"Complete candidate committed");
        }
        case "reopen" -> {
          require(before.friends().get(A).contains(B)&&before.friends().get(B).contains(A),"Accepted pair survives halt/reopen");
          require(before.incoming().isEmpty()&&before.legacy().equals(Map.of(C,Set.of(A))),"Consumed consent and unresolved contact remain distinct");
          store.commitFriendState(before,before);
        }
        case "failure" -> {
          sql(db,"CREATE TRIGGER reject_friend BEFORE INSERT ON starcore_friend_relations WHEN NEW.player_uuid='"+A+"' BEGIN SELECT RAISE(ABORT,'injected'); END");
          boolean refused=false;try{store.commitFriendState(before,accepted(before));}
          catch(SqlSocialStateStorage.FriendPersistenceException failure){refused=!failure.outcomeUnknown();}
          require(refused,"Known SQL rollback reported");require(store.loadFriendState().equals(before),"No partial candidate visible");
        }
        case "pending" -> require(before.incoming().equals(Map.of(B,Set.of(A)))&&!before.friends().containsKey(A),"Rolled back consent reopens unchanged");
        case "stale" -> {
          var other=storage(db);other.savePlayerStatus(D,true);
          boolean refused=false;try{store.commitFriendState(before,accepted(before));}
          catch(SqlSocialStateStorage.FriendPersistenceException failure){refused=failure.authorityChanged()&&!failure.outcomeUnknown();}
          require(refused,"Cooperating old API invalidates complete expectation");
          var latest=store.loadFriendState();require(latest.online().get(D),"Concurrent status retained");
          require(latest.incoming().equals(Map.of(B,Set.of(A))),"Stale consent untouched");
        }
        case "legacy-api" -> {
          require(before.legacy().equals(Map.of(C,Set.of(A))),"Unknown contact available before reissue");
          store.saveFriendRequest(C,A,123);
          var next=store.loadFriendState();require(next.legacy().isEmpty(),"Fresh request retires only matching unknown contact");
          require(next.incoming().equals(Map.of(C,Set.of(A))),"Fresh sender supplies new explicit consent");
        }
        default -> throw new IllegalArgumentException(phase);
      }
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
    System.out.flush();Runtime.getRuntime().halt(0);
  }
}
