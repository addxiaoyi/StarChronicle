import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.*;
import dev.starcore.starcore.social.friend.FriendService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteDataSource;

/** Actual frozen product JARs in separate JVMs; no server implementation or startup. */
public class FriendStateRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
  static SqlSocialStateStorage storage(DataSource source) {
    DatabaseService database=mock(DatabaseService.class);
    when(database.dataSource()).thenReturn(Optional.of(source));when(database.isRunning()).thenReturn(true);
    return new SqlSocialStateStorage(database,Logger.getLogger("friend-offline"));
  }
  static void set(Object target,String field,Object value)throws Exception {
    var member=target.getClass().getDeclaredField(field);member.setAccessible(true);member.set(target,value);
  }
  static void sql(SQLiteDataSource source,String sql)throws Exception {
    try(var connection=source.getConnection();var statement=connection.createStatement()){statement.execute(sql);}
  }
  static boolean outcomeUnknown(IllegalStateException failure)throws Exception {
    return (Boolean)failure.getClass().getMethod("outcomeUnknown").invoke(failure);
  }
  public static void main(String[] args)throws Exception {
    String phase=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);
    var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+root.resolve("social.db"));
    var store=storage(data);
    if(phase.equals("old-write")) {
      store.ensureTables();
      var service=new FriendService();service.setSqlStorage(store);service.sendFriendRequest(A,B);
      store.saveFriendRelation(C,D,17);store.saveFriendRelation(D,C,18);
      var module=new SocialModule();
      JavaPlugin plugin=mock(JavaPlugin.class);when(plugin.getLogger()).thenReturn(Logger.getLogger("old-friend-file"));
      set(module,"plugin",plugin);set(module,"friendsFile",root.resolve("friends.yml").toFile());
      module.friendService().sendFriendRequest(A,B);module.friendService().loadFriendship(C,D);
      var save=SocialModule.class.getDeclaredMethod("saveFriendsToYaml");save.setAccessible(true);save.invoke(module);
      require(module.friendService().getFriendRequests(A).contains(B),"Old JAR produced sent copy");
    } else if(phase.startsWith("file-")) {
      var service=new FriendService();service.setFileStorage(root.resolve("friends.yml"));
      if(phase.equals("file-reject")) {
        boolean rejected=false;try{service.loadData();}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"Malformed file rejected");
        rejected=false;try{service.saveData();}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"Rejected recovery cannot overwrite file");
      } else {
        service.loadData();require(service.isFriend(C,D),"Original friendships restored");
        switch(phase) {
          case "file-upgrade" -> {
            require(service.exportFriendRequests().isEmpty(),"Old sent copies supply no consent");
            require(service.getUnresolvedLegacyRequests(A).equals(Set.of(B)),"Old evidence retained");
            require(!service.acceptFriendRequest(B,A),"Old direction cannot autoapprove");
            service.saveData();service.sendFriendRequest(A,B);
            require(!service.isFriend(A,B),"Fresh one-sided consent only");
          }
          case "file-accept" -> {
            require(service.getIncomingFriendRequests(B).equals(Set.of(A)),"New direction survives separate JVM");
            require(!service.acceptFriendRequest(A,B),"Sender cannot approve");
            require(service.acceptFriendRequest(B,A),"Recipient accepted durably");
          }
          case "file-reopen" -> {
            require(service.isFriend(A,B)&&service.isFriend(B,A),"Both committed sides reopened");
            require(service.exportFriendRequests().isEmpty(),"Accepted request absent");
          }
          default -> throw new IllegalArgumentException(phase);
        }
      }
    } else {
      var service=new FriendService();service.setSqlStorage(store);
      if(phase.equals("sql-reject")) {
        boolean rejected=false;try{service.loadData();}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"Malformed SQL rejected");
        rejected=false;try{service.saveData();}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"Rejected SQL recovery cannot save");
      } else {
        service.loadData();require(service.isFriend(C,D),"Original friend pair retained");
        switch(phase) {
          case "sql-upgrade" -> {
            require(service.getIncomingFriendRequests(B).equals(Set.of(A)),"SQL preserves old explicit direction");
            require(!service.acceptFriendRequest(A,B),"SQL sender cannot approve");
            require(!service.sendFriendRequest(A,B),"SQL repeat cannot selfapprove");
            service.acceptFriendRequest(B,A);service.saveData();
          }
          case "sql-reopen" -> {
            require(service.isFriend(A,B)&&service.isFriend(B,A),"Committed friendship reopened");
            require(service.exportFriendRequests().isEmpty(),"Consumed requests absent");
          }
          case "sql-failure" -> {
            sql(data,"CREATE TRIGGER reject_friend BEFORE INSERT ON starcore_friend_relations WHEN NEW.player_uuid='"+A+"' BEGIN SELECT RAISE(ABORT,'second row'); END");
            boolean rejected=false;try{service.acceptFriendRequest(B,A);}catch(IllegalStateException failure){rejected=!outcomeUnknown(failure);}
            require(rejected,"Known transaction failure visible");require(!service.isFriend(A,B),"No memory publication");
            require(service.getIncomingFriendRequests(B).equals(Set.of(A)),"Request retained");
          }
          case "sql-pending" -> require(service.getIncomingFriendRequests(B).equals(Set.of(A))&&!service.isFriend(A,B),"Failed transaction reopened unchanged");
          case "sql-unknown" -> {
            AtomicBoolean inject=new AtomicBoolean(true);DataSource faulty=mock(DataSource.class);
            when(faulty.getConnection()).thenAnswer(unused->{
              var original=data.getConnection();
              return Proxy.newProxyInstance(FriendStateRecoveryProbe.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,values)->{
                Object result;try{result=method.invoke(original,values);}catch(InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("commit")&&inject.compareAndSet(true,false))throw new SQLException("Acknowledgement lost");
                return result;
              });
            });
            service.setSqlStorage(storage(faulty));
            boolean unknown=false;try{service.acceptFriendRequest(B,A);}catch(IllegalStateException failure){unknown=outcomeUnknown(failure);}
            require(unknown&&!service.isFriend(A,B),"Unknown commit not published as success");
            boolean blocked=false;try{service.saveData();}catch(IllegalStateException expected){blocked=true;}
            require(blocked,"Old memory cannot overwrite unknown commit");
          }
          default -> throw new IllegalArgumentException(phase);
        }
      }
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
