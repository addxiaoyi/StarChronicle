import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import java.nio.file.Path;

/** Run with the actual chosen JAR, including the chosen older JAR for rejection. */
public final class ProcessingSchemaProbe {
    public static void main(String[] args) {
        boolean reject=args[0].equals("reject");
        try(var store=new SqliteStore(Path.of(args[1]))) {
            if(reject) throw new AssertionError("Old plugin accepted processing receipt schema");
            new CommittedHistoryJournal(store);
            int version=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}});
            int expected=args.length>2?Integer.parseInt(args[2]):11;
            if(version!=expected)throw new AssertionError("Expected schema"+expected+", got "+version);
            System.out.println("UPGRADED schema="+version);
        }catch(IllegalStateException error){
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            if(!reject||!cause.getMessage().equals("Database schema newer than this plugin"))throw error;
            System.out.println("REJECTED "+cause.getMessage());
        }
    }
}
