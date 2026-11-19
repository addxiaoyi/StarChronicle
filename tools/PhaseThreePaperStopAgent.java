package smoke;

import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** One-purpose recovery after the Python console owner failed. It checks the actual server
 * world directory and frozen product before scheduling Bukkit's normal shutdown. */
public final class PhaseThreePaperStopAgent {
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("pid agent.jar expectedRun expectedSha marker");
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1],args[2]+"|"+args[3]+"|"+args[4]);}finally{vm.detach();}
    }
    public static void agentmain(String args,Instrumentation instrumentation)throws Exception{
        String[] parts=args.split("\\|",-1);if(parts.length!=3)throw new IllegalArgumentException("Invalid bounded recovery arguments");
        Path run=Path.of(parts[0]).toRealPath(),marker=Path.of(parts[2]).toAbsolutePath().normalize();
        if(!run.getFileName().toString().matches("phase-three-paper-[a-z0-9][a-z0-9-]{0,63}")||!marker.getParent().equals(run.resolve("evidence"))||Files.exists(marker))throw new SecurityException("Unsafe recovery target");
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(run.resolve("plugins/StarChronicle.jar"))));
        if(!actual.equals(parts[1]))throw new SecurityException("Product fingerprint drift");
        Class<?> bukkit=Arrays.stream(instrumentation.getAllLoadedClasses()).filter(type->type.getName().equals("org.bukkit.Bukkit")).findFirst().orElseThrow();
        Object server=bukkit.getMethod("getServer").invoke(null);
        Path world=((java.io.File)server.getClass().getMethod("getWorldContainer").invoke(server)).toPath().toRealPath();
        if(!world.equals(run))throw new SecurityException("Attached JVM belongs to a different actual world directory: "+world);
        Object manager=server.getClass().getMethod("getPluginManager").invoke(server);
        Object fixture=manager.getClass().getMethod("getPlugin",String.class).invoke(manager,"PhaseThreePaperFixture");
        if(fixture==null)throw new SecurityException("Actual bounded fixture is absent");
        Object scheduler=server.getClass().getMethod("getScheduler").invoke(server);
        Runnable shutdown=()->{
            try{
                String value="{\"status\":\"NORMAL_BUKKIT_SHUTDOWN_INVOKED\",\"pid\":"+ProcessHandle.current().pid()+",\"utc\":\""+Instant.now()+"\",\"product_sha256\":\""+parts[1]+"\"}\n";
                Files.writeString(marker,value,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                server.getClass().getMethod("shutdown").invoke(server);
            }catch(Exception failure){throw new IllegalStateException("Bounded normal shutdown failed",failure);}
        };
        Method task=Arrays.stream(scheduler.getClass().getMethods()).filter(method->method.getName().equals("runTask")&&method.getParameterCount()==2&&method.getParameterTypes()[0].isInstance(fixture)&&method.getParameterTypes()[1]==Runnable.class).findFirst().orElseThrow();
        task.invoke(scheduler,fixture,shutdown);
    }
}
