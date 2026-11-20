package io.github.addxiaoyi.starchronicle.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationRegionTest {
    @Test void disabledOrEmptyConfigurationCreatesNoAutomaticWorldScope() {
        assertTrue(ExplorationRegion.load(new YamlConfiguration()).isEmpty());
        var config=new YamlConfiguration();config.set("exploration.enabled",true);
        assertTrue(ExplorationRegion.load(config).isEmpty());
    }

    @Test void configuredCuboidUsesWorldIdentityAndInclusiveBlockBounds() {
        var config=new YamlConfiguration();config.set("exploration.enabled",true);config.set("exploration.regions",List.of(java.util.Map.of("id","old-road","world","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","min-x",-2,"min-y",60,"min-z",3,"max-x",4,"max-y",80,"max-z",9)));
        var region=ExplorationRegion.load(config).getFirst();
        assertTrue(region.contains(region.worldId(),-2,60,3));assertTrue(region.contains(region.worldId(),4,80,9));
        assertFalse(region.contains(region.worldId(),5,80,9));assertFalse(region.contains(java.util.UUID.randomUUID(),0,70,5));
    }

    @Test void invalidOrAmbiguousDefinitionsFailStartupValidation() {
        var missing=new YamlConfiguration();missing.set("exploration.enabled",true);missing.set("exploration.regions",List.of(java.util.Map.of("id","road","world","bad","min-x",0,"min-y",0,"min-z",0,"max-x",1,"max-y",1,"max-z",1)));
        assertThrows(IllegalArgumentException.class,()->ExplorationRegion.load(missing));
        var duplicate=new YamlConfiguration();duplicate.set("exploration.enabled",true);var item=java.util.Map.of("id","road","world","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","min-x",0,"min-y",0,"min-z",0,"max-x",1,"max-y",1,"max-z",1);duplicate.set("exploration.regions",List.of(item,item));
        assertThrows(IllegalArgumentException.class,()->ExplorationRegion.load(duplicate));
        duplicate.set("exploration.regions",List.of(item,"typo"));assertThrows(IllegalArgumentException.class,()->ExplorationRegion.load(duplicate));
        duplicate.set("exploration.regions","road");assertThrows(IllegalArgumentException.class,()->ExplorationRegion.load(duplicate));
    }
}
