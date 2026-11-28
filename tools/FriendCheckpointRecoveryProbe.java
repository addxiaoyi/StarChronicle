import dev.starcore.starcore.social.friend.FriendService;
import dev.starcore.starcore.social.SqlSocialStateStorage;
import dev.starcore.starcore.core.database.DatabaseService;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/** Frozen product JAR file operations only; no server implementation or bootstrap. */
public final class FriendCheckpointRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4);
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static void denied(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"Stale/closed authority must refuse");}
  static FriendService open(Path path){var service=new FriendService();service.setFileStorage(path);service.loadData();return service;}
  static void isolated(FriendService service){
    require(!service.isReady(),"Authority isolated");
    denied(service::snapshot);denied(service::saveData);denied(()->service.getFriends(A));
    denied(()->service.sendFriendRequest(A,C));
  }
  public static void main(String[] args)throws Exception{
    String phase=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);Path path=root.resolve("friends.yml");
    if(phase.startsWith("sql-")){
      var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+root.resolve("social.db"));
      AtomicBoolean inject=new AtomicBoolean(false);DataSource source=mock(DataSource.class);
      when(source.getConnection()).thenAnswer(unused->{
        var actual=database.getConnection();
        return Proxy.newProxyInstance(FriendCheckpointRecoveryProbe.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,values)->{
          Object result;try{result=method.invoke(actual,values);}catch(InvocationTargetException failure){throw failure.getCause();}
          if(method.getName().equals("commit")&&inject.compareAndSet(true,false))throw new SQLException("Acknowledgement lost after real commit");
          return result;
        });
      });
      var api=mock(DatabaseService.class);when(api.dataSource()).thenReturn(Optional.of(source));when(api.isRunning()).thenReturn(true);
      var service=new FriendService();service.setSqlStorage(new SqlSocialStateStorage(api,Logger.getAnonymousLogger()));service.loadData();
      require(service.isFriend(C,D),"Original SQL pair retained");
      if(phase.equals("sql-unknown")){
        inject.set(true);boolean unknown=false;
        try{service.acceptFriendRequest(B,A);}catch(SqlSocialStateStorage.FriendPersistenceException failure){unknown=failure.outcomeUnknown();}
        require(unknown,"Unknown commit acknowledged as unknown");isolated(service);
      }else if(phase.equals("sql-reopen")){
        require(service.isFriend(A,B)&&service.isFriend(B,A),"Actual committed result restored in new JVM");
        require(service.exportFriendRequests().isEmpty(),"Accepted SQL request absent");
        service.close();isolated(service);
      }else throw new IllegalArgumentException(phase);
    }else if(phase.equals("schema2-write")){
      var service=open(path);service.loadFriendship(C,D);service.loadLegacyFriendRequest(C,A);
      service.loadBlacklistEntry(D,B);service.sendFriendRequest(A,B);service.setOnlineStatus(A,true);service.saveData();
    }else if(phase.equals("reject")){
      var service=new FriendService();service.setFileStorage(path);denied(service::loadData);isolated(service);
    }else if(phase.equals("stale")){
      var first=open(path);var second=open(path);first.sendFriendRequest(A,C);byte[] current=Files.readAllBytes(path);
      denied(()->second.sendFriendRequest(A,D));isolated(second);
      require(Arrays.equals(current,Files.readAllBytes(path)),"Stale writer changed authority");
      second.loadData();second.sendFriendRequest(A,D);
      denied(first::saveData);isolated(first);first.loadData();
      require(first.getOutgoingFriendRequests(A).equals(Set.of(C,D)),"Both durable new requests preserved");
    }else if(phase.equals("stale-reopen")){
      var service=open(path);require(service.getOutgoingFriendRequests(A).equals(Set.of(C,D)),"Stale scenario reopened");
    }else{
      var service=open(path);require(service.isFriend(C,D)&&service.isFriend(D,C),"Original friend pair retained");
      switch(phase){
        case "legacy-upgrade" -> {
          require(service.exportFriendRequests().isEmpty(),"Unknown old request cannot consent");
          require(service.getUnresolvedLegacyRequests(A).equals(Set.of(B)),"Legacy evidence retained");
          require(!service.acceptFriendRequest(B,A),"Ambiguous request rejected");
          service.saveData();service.sendFriendRequest(A,B);
        }
        case "schema2-upgrade" -> {
          require(service.getUnresolvedLegacyRequests(C).equals(Set.of(A)),"Schema2 unresolved evidence retained");
          require(service.isBlacklisted(D,B)&&service.getOnlineStatus(A),"Schema2 block/status retained");
          require(service.getIncomingFriendRequests(B).equals(Set.of(A)),"Schema2 incoming direction retained");
          require(!service.acceptFriendRequest(A,B),"Sender cannot consent");service.saveData();
        }
        case "accept" -> {
          require(service.getIncomingFriendRequests(B).equals(Set.of(A)),"Original request reopened");
          require(service.acceptFriendRequest(B,A),"Recipient acceptance committed");
        }
        case "reopen" -> {
          require(service.isFriend(A,B)&&service.isFriend(B,A),"Accepted pair durable");
          require(service.exportFriendRequests().isEmpty(),"Consumed request absent");
          service.close();isolated(service);denied(service::loadData);
        }
        default -> throw new IllegalArgumentException(phase);
      }
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
    if(!phase.equals("schema2-write")){System.out.flush();Runtime.getRuntime().halt(0);}
  }
}
