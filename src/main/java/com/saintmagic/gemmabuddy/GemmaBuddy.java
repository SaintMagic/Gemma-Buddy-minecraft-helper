package com.saintmagic.gemmabuddy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;

import com.saintmagic.gemmabuddy.ActionRegistry;
import com.saintmagic.gemmabuddy.CommandRouter;
import com.saintmagic.gemmabuddy.GoalManager;
import com.saintmagic.gemmabuddy.KnowledgeIndex;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * GemmaBuddy server-side core and coordinator.
 */
@Mod(GemmaBuddy.MOD_ID)
public final class GemmaBuddy {
    public static final String MOD_ID = "gemmabuddy";
    public static final String CHAT_PREFIX = "[GemmaBuddy] ";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String COMPANION_TAG = "gemmabuddy_companion";
    private static final String COMPANION_NAME = "GemmaBuddy";
    private static final int COMPANION_SEARCH_RADIUS = 128;

    private static final GemmaBuddyConfig CONFIG = new GemmaBuddyConfig();
    private static final MemoryManager MEMORY = createMemoryManager();
    private static final PermissionManager PERMISSIONS = createPermissionManager();
    private static final SafetyManager SAFETY = new SafetyManager(PERMISSIONS);
    private static final ActionRegistry ACTION_REGISTRY = new ActionRegistry();
    private static final GoalManager GOAL_MANAGER = new GoalManager(MEMORY);
    private static final KnowledgeIndex KNOWLEDGE_INDEX = new KnowledgeIndex();
    private static final KnowledgeRepository KNOWLEDGE_REPOSITORY = new KnowledgeDataverse(KNOWLEDGE_INDEX);
    private static final FindService FIND_SERVICE = new FindService(KNOWLEDGE_REPOSITORY, MEMORY);
    private static final PlannerService PLANNER_SERVICE = new PlannerService();
    private static final SkillRegistry SKILL_REGISTRY = new SkillRegistry();
    private static final ProgressionBrain PROGRESSION_BRAIN = new ProgressionBrain(KNOWLEDGE_REPOSITORY, MEMORY);
    private static final LmStudioClient LLM = new LmStudioClient(CONFIG);
    private static final WorkOrderService WORK_ORDERS = new WorkOrderService(CONFIG, SAFETY, FIND_SERVICE,
            KNOWLEDGE_REPOSITORY, PROGRESSION_BRAIN, MEMORY);
    private static final RegressionTestService TESTS = new RegressionTestService();
    private static final CommandRouter COMMAND_ROUTER = new CommandRouter(ACTION_REGISTRY, GOAL_MANAGER,
            KNOWLEDGE_INDEX, KNOWLEDGE_REPOSITORY, MEMORY, SAFETY, FIND_SERVICE, PLANNER_SERVICE, SKILL_REGISTRY,
            PROGRESSION_BRAIN, WORK_ORDERS, TESTS, LLM);

