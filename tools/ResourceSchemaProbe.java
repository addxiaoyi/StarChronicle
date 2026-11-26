import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;

/** Run with a chosen actual product JAR to test its schema admission, without server startup. */
public final class ResourceSchemaProbe {
    public static void main(String[] args) {
        boolean reject=args[0].equals("reject");
        try(var store=new SqliteStore(Path.of(args[1]))) {
            if(reject) throw new AssertionError("Old plugin accepted a newer receipt database");
            int version=store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){return r.getInt(1);}});
            if(version!=9) throw new AssertionError("Expected schema 9");
            System.out.println("UPGRADED schema="+version);
        } catch(IllegalStateException failure) {
            Throwable cause=failure;
            while(cause.getCause()!=null)cause=cause.getCause();
            if(!reject || !cause.getMessage().equals("Database schema newer than this plugin"))throw failure;
            System.out.println("REJECTED "+cause.getMessage());
        }
    }
}
