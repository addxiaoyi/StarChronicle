import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.*;
import dev.starcore.starcore.social.simulation.*;
import io.papermc.paper.threadedregions.scheduler.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteDataSource;

/** Frozen-product influence SQL and scheduler probe. No server is initialized. */
public class SocialInfluenceRecoveryProbe {
  static final UUID PLAYER=UUID.fromString("22222222-3333-4444-5555-666666666666");
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static Object query(SQLiteDataSource source,String sql,Function<ResultSet,?> mapper,Object[] values) throws Exception {
    try(var connection=source.getConnection();var statement=connection.prepareStatement(sql)){
      for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
      try(var rows=statement.executeQuery()){return mapper.apply(rows);}
    }
  }
  public static void main(String[] args) throws Exception {
    String phase=args[0];Path folder=Path.of(args[1]);Files.createDirectories(folder);
    SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+folder.resolve("influence.db"));
    DatabaseService database=mock(DatabaseService.class);
    when(database.settings()).thenReturn(DatabaseSettings.disabled());
    when(database.isRunning()).thenReturn(true);when(database.dataSource()).thenReturn(Optional.of(source));
    when(database.execute(anyString())).thenAnswer(inv->{
      try(var connection=source.getConnection();var statement=connection.createStatement()){
        return statement.execute(inv.getArgument(0));
      }
    });
    when(database.query(anyString(),any())).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),new Object[0]));
    when(database.query(anyString(),any(),any(Object[].class))).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),(Object[])inv.getRawArguments()[2]));
    when(database.update(anyString(),any(Object[].class))).thenAnswer(inv->{
      try(var connection=source.getConnection();var statement=connection.prepareStatement(inv.getArgument(0))){
        Object[] values=(Object[])inv.getRawArguments()[1];
        for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
        return statement.executeUpdate();
      }
    });
    JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("offline-influence"));
    when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);
    AsyncScheduler scheduler=mock(AsyncScheduler.class);when(server.getAsyncScheduler()).thenReturn(scheduler);
    AtomicReference<Consumer<ScheduledTask>> tick=new AtomicReference<>();
    when(scheduler.runAtFixedRate(eq(plugin),any(),anyLong(),anyLong(),any())).thenAnswer(inv->{
      tick.set(inv.getArgument(1));return mock(ScheduledTask.class);
    });
    var service=new SocialInfluenceServiceImpl(plugin,null,mock(RelationshipNetwork.class),database);
    if(phase.equals("old-write")){
      service.addInfluence(PLAYER,200,"legacy");require(service.getInfluence(PLAYER)==200,"Legacy balance written");
    }else if(phase.equals("upgrade")){
      require(service.getInfluence(PLAYER)==200,"Legacy balance recovered");
      service.addInfluence(PLAYER,100,"new-source");
      service.startDecayTask();tick.get().accept(null);service.stopDecayTask();tick.get().accept(null);
      require(service.getInfluence(PLAYER)==297,"One durable decay, retired callback ignored");service.shutdown();
    }else if(phase.equals("reopen")||phase.equals("reopen-stale")){
      require(service.getInfluence(PLAYER)==(phase.equals("reopen")?297:400),"Independent reopen retained balance");service.shutdown();
    }else if(phase.equals("stale")||phase.equals("missing")){
      try(var connection=source.getConnection();var statement=connection.createStatement()){
        statement.execute(phase.equals("stale")?"UPDATE player_influence SET influence=400":"DELETE FROM player_influence");
      }
      service.startDecayTask();tick.get().accept(null);
      boolean rejected=false;try{service.getInfluence(PLAYER);}catch(IllegalStateException expected){rejected=true;}
      require(rejected,"Changed/missing row quarantined");service.shutdown();
      try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT influence FROM player_influence")){
        if(phase.equals("stale")){require(rows.next()&&rows.getInt(1)==400,"External value not overwritten");}
        else require(!rows.next(),"Missing row not recreated by decay");
      }
    }else throw new IllegalArgumentException(phase);
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
