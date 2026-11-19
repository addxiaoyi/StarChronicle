package io.github.addxiaoyi.starchronicle.config;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unknown authority cannot be turned into permission by negation or a composite expression. */
class ConditionLogicTest {
    Condition leaf(String fact,String op,String value){return new Condition(null,null,null,fact,op,value);}
    Condition not(Condition condition){return new Condition(null,null,condition,null,null,null);}
    Condition all(Condition...conditions){return new Condition(List.of(conditions),null,null,null,null,null);}
    Condition any(Condition...conditions){return new Condition(null,List.of(conditions),null,null,null,null);}
    Condition unknown(){return leaf("nation.war","eq","true");}
    Condition yes(){return leaf("player.role","eq","scholar");}
    Condition no(){return leaf("player.role","eq","soldier");}
    final Map<String,String> facts=Map.of("player.role","scholar");

    @Test void missingAuthorityAndItsNegationBothFailClosed() {
        assertFalse(unknown().test(facts));assertFalse(not(unknown()).test(facts));
        assertFalse(not(leaf("nation.war","ne","true")).test(facts));
    }
    @Test void doubleNegationDoesNotTurnUnknownIntoKnownState() {
        assertFalse(not(not(unknown())).test(facts));assertFalse(not(not(not(unknown()))).test(facts));
    }
    @Test void allTrueAndUnknownRemainsUnknownIncludingUnderNegation() {
        assertFalse(all(yes(),unknown()).test(facts));assertFalse(not(all(yes(),unknown())).test(facts));
        assertFalse(all(unknown(),yes()).test(facts));assertFalse(not(all(unknown(),yes())).test(facts));
    }
    @Test void allKnownFalseAndUnknownIsConclusiveFalseInEitherOrder() {
        assertFalse(all(no(),unknown()).test(facts));assertTrue(not(all(no(),unknown())).test(facts));
        assertFalse(all(unknown(),no()).test(facts));assertTrue(not(all(unknown(),no())).test(facts));
    }
    @Test void anyKnownFalseAndUnknownRemainsUnknownIncludingUnderNegation() {
        assertFalse(any(no(),unknown()).test(facts));assertFalse(not(any(no(),unknown())).test(facts));
        assertFalse(any(unknown(),no()).test(facts));assertFalse(not(any(unknown(),no())).test(facts));
    }
    @Test void anyKnownTrueAndUnknownIsConclusiveTrueInEitherOrder() {
        assertTrue(any(yes(),unknown()).test(facts));assertFalse(not(any(yes(),unknown())).test(facts));
        assertTrue(any(unknown(),yes()).test(facts));assertFalse(not(any(unknown(),yes())).test(facts));
    }
    @Test void explicitExistsAndNotExistsRemainTwoValuedForAbsentFacts() {
        Condition exists=leaf("nation.war","exists",null);assertFalse(exists.test(facts));assertTrue(not(exists).test(facts));assertFalse(not(not(exists)).test(facts));
    }
    @Test void explicitExistsCountsPresentEmptyAndNumericZeroAsPresent() {
        assertTrue(leaf("empty","exists",null).test(Map.of("empty","")));assertTrue(leaf("zero","exists",null).test(Map.of("zero","0")));
        assertFalse(not(leaf("player.role","exists",null)).test(facts));
    }
    @Test void malformedActualNumberAndItsNegationFailClosedForEveryComparison() {
        for(String op:List.of("gte","lte","gt","lt")) {
            Condition condition=leaf("nation.treasury",op,"1000");Map<String,String> bad=Map.of("nation.treasury","temporarily-unavailable");
            assertFalse(condition.test(bad));assertFalse(not(condition).test(bad));
        }
    }
    @Test void missingNumberAndItsNegationFailClosed() {
        for(String op:List.of("gte","lte","gt","lt")){Condition condition=leaf("nation.treasury",op,"1000");assertFalse(condition.test(facts));assertFalse(not(condition).test(facts));}
    }
    @Test void malformedComparisonConstantCannotBecomeTrueThroughNot() {
        Condition condition=leaf("nation.treasury","gte","invalid-number");Map<String,String> known=Map.of("nation.treasury","1000");assertFalse(condition.test(known));assertFalse(not(condition).test(known));
    }
    @Test void ordinaryKnownBooleanAndNumericNegationPreserveTheirPreviousResults() {
        assertFalse(not(yes()).test(facts));assertTrue(not(no()).test(facts));assertTrue(not(not(yes())).test(facts));
        assertTrue(not(leaf("amount","gt","10")).test(Map.of("amount","3")));assertFalse(not(leaf("amount","gte","3")).test(Map.of("amount","3")));
    }
    @Test void nestedMixedExpressionKeepsUnknownUntilASeparateKnownBranchDecidesIt() {
        Condition ambiguous=not(any(no(),all(yes(),unknown())));assertFalse(ambiguous.test(facts));
        assertTrue(any(ambiguous,yes()).test(facts));assertFalse(not(any(ambiguous,no())).test(facts));
    }
}
