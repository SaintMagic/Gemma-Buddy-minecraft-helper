package com.saintmagic.gemmabuddy;

import java.util.List;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;

/**
 * Storage-facing contract for GemmaBuddy's local knowledge dataverse.
 *
 * The current implementation is in-memory and backed by registries plus local
 * JSON reports. A future SQLite backend can implement the same interface.
 */
public interface KnowledgeRepository {
    Optional<KnowledgeDataverse.KnowledgeEntry> resolveEntry(MinecraftServer server, String query);

    Optional<KnowledgeDataverse.RecipeRecord> findRecipeForOutput(MinecraftServer server, String queryOrRegistryId);

    List<KnowledgeDataverse.RecipeRecord> findUsagesForInput(MinecraftServer server, String queryOrRegistryId);

    List<String> findTagsForEntry(MinecraftServer server, String queryOrRegistryId);

    List<KnowledgeDataverse.KnowledgeEntry> findEntriesByMod(MinecraftServer server, String modId);

    String getEntrySummary(MinecraftServer server, String queryOrRegistryId);

    Optional<KnowledgeDataverse.DeterministicAnswer> answerQuestion(MinecraftServer server, String query);

    String docsRootPath();

    String statusLine();
}
