package dev.starcore.starcore.social;

import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.social.friend.FriendService;
import dev.starcore.starcore.social.guild.*;
import dev.starcore.starcore.social.party.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteDataSource;
import static org.mockito.Mockito.*;

/** Exact product JAR imports interrupted by process halt; no server bootstrap. */
public final class SocialImportOfflineProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3),D=new UUID(0,4),E=new UUID(0,5),F=new UUID(0,6),G=new UUID(1,1),P=new UUID(2,1);
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static SocialSnapshot candidate(){
    var friends=new FriendService.State(Map.of(A,Set.of(B),B,Set.of(A)),Map.of(A,Set.of(C)),Map.of(C,Set.of(D)),Map.of(D,Set.of(A)),Map.of(A,true,B,false));
    var guild=new GuildState.Entry(G,"Alpha","A",A,Map.of(A,GuildRole.LEADER,B,GuildRole.OFFICER),3,42,12.5,1234,"original description");
    var party=new PartyState.Entry(P,C,Set.of(C,D),2345,true,false,1);
    return new SocialSnapshot(friends,new GuildState(Map.of(G,guild),Set.of(new GuildService.GuildInvite(G,A,C,3456))),new PartyState(Map.of(P,party),Set.of(new PartyState.Invite(P,C,E,4567),new PartyState.Invite(null,D,F,5678))));
  }
  static void verified(String phase){System.out.println("{\"verified\":true,\"phase\":\""+phase+"\",\"server_started\":false}");System.out.flush();}
  public static void main(String[] args)throws Exception{
    String phase=args[0],mode=args[2];Path folder=Path.of(args[1]);Files.createDirectories(folder);
    var plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    var friends=new FriendService();var guilds=new GuildService();var parties=new PartyService();
    if(!mode.equals("file")){
      var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+folder.resolve("social.db"));
      var database=mock(DatabaseService.class);when(database.dataSource()).thenReturn(Optional.of(source));when(database.isRunning()).thenReturn(true);
      var store=new SqlSocialStateStorage(database,Logger.getAnonymousLogger());store.ensureTables();friends.setSqlStorage(store);
      if(mode.equals("sql")){guilds.setSqlStorage(store);parties.setSqlStorage(store);}
    }else friends.setFileStorage(folder.resolve("social/friends.yml"));
    guilds.initialize(plugin);parties.initialize(plugin);friends.loadData();guilds.loadGuilds();parties.loadData();
    var service=new SocialImportExportService(plugin,friends,guilds,parties);
    if(phase.startsWith("halt-")){
      String boundary=phase.substring(5);var wanted=candidate();String input=new SocialExportFiles(folder).write("state",wanted.encode());
      service=new SocialImportExportService(plugin,friends,guilds,parties,stage->{
        if(!stage.equals(boundary))return;
        if(!stage.equals("prepared"))require(friends.snapshot().equals(wanted.friends()),"First participant durable prefix");
        if(Set.of("guilds","parties","complete").contains(stage))require(guilds.snapshot().equals(wanted.guilds()),"Second participant durable prefix");
        if(Set.of("parties","complete").contains(stage))require(parties.snapshot().equals(wanted.parties()),"All participants committed");
        verified(phase);Runtime.getRuntime().halt(0);
      });
      service.importAll(input);throw new AssertionError("Halt boundary not reached");
    }else if(phase.equals("recover")){
      service.recoverPendingImports();require(new SocialSnapshot(friends.snapshot(),guilds.snapshot(),parties.snapshot()).equals(candidate()),"Complete original state after new JVM recovery");
      String export=service.exportAll(null);require(export!=null,"Actual complete export");
      require(SocialSnapshot.decode(new SocialExportFiles(folder).read(export)).equals(candidate()),"All invitations, legacy direction, roles, times, capacity and status exported");
    }else if(phase.equals("reject")){
      boolean refused=false;try{service.recoverPendingImports();}catch(IllegalStateException expected){refused=true;}
      require(refused&&!friends.isReady()&&!guilds.isReady()&&!parties.isReady(),"Rejected import isolates all participants");
    }else if(phase.equals("external-party")){
      parties.createParty(F);
    }else if(phase.equals("legacy-import")||phase.equals("legacy-reopen")){
      if(phase.equals("legacy-import"))require(service.importAll("legacy.json"),"Actual legacy exporter input accepted");
      else service.recoverPendingImports();
      require(friends.isFriend(A,B)&&friends.isFriend(B,A)&&friends.getOnlineStatus(A),"Legacy friendship/status retained");
      require(friends.snapshot().incoming().isEmpty()&&friends.snapshot().legacy().isEmpty()&&friends.snapshot().blocked().isEmpty(),"Missing old export evidence never invented");
      var guild=guilds.snapshot().guilds().get(G);require(guild.createdTime()==1234&&guild.balance()==12.5&&guild.members().get(B)==GuildRole.OFFICER,"Legacy guild metadata retained");
      var party=parties.snapshot().parties().get(P);require(party.createdTime()==2345&&party.members().equals(Set.of(C,D))&&party.maxMembers()==1,"Legacy party identity and over-capacity retained");
      require(guilds.snapshot().invites().isEmpty()&&parties.snapshot().invites().isEmpty(),"No invented old invitation");
    }else throw new IllegalArgumentException(phase);
    verified(phase);Runtime.getRuntime().halt(0);
  }
}
