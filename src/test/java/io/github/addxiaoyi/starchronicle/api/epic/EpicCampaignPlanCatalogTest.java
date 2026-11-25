package io.github.addxiaoyi.starchronicle.api.epic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EpicCampaignPlanCatalogTest {
    @Test void loadsTheBundledHeirStrategyContractWithStrictRouteAndReceiptRequirements() throws Exception {
        var catalog=EpicCampaignPlanCatalog.load(Path.of("examples/national-epic/last-heir-strategy/epic-campaigns"));
        var plan=catalog.plan("heir-strategy");
        assertEquals("last-heir-strategy",plan.storyId());
        assertEquals("investigation/records-evidence",plan.phases().getFirst().requirements().iterator().next().nativeReference());
        assertEquals("ANY",plan.phases().get(2).requirements().stream()
                .filter(requirement->requirement.type()==EpicRequirement.Type.DIPLOMACY_ACCEPT)
                .findFirst().orElseThrow().target());
        assertTrue(plan.requirementsForRoute("WAR").stream()
                .anyMatch(requirement->requirement.nativeReference().equals("WAR_OUTCOME_V1")));
    }
}
