package io.github.addxiaoyi.starchronicle.config;
import java.util.*;
import java.math.BigDecimal;

/** Bounded data expression; no scripts, reflection, Java expression evaluation or command language. */
public record Condition(List<Condition> all, List<Condition> any, Condition not, String fact, String op, String value) {
    public Condition { all=all==null?null:List.copyOf(all);any=any==null?null:List.copyOf(any); }
    public boolean test(Map<String,String> facts) {
        return evaluate(Objects.requireNonNull(facts))==Truth.TRUE;
    }
    /** Kleene three-valued logic: absent/malformed authority is UNKNOWN, including under NOT.
     * Explicit exists remains a two-valued query so authors may intentionally test absence. */
    private Truth evaluate(Map<String,String> facts) {
        if(all!=null) {
            boolean unknown=false;
            for(Condition child:all){Truth result=child.evaluate(facts);if(result==Truth.FALSE)return Truth.FALSE;unknown|=result==Truth.UNKNOWN;}
            return unknown?Truth.UNKNOWN:Truth.TRUE;
        }
        if(any!=null) {
            boolean unknown=false;
            for(Condition child:any){Truth result=child.evaluate(facts);if(result==Truth.TRUE)return Truth.TRUE;unknown|=result==Truth.UNKNOWN;}
            return unknown?Truth.UNKNOWN:Truth.FALSE;
        }
        if(not!=null) return switch(not.evaluate(facts)){case TRUE->Truth.FALSE;case FALSE->Truth.TRUE;case UNKNOWN->Truth.UNKNOWN;};
        if(fact==null)return Truth.UNKNOWN;
        String actual=facts.get(fact);
        if("exists".equals(op)) return actual!=null?Truth.TRUE:Truth.FALSE;
        if(actual==null||value==null)return Truth.UNKNOWN;
        return switch(op) {
            case "eq" -> truth(actual.equals(value));
            case "ne" -> truth(!actual.equals(value));
            case "gte", "lte", "gt", "lt" -> compare(actual,value,op);
            default -> Truth.UNKNOWN;
        };
    }
    private static Truth compare(String a,String b,String op) {
        try { int c=new BigDecimal(a).compareTo(new BigDecimal(b)); return truth(switch(op){case "gte"->c>=0;case "lte"->c<=0;case "gt"->c>0;default->c<0;}); }
        catch(NumberFormatException|NullPointerException e){return Truth.UNKNOWN;}
    }
    private static Truth truth(boolean value){return value?Truth.TRUE:Truth.FALSE;}
    private enum Truth { TRUE, FALSE, UNKNOWN }
}
