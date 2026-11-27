import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.*;
import dev.starcore.starcore.social.simulation.RelationshipNetworkImpl;
import dev.starcore.starcore.social.simulation.RelationshipNetwork.RelationshipType;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteDataSource;

/** Actual frozen product classes, independent SQLite connections; no server startup. */
public class RelationshipRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2);
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static Object query(SQLiteDataSource source,String sql,Function<ResultSet,?> mapper,Object[] values)throws Exception{
    try(var connection=source.getConnection();var statement=connection.prepareStatement(sql)){
      for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
      try(var rows=statement.executeQuery()){return mapper.apply(rows);}
    }
  }
  public static void main(String[] args)throws Exception{
    String phase=args[0];Path folder=Path.of(args[1]);Files.createDirectories(folder);
    SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+folder.resolve("relationships.db"));
    DatabaseService database=mock(DatabaseService.class);
    when(database.settings()).thenReturn(DatabaseSettings.disabled());when(database.isRunning()).thenReturn(true);
    when(database.dataSource()).thenReturn(Optional.of(source));
    when(database.execute(anyString())).thenAnswer(inv->{try(var connection=source.getConnection();var statement=connection.createStatement()){return statement.execute(inv.getArgument(0));}});
    when(database.query(anyString(),any())).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),new Object[0]));
    when(database.query(anyString(),any(),any(Object[].class))).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),(Object[])inv.getRawArguments()[2]));
    when(database.update(anyString(),any(Object[].class))).thenAnswer(inv->{
      try(var connection=source.getConnection();var statement=connection.prepareStatement(inv.getArgument(0))){
        Object[] values=(Object[])inv.getRawArguments()[1];for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement.executeUpdate();
      }
    });
    JavaPlugin plugin=mock(JavaPlugin.class);when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("offline-relations"));
    if(phase.equals("reject-load")){
      boolean rejected=false;try{var invalid=new RelationshipNetworkImpl(plugin,database);invalid.shutdown();}catch(IllegalStateException expected){rejected=true;}
      require(rejected,"Invalid authority rejected");
    }else{
      var service=new RelationshipNetworkImpl(plugin,database);
      switch(phase){
        case "old-write" -> {
          service.setRelationship(B,A,RelationshipType.FRIEND,50);service.increaseStrength(B,A,5);
          require(service.getRelationship(A,B).strength()==55,"Legacy reverse relation written");
        }
        case "upgrade" -> {
          var old=service.getRelationship(A,B);
          require(old.strength()==55&&old.history().size()==3,"Actual old profile and three historical actions recovered");
          require(old.player1().equals(B)&&old.player2().equals(A),"Legacy orientation retained");
          service.increaseStrength(A,B,5);service.applyDecay(100);
          var next=service.getRelationship(B,A);
          require(next.strength()==54&&next.history().size()==5,"Reverse update and single decay committed");
          require(next.createdAt()==old.createdAt(),"Original creation time retained");service.shutdown();
        }
        case "reopen","reopen-stale" -> {
          var relation=service.getRelationship(A,B);
          require(relation.strength()==(phase.equals("reopen")?54:77),"Independent reopen retained strength");
          require(relation.history().size()==5,"Independent reopen retained complete history");
          require(service.getFriends(A).equals(Set.of(B))&&service.getFriends(B).equals(Set.of(A)),"Symmetric graph restored");service.shutdown();
        }
        case "stale","missing","history-fail" -> {
          try(var connection=source.getConnection();var statement=connection.createStatement()){
            statement.execute(switch(phase){
              case "stale" -> "UPDATE relationships SET strength=77";
              case "missing" -> "DELETE FROM relationships";
              default -> "CREATE TRIGGER reject_history BEFORE INSERT ON relationship_history BEGIN SELECT RAISE(ABORT,'injected'); END";
            });
          }
          boolean rejected=false;try{service.increaseStrength(A,B,1);}catch(IllegalStateException expected){rejected=true;}
          require(rejected,"Rejected mutation reports failure");
          boolean isolated=false;try{service.getRelationship(A,B);}catch(IllegalStateException expected){isolated=true;}
          require(isolated,"Failed service isolated");service.shutdown();
        }
        default -> throw new IllegalArgumentException(phase);
      }
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
