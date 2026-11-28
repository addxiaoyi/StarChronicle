import static org.mockito.Mockito.*;
import dev.starcore.starcore.social.chat.PrivateMessageService;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** Runs actual old/new product JARs without a server implementation, worlds or clients. */
public final class PrivateMessageRecoveryProbe {
  static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3);
  static void require(boolean result,String message){if(!result)throw new AssertionError(message);}
  static void rejected(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"Expected explicit rejection");}
  static String snapshot(PrivateMessageService service){
    StringBuilder text=new StringBuilder();
    for(UUID id:List.of(A,B,C))text.append(id).append(':').append(service.getMessageHistory(id,0)).append(':').append(service.getLastChatPartner(id)).append(':').append(new TreeSet<>(service.getMutedPlayers(id))).append('\n');
    return text.toString();
  }
  public static void main(String[] args)throws Exception {
    String phase=args[0];Path root=Path.of(args[1]);Files.createDirectories(root);
    Plugin plugin=mock(Plugin.class);when(plugin.getDataFolder()).thenReturn(root.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("private-message-offline"));
    var service=new PrivateMessageService();
    if(phase.equals("reject")) {
      rejected(()->service.initialize(plugin));require(!service.isReady(),"Rejected state not available");
      rejected(service::saveData);rejected(()->service.sendMessage(A,B,"must-not-record"));service.shutdown();
    } else {
      service.initialize(plugin);
      switch(phase) {
        case "old-write" -> {
          service.sendMessage(A,B,"old0");service.sendMessage(B,A,"old1");service.sendMessage(A,C,"old2");service.sendMessage(A,B,"old3");service.mutePlayer(A,C);service.shutdown();
        }
        case "upgrade" -> {
          require(service.getConversation(A,B,0).stream().map(PrivateMessageService.ChatMessage::message).toList().equals(List.of("old3","old1","old0")),"Legacy insertion order retained");
          require(service.getConversation(A,B,2).size()==2,"Limit applied after filtering");require(service.isMuted(A,C),"Legacy mute restored");
          service.sendMessage(A,B,"new4");service.saveData();Files.writeString(root.resolve("expected.txt"),snapshot(service));
          System.out.println("{\"phase\":\"upgrade\",\"verified\":true,\"server_started\":false}");System.out.flush();Runtime.getRuntime().halt(0);
        }
        case "reopen" -> {
          require(snapshot(service).equals(Files.readString(root.resolve("expected.txt"))),"All identities/messages/partners/mutes survive separate process reopen");service.shutdown();
        }
        case "clear" -> {
          require(service.clearConversation(A,B),"Conversation removed");require(service.clearHistory(B),"History removed");service.saveData();Files.writeString(root.resolve("expected.txt"),snapshot(service));service.shutdown();
        }
        case "new-write" -> {service.sendMessage(B,A,"concurrent");service.saveData();Files.writeString(root.resolve("expected.txt"),snapshot(service));service.shutdown();}
        case "stale-writer" -> {
          Files.writeString(root.resolve("stale-ready.txt"),"ready");
          long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
          while(!Files.exists(root.resolve("stale-release.txt"))&&System.nanoTime()<end)Thread.sleep(25);
          require(Files.exists(root.resolve("stale-release.txt")),"Coordinator released stale instance");
          rejected(service::saveData);require(!service.isReady(),"Stale process blocked");rejected(service::shutdown);
        }
        default -> throw new IllegalArgumentException(phase);
      }
    }
    System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
  }
}
