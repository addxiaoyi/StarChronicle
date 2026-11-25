package io.github.addxiaoyi.starchronicle.api.epic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Strict, bounded catalog of operator-owned epic campaign contracts. */
public record EpicCampaignPlanCatalog(List<EpicCampaignPlan> plans) {
    public EpicCampaignPlanCatalog {
        if (plans == null || plans.size() > 64) throw new IllegalArgumentException("Epic catalog requires 0..64 plans");
        plans = List.copyOf(plans);
        Set<String> ids = new HashSet<>();
        for (EpicCampaignPlan plan : plans)
            if (plan == null || !ids.add(plan.campaignId())) throw new IllegalArgumentException("Null or duplicate epic campaign ID");
    }

    public static EpicCampaignPlanCatalog empty() { return new EpicCampaignPlanCatalog(List.of()); }

    public EpicCampaignPlan plan(String campaignId) {
        return plans.stream().filter(plan -> plan.campaignId().equals(campaignId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown epic campaign plan: " + campaignId));
    }

    public static EpicCampaignPlanCatalog load(Path directory) throws IOException {
        Objects.requireNonNull(directory);
        if (!Files.exists(directory)) return empty();
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory))
            throw new IOException("Epic campaign catalog must be a local directory");
        List<EpicCampaignPlan> plans = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new IOException("Epic catalog entries must be regular files");
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".yml") && !name.endsWith(".yaml") && !name.endsWith(".json")) continue;
                if (Files.size(file) > 262_144) throw new IOException("Epic campaign plan exceeds 256 KiB: " + name);
                ObjectMapper mapper = name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
                mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
                plans.add(mapper.readValue(file.toFile(), EpicCampaignPlan.class));
                if (plans.size() > 64) throw new IOException("Epic catalog exceeds 64 plans");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid epic campaign catalog: " + invalid.getMessage(), invalid);
        }
        try { return new EpicCampaignPlanCatalog(plans); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid epic campaign catalog: " + invalid.getMessage(), invalid); }
    }
}
