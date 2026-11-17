package dev.regnum.economy.npc;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class RegnumNpc extends PathfinderMob {
    public static final int NAME_LIMIT = 48;
    public static final int DIALOGUE_LIMIT = 240;
    private static final int VERSION = 1;
    private static final String DATA_KEY = "RegnumNpc";
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(
            RegnumNpc.class, EntityDataSerializers.STRING);

    private UUID proprietor;
    private UUID city;
    private NpcRole role = NpcRole.GENERIC;
    private boolean active;
    private String roleTitle = "";
    private String greeting = "";
    private String details = "";
    private Tag unreadable;

    public RegnumNpc(EntityType<? extends RegnumNpc> type, Level level) {
        super(type, level);
        stationary();
    }

    private void stationary() {
        setNoAi(true);
        setNoGravity(true);
        setPersistenceRequired();
    }

    void configure(UUID proprietor, UUID city, NpcRole role, String name) {
        if (this.proprietor != null || unreadable != null || !validText(name, NAME_LIMIT) || name.isBlank()) {
            throw new IllegalArgumentException("Invalid NPC definition");
        }
        this.proprietor = Objects.requireNonNull(proprietor);
        this.city = city;
        this.role = Objects.requireNonNull(role);
        this.active = true;
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);
        appearance(role.appearance());
    }

    public UUID proprietor() { return proprietor; }
    public UUID city() { return city; }
    public NpcRole role() { return role; }
    Component roleLabel() { return roleTitle.isEmpty() ? role.label() : Component.literal(roleTitle); }
    String roleTitle() { return roleTitle; }
    void roleTitle(String value) { roleTitle = value; }
    boolean active() { return active; }
    void active(boolean active) { this.active = active; }
    String greeting() { return greeting; }
    String details() { return details; }
    void greeting(String value) { greeting = value; }
    void details(String value) { details = value; }

    public NpcAppearance appearance() {
        var appearance = NpcAppearance.find(entityData.get(APPEARANCE));
        return appearance == null ? NpcAppearance.DEFAULT : appearance;
    }

    void appearance(NpcAppearance appearance) {
        entityData.set(APPEARANCE, appearance.id());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcAppearance.DEFAULT.id());
    }

    public InteractionResult interactWithNpc(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            NpcInteractions.open(serverPlayer, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public void kill() { discard(); }
    @Override public boolean canCollideWith(Entity other) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean startRiding(Entity vehicle, boolean force) { return false; }

    @Override
    public void tick() {
        if (!getDeltaMovement().equals(Vec3.ZERO)) setDeltaMovement(Vec3.ZERO);
        super.tick();
    }
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean canChangeDimensions(Level origin, Level destination) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (unreadable != null) {
            tag.put(DATA_KEY, unreadable.copy());
            return;
        }
        if (proprietor == null) return;
        CompoundTag data = new CompoundTag();
        data.putInt("version", VERSION);
        data.putUUID("proprietor", proprietor);
        if (city != null) data.putUUID("city", city);
        data.putString("role", role.id().toString());
        data.putString("appearance", appearance().id());
        data.putBoolean("active", active);
        data.putString("role_title", roleTitle);
        data.putString("greeting", greeting);
        data.putString("details", details);
        tag.put(DATA_KEY, data);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        stationary();
        proprietor = null;
        city = null;
        role = NpcRole.GENERIC;
        appearance(NpcAppearance.DEFAULT);
        active = false;
        unreadable = null;
        roleTitle = "";
        greeting = "";
        details = "";
        if (!tag.contains(DATA_KEY)) return;
        try {
            CompoundTag data = tag.getCompound(DATA_KEY);
            var savedRole = NpcRole.find(ResourceLocation.parse(data.getString("role")));
            var savedAppearance = NpcAppearance.find(data.getString("appearance"));
            if (!data.contains("version", Tag.TAG_INT) || data.getInt("version") != VERSION || savedRole == null || savedAppearance == null
                    || !validText(data.getString("role_title"), NAME_LIMIT)
                    || !data.contains("active", Tag.TAG_BYTE) || getCustomName() == null
                    || getCustomName().getString().isBlank() || !validText(getCustomName().getString(), NAME_LIMIT)
                    || !validText(data.getString("greeting"), DIALOGUE_LIMIT)
                    || !validText(data.getString("details"), DIALOGUE_LIMIT)) {
                throw new IllegalArgumentException("Unsupported or malformed NPC definition");
            }
            UUID savedOwner = data.getUUID("proprietor");
            city = data.contains("city") ? data.getUUID("city") : null;
            role = savedRole;
            appearance(savedAppearance);
            active = data.getBoolean("active");
            roleTitle = data.getString("role_title");
            greeting = data.getString("greeting");
            details = data.getString("details");
            proprietor = savedOwner;
        } catch (RuntimeException failure) {
            unreadable = tag.get(DATA_KEY).copy();
            LogUtils.getLogger().warn("Keeping unreadable Regnum NPC {} inactive: {}", getUUID(), failure.getMessage());
        }
    }

    static boolean validText(String value, int limit) {
        return value.length() <= limit && value.codePoints().noneMatch(Character::isISOControl);
    }
}
