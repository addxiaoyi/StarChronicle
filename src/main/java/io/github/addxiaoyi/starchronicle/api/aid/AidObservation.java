package io.github.addxiaoyi.starchronicle.api.aid;

/** ABSENT permits first admission only. After admission it never authorizes a resend. */
public record AidObservation(Status status,AidReceipt receipt,String detail) {
    public enum Status {ABSENT,COMMITTED,REJECTED,UNKNOWN}
    public AidObservation {
        java.util.Objects.requireNonNull(status);
        if((status==Status.COMMITTED)!=(receipt!=null))throw new IllegalArgumentException("Committed aid needs exactly one native receipt");
        detail=detail==null?"":detail;if(detail.length()>2048)detail=detail.substring(0,2048);
    }
    public static AidObservation unknown(String detail){return new AidObservation(Status.UNKNOWN,null,detail);}
}
