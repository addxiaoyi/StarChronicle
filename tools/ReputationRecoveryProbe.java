import static org.mockito.Mockito.*;
import dev.starcore.starcore.core.database.*;
import dev.starcore.starcore.social.simulation.ReputationServiceImpl;
import dev.starcore.starcore.social.simulation.ReputationService.*;
import io.papermc.paper.threadedregions.scheduler.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.sqlite.SQLiteDataSource;

/** Runs the actual frozen old/new reputation classes without starting a server. */
public class ReputationRecoveryProbe {
  static final UUID PLAYER=UUID.fromString("22222222-3333-4444-5555-666666666666");
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static Object query(SQLiteDataSource source,String sql,Function<ResultSet,?> mapper,Object[] values)throws Exception{
    try(var connection=source.getConnection();var statement=connection.prepareStatement(sql)){
      for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
      try(var rows=statement.executeQuery()){return mapper.apply(rows);}
    }
  }
  public static void main(String[] args)throws Exception{
    String phase=args[0];Path folder=Path.of(args[1]);Files.createDirectories(folder);
    SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+folder.resolve("reputation.db"));
    DatabaseService database=mock(DatabaseService.class);
    when(database.settings()).thenReturn(DatabaseSettings.disabled());when(database.isRunning()).thenReturn(true);
    when(database.dataSource()).thenReturn(Optional.of(source));
    when(database.execute(anyString())).thenAnswer(inv->{
      try(var connection=source.getConnection();var statement=connection.createStatement()){return statement.execute(inv.getArgument(0));}
    });
    when(database.query(anyString(),any())).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),new Object[0]));
    when(database.query(anyString(),any(),any(Object[].class))).thenAnswer(inv->query(source,inv.getArgument(0),inv.getArgument(1),(Object[])inv.getRawArguments()[2]));
    when(database.update(anyString(),any(Object[].class))).thenAnswer(inv->{
      try(var connection=source.getConnection();var statement=connection.prepareStatement(inv.getArgument(0))){
        Object[] values=(Object[])inv.getRawArguments()[1];for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement.executeUpdate();
      }
    });
    JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("offline-reputation"));
    when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);
    BukkitScheduler oldScheduler=mock(BukkitScheduler.class);when(server.getScheduler()).thenReturn(oldScheduler);
    when(oldScheduler.runTask(eq(plugin),any(Runnable.class))).thenReturn(mock(BukkitTask.class));
    GlobalRegionScheduler scheduler=mock(GlobalRegionScheduler.class);when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
    when(scheduler.run(eq(plugin),any())).thenReturn(mock(ScheduledTask.class));
    if(phase.equals("reject-load")){
      boolean rejected=false;
      try{var invalid=new ReputationServiceImpl(plugin,database);invalid.shutdown();}catch(IllegalStateException expected){rejected=true;}
      require(rejected,"Invalid authority must reject recovery");
    }else{
      var service=new ReputationServiceImpl(plugin,database);
      try{
        switch(phase){
          case "old-write" -> {
            service.modifyReputation(PLAYER,10,ReputationReason.HELP_PLAYER).join();
            service.modifyReputation(PLAYER,20,ReputationReason.COMPLETE_QUEST).join();
            require(service.getReputation(PLAYER)==30,"Legacy profile written");
          }
          case "upgrade" -> {
            require(service.getReputation(PLAYER)==30,"Old dimensions recovered despite stale overall column");
            require(service.getHistoryEntries(PLAYER,10).size()==2,"Old history complete");
            service.modifyReputation(PLAYER,Integer.MAX_VALUE,ReputationReason.HELP_PLAYER).join();
            require(service.getLastChange(PLAYER).amount()==990,"History records applied clamp delta");
            service.addWealth(PLAYER,7,"offline-award").join();
            require(service.getReputation(PLAYER)==1027,"Four dimensions retained");
            require(service.getLevel(PLAYER)==ReputationLevel.LEGEND,"Correct level selected");
          }
          case "reopen", "reopen-stale" -> {
            require(service.getReputation(PLAYER)==(phase.equals("reopen")?1027:104),"Independent reopen retained authoritative dimensions");
            require(service.getHistoryEntries(PLAYER,10).size()==4,"History unchanged by reads or rejected mutation");
            require(service.getLastChange(PLAYER).description().equals("offline-award"),"Custom award description persisted");
          }
          case "stale", "missing", "history-fail" -> {
            try(var connection=source.getConnection();var statement=connection.createStatement()){
              statement.execute(switch(phase){
                case "stale" -> "UPDATE player_reputation SET moral=77";
                case "missing" -> "DELETE FROM player_reputation";
                default -> "CREATE TRIGGER reject_history BEFORE INSERT ON reputation_history BEGIN SELECT RAISE(ABORT,'injected'); END";
              });
            }
            boolean rejected=false;
            try{service.modifyReputation(PLAYER,1,ReputationReason.HELP_PLAYER).join();}catch(java.util.concurrent.CompletionException expected){rejected=true;}
            require(rejected,"Rejected mutation must report failure");
            boolean isolated=false;try{service.getProfile(PLAYER);}catch(IllegalStateException expected){isolated=true;}
            require(isolated,"Failed authority is quarantined");
          }
          default -> throw new IllegalArgumentException(phase);
        }
      }finally{service.shutdown();}
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
