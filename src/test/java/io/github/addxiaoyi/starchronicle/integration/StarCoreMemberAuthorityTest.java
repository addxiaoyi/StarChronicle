package io.github.addxiaoyi.starchronicle.integration;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreMemberAuthorityTest {
    static final UUID PLAYER=UUID.randomUUID();
    static final String NATION=UUID.randomUUID().toString();
    static class NativeState implements StarCoreNationalVoteAuthority.NativeAccess {
        final Object lock=new Object();final CountDownLatch requested=new CountDownLatch(1);
        String nation=NATION;boolean exists=true;
        public Object monitor(){requested.countDown();return lock;}
        public String nationOf(UUID player){assertTrue(Thread.holdsLock(lock));return nation;}
        public boolean exists(String id){assertTrue(Thread.holdsLock(lock));return exists;}
        public boolean currentHolder(UUID player,String id){fail("Ordinary member review must not require leadership");return false;}
    }
    @Test void ordinaryMemberAndStatelessPlayerAreCheckedInsideNativeMonitor()throws Exception{
        var nativeState=new NativeState();var authority=new StarCoreMemberAuthority(()->nativeState,Clock.systemUTC());
        assertEquals("ok",authority.withMember(PLAYER,NATION,m->{assertTrue(Thread.holdsLock(nativeState.lock));assertEquals(NATION,m.nationId());return "ok";}));
        nativeState.nation="";assertEquals("",authority.withMember(PLAYER,"",m->m.nationId()));
        nativeState.nation=NATION;nativeState.exists=false;
        assertThrows(SecurityException.class,()->authority.withMember(PLAYER,NATION,m->fail("Dissolved nation cannot approve")));
    }
    @Test void membershipChangesWhileWaitingForLockAreRecheckedBeforeCommit()throws Exception{
        var state=new NativeState();var authority=new StarCoreMemberAuthority(()->state,Clock.systemUTC());
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<?> work;
            synchronized(state.lock){
                work=executor.submit(()->authority.withMember(PLAYER,NATION,m->fail("Changed nation must not commit")));
                assertTrue(state.requested.await(3,TimeUnit.SECONDS));state.nation="";
            }
            var error=assertThrows(ExecutionException.class,()->work.get(3,TimeUnit.SECONDS));assertInstanceOf(SecurityException.class,error.getCause());
        }
    }
    @Test void absentNativeServiceNeverApproves(){
        var authority=new StarCoreMemberAuthority(()->null,Clock.systemUTC());
        assertThrows(SecurityException.class,()->authority.withMember(PLAYER,NATION,m->fail("Unavailable service")));
    }
}
