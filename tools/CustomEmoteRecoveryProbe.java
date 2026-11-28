import dev.starcore.starcore.social.emote.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

public final class CustomEmoteRecoveryProbe {
  static void require(boolean result,String message) { if(!result) throw new AssertionError(message); }
  static String digest(Path path) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
  public static void main(String[] args) throws Exception {
    String phase=args[0];Path root=Path.of(args[1]),path=root.resolve("custom-emotes.yml");
    var service=new EmoteService();var manager=new CustomEmoteManager(service);
    if(phase.equals("reject")) {
      String original=Files.exists(path)?digest(path):null;
      try {manager.initialize(path);throw new AssertionError("Invalid authority accepted");} catch(IllegalStateException expected) {}
      require(service.getEmotesByCategory("custom").isEmpty(),"No partial registry");
      require(original==null?!Files.exists(path):original.equals(digest(path)),"Original source retained");
    } else {
      manager.initialize(path);String id=Files.readString(root.resolve("id.txt"));
      var entry=manager.getCustomEmote(id).orElseThrow();require(entry.getCreatedAt()==0,"Unknown legacy creation remains unknown");
      require(entry.getCreatorId().equals(new UUID(0,1)),"Original owner retained");
      if(phase.equals("upgrade")) {
        require(entry.getName().equals("旧动作")&&entry.getDurationTicks()==50&&entry.getCooldownSeconds()==7,"Original fields retained");
        String oldHash=digest(path);
        require(manager.updateCustomEmote(id,new UUID(0,1),"已更新",null,80,9),"Durable update succeeds");
        Path backup=root.resolve("custom-emotes.yml.legacy-"+oldHash+".bak");require(digest(backup).equals(oldHash),"Exact original backup");
        var created=manager.createCustomEmote(new UUID(0,2),"新动作","新说明","particle","HEART",60,3,false);
        Files.writeString(root.resolve("new-id.txt"),created.getId());Files.writeString(root.resolve("new-created.txt"),Long.toString(created.getCreatedAt()));
        var rows=(List<Map<String,Object>>)manager.serialize().get("customEmotes");
        var changed=new ArrayList<Map<String,Object>>();
        for(var row:rows) {var copy=new HashMap<>(row);copy.put("metadata",Map.of("nested",List.of("value",7)));changed.add(copy);}
        manager.deserialize(Map.of("schema",1,"customEmotes",changed));
      } else if(phase.equals("reopen")) {
        require(manager.getCustomEmoteCount()==2,"Independent process restores complete state");
        require(entry.getName().equals("已更新")&&entry.getDurationTicks()==80&&entry.getCooldownSeconds()==9,"Updated fields retained");
        require(entry.getMetadata().equals(Map.of("nested",List.of("value",7L))),"Nested metadata retained");
        var added=manager.getCustomEmote(Files.readString(root.resolve("new-id.txt"))).orElseThrow();
        require(added.getCreatedAt()==Long.parseLong(Files.readString(root.resolve("new-created.txt")))&&!added.isPublic(),"New creation and private visibility retained");
      } else throw new IllegalArgumentException(phase);
      manager.close();
    }
    System.out.println("{\"verified\":true,\"phase\":\""+phase+"\",\"server_started\":false}");
  }
}