    public GemmaBuddy(IEventBus modEventBus) {
        CONFIG.load();
        GemmaBuddyItems.ITEMS.register(modEventBus);
        GemmaBuddyEntities.ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::onEntityAttributeCreation);
        modEventBus.addListener(this::onCreativeTabContents);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("GemmaBuddy loaded. LM Studio endpoint: {}", CONFIG.lmStudioEndpoint());
    }

    public static ActionRegistry actionRegistry() {
        return ACTION_REGISTRY;
    }

    public static GoalManager goalManager() {
        return GOAL_MANAGER;
    }

    public static GemmaBuddyConfig config() {
        return CONFIG;
    }

    public static void reloadConfig() {
        CONFIG.load();
        MEMORY.load();
        PERMISSIONS.load();
    }

    public static String llmEndpoint() {
        return CONFIG.lmStudioEndpoint();
    }

    public static String llmModel() {
        return CONFIG.modelName();
    }

    public static KnowledgeIndex knowledgeIndex() {
        return KNOWLEDGE_INDEX;
    }

    public static KnowledgeRepository knowledgeRepository() {
        return KNOWLEDGE_REPOSITORY;
    }

    public static String llmStatusLine() {
        return "LM Studio: " + CONFIG.lmStudioEndpoint() + " | model: " + CONFIG.modelName()
                + " | thinking: " + CONFIG.thinkingMode().configValue();
    }

    public static String llmConnectionStatus() {
        return LLM.connectionStatusLine();
    }

    public static CommandRouter commandRouter() {
        return COMMAND_ROUTER;
    }

    public static MemoryManager memoryManager() {
        return MEMORY;
    }

    public static SafetyManager safetyManager() {
        return SAFETY;
    }

    public static SkillRegistry skillRegistry() {
        return SKILL_REGISTRY;
    }

    public static ProgressionBrain progressionBrain() {
        return PROGRESSION_BRAIN;
    }

    public static WorkOrderService workOrderService() {
        return WORK_ORDERS;
    }

    public static RegressionTestService regressionTests() {
        return TESTS;
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        COMMAND_ROUTER.registerSlashCommands(event.getDispatcher());
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(GemmaBuddyEntities.GEMMA_BUDDY.get(), GemmaBuddyEntity.createAttributes().build());
    }

    private void onCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(GemmaBuddyItems.CONSOLE.get());
        }
    }

    private void onServerChat(ServerChatEvent event) {
        String raw = normalize(event.getRawText());
        if (!startsWithGemma(raw)) {
            return;
        }

        event.setCanceled(true);
        ServerPlayer player = event.getPlayer();
        try {
            COMMAND_ROUTER.routeChat(player, stripGemmaPrefix(raw));
        } catch (Exception ex) {
            LOGGER.error("GemmaBuddy chat handler failed for input '{}'", stripGemmaPrefix(raw), ex);
            sendError(player, "GemmaBuddy hit an error: " + friendlyError(ex) + ". Check the log for details.");
        }
    }

    private void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        player.getServer().execute(() -> rememberOpenedContainer(player, event.getContainer()));
    }

    private void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) {
            WORK_ORDERS.tick(event.getServer());
        }
    }

    private void rememberOpenedContainer(ServerPlayer player,
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        ContextResolver.ResolvedContext lookedAt = ContextResolver.resolveLookedAt(player);
        if (lookedAt == null || !"block".equals(lookedAt.type())) {
            return;
        }
        int containerSlots = Math.max(0, menu.slots.size() - 36);
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (int index = 0; index < containerSlots; index++) {
            net.minecraft.world.item.ItemStack stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                contents.merge(id.toString(), stack.getCount(), Integer::sum);
            }
        }
        MEMORY.rememberContainer(player.level().dimension().location().toString(), lookedAt.position(),
                lookedAt.registryId(), contents);
        LOGGER.info("GemmaBuddy remembered opened container type={} position={} itemTypes={}",
                lookedAt.registryId(), lookedAt.position().toShortString(), contents.size());
    }

    public static void sendLine(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(CHAT_PREFIX + text));
    }

    public static void sendError(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(CHAT_PREFIX + text));
    }

    public static void spawnBuddy(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<GemmaBuddyEntity> buddies = findBuddyEntities(level, player);
        if (!buddies.isEmpty()) {
            GemmaBuddyEntity existing = buddies.get(0);
            sendLine(player, "GemmaBuddy is already spawned at " + formatPosition(existing.blockPosition()) + ".");
            return;
        }

        Entity entity = GemmaBuddyEntities.GEMMA_BUDDY.get().create(level);
        if (!(entity instanceof GemmaBuddyEntity buddy)) {
            sendError(player, "GemmaBuddy could not spawn right now.");
            return;
        }

        buddy.moveTo(player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D, player.getYRot(), 0.0F);
        buddy.setCustomName(Component.literal(COMPANION_NAME));
        buddy.setCustomNameVisible(true);
        buddy.setPersistenceRequired();
        buddy.addTag(COMPANION_TAG);
        buddy.setOwnerUuid(player.getUUID());
        buddy.setBuddyMode(GemmaBuddyEntity.BuddyMode.IDLE);

        level.addFreshEntity(buddy);
        sendLine(player, "GemmaBuddy spawned at " + formatPosition(buddy.blockPosition()) + ".");
    }

    public static void despawnBuddy(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<GemmaBuddyEntity> buddies = findBuddyEntities(level, player);
        if (buddies.isEmpty()) {
            sendLine(player, "GemmaBuddy is not spawned right now.");
            return;
        }

        for (GemmaBuddyEntity buddy : buddies) {
            buddy.discard();
        }

        sendLine(player, "GemmaBuddy despawned.");
    }

    public static void reportBuddyLocation(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<GemmaBuddyEntity> buddies = findBuddyEntities(level, player);
        if (buddies.isEmpty()) {
            sendLine(player, "GemmaBuddy is not spawned right now. Use /gemmabuddy spawn.");
            return;
        }

        GemmaBuddyEntity buddy = buddies.get(0);
        sendLine(player, "GemmaBuddy is at " + formatPosition(buddy.blockPosition()) + " in "
                + level.dimension().location() + ", mode=" + buddy.buddyMode().name().toLowerCase(Locale.ROOT) + ".");
    }

    public static List<GemmaBuddyEntity> findBuddyEntities(ServerLevel level, ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(COMPANION_SEARCH_RADIUS);
        List<GemmaBuddyEntity> result = new java.util.ArrayList<>();
        for (GemmaBuddyEntity buddy : level.getEntitiesOfClass(GemmaBuddyEntity.class, searchBox,
                GemmaBuddy::isCompanion)) {
            result.add(buddy);
        }
        result.sort(Comparator.comparingDouble(buddy -> buddy.distanceToSqr(player)));
        return result;
    }

    public static GemmaBuddyEntity nearestBuddy(ServerPlayer player) {
        List<GemmaBuddyEntity> buddies = findBuddyEntities(player.serverLevel(), player);
        return buddies.isEmpty() ? null : buddies.get(0);
    }

    public static ActionResult setBuddyMode(ServerPlayer player, GemmaBuddyEntity.BuddyMode mode) {
        GemmaBuddyEntity buddy = nearestBuddy(player);
        if (buddy == null) {
            sendError(player, "GemmaBuddy is not spawned. Use /gemmabuddy spawn first.");
            return ActionResult.failure("Buddy is not spawned.");
        }
        buddy.setOwnerUuid(player.getUUID());
        if (mode == GemmaBuddyEntity.BuddyMode.RETURN_HOME) {
            MemoryManager.HomeLocation home = MEMORY.home();
            if (home == null) {
                sendError(player, "No home is marked yet.");
                return ActionResult.failure("Home is not marked.");
            }
            if (!home.dimension().equals(player.level().dimension().location().toString())) {
                sendError(player, "Home is in another dimension; cross-dimension movement is locked.");
                return ActionResult.failure("Home is in another dimension.");
            }
            buddy.setHomePosition(home.position());
        }
        buddy.setBuddyMode(mode);
        sendLine(player, "Buddy mode: " + mode.name().toLowerCase(Locale.ROOT) + ".");
        return ActionResult.success("Buddy mode changed.");
    }

    public static ActionResult guideBuddyTo(ServerPlayer player, BlockPos target) {
        GemmaBuddyEntity buddy = nearestBuddy(player);
        if (buddy == null) {
            sendError(player, "GemmaBuddy is not spawned. Use /gemmabuddy spawn first.");
            return ActionResult.failure("Buddy is not spawned.");
        }
        buddy.setOwnerUuid(player.getUUID());
        buddy.setMovementTarget(target);
        buddy.setBuddyMode(GemmaBuddyEntity.BuddyMode.GUIDING_TO_TARGET);
        sendLine(player, "GemmaBuddy is guiding toward " + formatPosition(target) + ".");
        return ActionResult.success("Buddy guidance started.");
    }

    private static MemoryManager createMemoryManager() {
        MemoryManager memory = new MemoryManager();
        memory.load();
        return memory;
    }

    private static PermissionManager createPermissionManager() {
        PermissionManager permissions = new PermissionManager();
        permissions.load();
        return permissions;
    }

    private static boolean isCompanion(GemmaBuddyEntity mob) {
        return mob != null && mob.getTags().contains(COMPANION_TAG);
    }

    private static String formatPosition(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String stripGemmaPrefix(String text) {
        String cleaned = normalize(text);
        if (!startsWithGemma(cleaned)) {
            return cleaned;
        }
        cleaned = cleaned.substring(5).trim();
        if (cleaned.startsWith(",") || cleaned.startsWith(":")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned;
    }

    private static boolean startsWithGemma(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("gemma")) {
            return false;
        }
        return normalized.length() == 5 || Character.isWhitespace(normalized.charAt(5))
                || normalized.charAt(5) == ',' || normalized.charAt(5) == ':';
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String friendlyError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }
}
