package com.faboslav.friendsandfoes.entity.passive;

import com.faboslav.friendsandfoes.FriendsAndFoes;
import com.faboslav.friendsandfoes.client.animation.AnimationContextTracker;
import com.faboslav.friendsandfoes.entity.AnimatedEntity;
import com.faboslav.friendsandfoes.util.RandomGenerator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer.Builder;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class MaulerEntity extends PathAwareEntity implements Angerable, AnimatedEntity
{
	private static final float MOVEMENT_SPEED = 0.35F;
	private static final int MAXIMUM_STORED_EXPERIENCE_POINTS = 1395;
	private static final Predicate<Entity> BABY_VILLAGER_PREDICATE;
	private static final Predicate<Entity> BABY_ZOMBIE_PREDICATE;
	private static final Predicate<Entity> SMALL_SLIME_PREDICATE;

	private static final String STORED_EXPERIENCE_POINTS_NBT_NAME = "StoredExperiencePoints";

	private static final TrackedData<String> TYPE;
	private static final TrackedData<Integer> ANGER_TIME;
	private static final TrackedData<Integer> STORED_EXPERIENCE_POINTS;

	@Environment(EnvType.CLIENT)
	private AnimationContextTracker animationTickTracker;

	@Nullable
	private UUID angryAt;

	static {
		BABY_VILLAGER_PREDICATE = (entity) -> {
			return entity instanceof VillagerEntity && ((VillagerEntity) entity).isBaby();
		};
		BABY_ZOMBIE_PREDICATE = (entity) -> {
			return entity instanceof ZombieEntity && ((ZombieEntity) entity).isBaby();
		};
		SMALL_SLIME_PREDICATE = (entity) -> {
			return entity instanceof SlimeEntity && ((SlimeEntity) entity).getSize() == SlimeEntity.MIN_SIZE;
		};
		TYPE = DataTracker.registerData(MaulerEntity.class, TrackedDataHandlerRegistry.STRING);
		ANGER_TIME = DataTracker.registerData(MaulerEntity.class, TrackedDataHandlerRegistry.INTEGER);
		STORED_EXPERIENCE_POINTS = DataTracker.registerData(MaulerEntity.class, TrackedDataHandlerRegistry.INTEGER);
	}

	public MaulerEntity(EntityType<? extends MaulerEntity> entityType, World world) {
		super(entityType, world);
	}

	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(TYPE, Type.DESERT.name());
		this.dataTracker.startTracking(ANGER_TIME, 0);
		this.dataTracker.startTracking(STORED_EXPERIENCE_POINTS, 0);
	}

	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		this.writeAngerToNbt(nbt);
		nbt.putInt(STORED_EXPERIENCE_POINTS_NBT_NAME, this.getStoredExperiencePoints());
	}

	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.readAngerFromNbt(this.getWorld(), nbt);
		this.setStoredExperiencePoints(nbt.getInt(STORED_EXPERIENCE_POINTS_NBT_NAME));
		this.experiencePoints = this.getStoredExperiencePoints();
		this.calculateDimensions();
	}

	@Nullable
	public EntityData initialize(
		ServerWorldAccess world,
		LocalDifficulty difficulty,
		SpawnReason spawnReason,
		@Nullable EntityData entityData,
		@Nullable NbtCompound entityNbt
	) {
		Type type;

		if (
			spawnReason == SpawnReason.COMMAND
			|| spawnReason == SpawnReason.SPAWN_EGG
			|| spawnReason == SpawnReason.SPAWNER
			|| spawnReason == SpawnReason.DISPENSER
		) {
			int randomTypeNumber = RandomGenerator.generateInt(0, Type.values().length - 1);
			type = Type.values()[randomTypeNumber];
		} else {
			RegistryKey<Biome> biomeKey = world.getBiome(this.getBlockPos()).getKey().orElse(BiomeKeys.DESERT);
			type = Type.getTypeByBiome(biomeKey);
		}

		this.setType(type);

		return super.initialize(world, difficulty, spawnReason, entityData, entityNbt);
	}

	public static boolean canSpawn(
		EntityType<MaulerEntity> maulerEntityType,
		ServerWorldAccess serverWorldAccess,
		SpawnReason spawnReason,
		BlockPos blockPos,
		Random random
	) {
		BlockState blockState = serverWorldAccess.getBlockState(blockPos.down());

		boolean isRelatedBlock = (
			blockState.isOf(Blocks.SAND)
			|| blockState.isOf(Blocks.RED_SAND)
			|| blockState.isOf(Blocks.GRASS_BLOCK)
		);

		return isRelatedBlock;
	}

	protected void initGoals() {
		this.goalSelector.add(1, new SwimGoal(this));
		this.goalSelector.add(4, new MeleeAttackGoal(this, 0.5F, false));
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.6D));
		this.goalSelector.add(11, new LookAtEntityGoal(this, PlayerEntity.class, 10.0F));

		this.targetSelector.add(1, (new RevengeGoal(this)).setGroupRevenge());
		this.targetSelector.add(2, new ActiveTargetGoal(this, RabbitEntity.class, true));
		this.targetSelector.add(2, new ActiveTargetGoal(this, ChickenEntity.class, true));
		this.targetSelector.add(4, new ActiveTargetGoal(this, VillagerEntity.class, 10, true, true, BABY_VILLAGER_PREDICATE));
		this.targetSelector.add(4, new ActiveTargetGoal(this, ZombieEntity.class, 10, true, true, BABY_ZOMBIE_PREDICATE));
		this.targetSelector.add(4, new ActiveTargetGoal(this, SlimeEntity.class, 10, true, true, SMALL_SLIME_PREDICATE));

		/*
		this.goalSelector.add(1, new SwimGoal(this));
		this.goalSelector.add(1, new PowderSnowJumpGoal(this, this.world));
		this.goalSelector.add(1, new MaulerEntity.EscapeDangerGoal(this, 2.2D));
		this.goalSelector.add(3, new TemptGoal(this, 1.0D, Ingredient.ofItems(new ItemConvertible[]{Items.CARROT, Items.GOLDEN_CARROT, Blocks.DANDELION}), false));
		this.goalSelector.add(4, new MaulerEntity.FleeGoal(this, PlayerEntity.class, 8.0F, 2.2D, 2.2D));
		this.goalSelector.add(4, new MaulerEntity.FleeGoal(this, WolfEntity.class, 10.0F, 2.2D, 2.2D));
		this.goalSelector.add(4, new MaulerEntity.FleeGoal(this, HostileEntity.class, 4.0F, 2.2D, 2.2D));
		this.goalSelector.add(5, new MaulerEntity.EatCarrotCropGoal(this));
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.6D));
		this.goalSelector.add(11, new LookAtEntityGoal(this, PlayerEntity.class, 10.0F));
	*/
	}

	public void tickMovement() {
		super.tickMovement();

		if (this.getWorld().isClient() == false) {
			this.tickAngerLogic((ServerWorld) this.getWorld(), true);
		}
	}

	@Override
	public float getMovementSpeed() {
		return MOVEMENT_SPEED;
	}

	@Override
	public ActionResult interactMob(
		PlayerEntity player,
		Hand hand
	) {
		ItemStack itemStack = player.getStackInHand(hand);
		Item itemInHand = itemStack.getItem();
		boolean interactionResult = false;

		if (
			this.hasAngerTime() == false
			&& (
				itemStack.hasEnchantments()
				|| itemInHand == Items.ENCHANTED_BOOK
			)
		) {
			interactionResult = this.tryToInteractWithEnhancedItem(player, itemStack);
		} else if (
			this.hasAngerTime() == false
			&& itemInHand == Items.GLASS_BOTTLE
		) {
			interactionResult = this.tryToInteractWithGlassBottle(player, itemStack);
		}

		if (interactionResult) {
			this.emitGameEvent(GameEvent.MOB_INTERACT, this.getCameraBlockPos());
			return ActionResult.success(this.world.isClient);
		}

		return super.interactMob(player, hand);
	}

	private boolean tryToInteractWithEnhancedItem(
		PlayerEntity player,
		ItemStack itemStack
	) {
		int storedExperiencePoints = this.getStoredExperiencePoints();

		if (storedExperiencePoints >= MAXIMUM_STORED_EXPERIENCE_POINTS) {
			return false;
		}

		int experiencePoints = this.getExperiencePoints(itemStack);
		int recalculatedExperiencePoints = storedExperiencePoints + experiencePoints;

		if (recalculatedExperiencePoints > MAXIMUM_STORED_EXPERIENCE_POINTS) {
			recalculatedExperiencePoints = MAXIMUM_STORED_EXPERIENCE_POINTS;
		}

		this.setStoredExperiencePoints(recalculatedExperiencePoints);
		this.experiencePoints = this.getStoredExperiencePoints();
		this.calculateDimensions();

		itemStack.decrement(1);

		this.getEntityWorld().playSoundFromEntity(null, this, SoundEvents.ITEM_HONEYCOMB_WAX_ON, SoundCategory.BLOCKS, 1.0F, 1.0F);
		this.spawnParticles(ParticleTypes.ENCHANT, 7);

		FriendsAndFoes.getLogger().info(String.valueOf(this.getStoredExperiencePoints()));
		return true;
	}

	private boolean tryToInteractWithGlassBottle(
		PlayerEntity player,
		ItemStack itemStack
	) {
		int storedExperiencePoints = this.getStoredExperiencePoints();

		FriendsAndFoes.getLogger().info(String.valueOf(this.getStoredExperiencePoints()));

		if (storedExperiencePoints < 7) {
			return false;
		}

		int glassBottlesCount = itemStack.getCount();
		int experienceBottleCount = storedExperiencePoints / 7;

		if (experienceBottleCount > glassBottlesCount) {
			experienceBottleCount = glassBottlesCount;
		}

		FriendsAndFoes.getLogger().info("experienceBottleCount: " + experienceBottleCount);
		FriendsAndFoes.getLogger().info("glassBottlesCount: " + glassBottlesCount);

		itemStack.decrement(experienceBottleCount);
		ItemStack experienceBottleItemStack = new ItemStack(Items.EXPERIENCE_BOTTLE, experienceBottleCount);
		player.giveItemStack(experienceBottleItemStack);

		this.setStoredExperiencePoints(storedExperiencePoints - experienceBottleCount * 7);

		this.playSound(SoundEvents.ITEM_BOTTLE_FILL_DRAGONBREATH, 1.0F, 1.0F);
		this.spawnParticles(ParticleTypes.ENCHANT, 7);

		return true;
	}

	public boolean shouldSpawnSprintingParticles() {
		return false;
	}

	public static Builder createRabbitAttributes() {
		return MobEntity.createMobAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 3.0D).add(EntityAttributes.GENERIC_MOVEMENT_SPEED, MOVEMENT_SPEED).add(EntityAttributes.GENERIC_ATTACK_SPEED, MOVEMENT_SPEED);
	}

	protected SoundEvent getAmbientSound() {
		return SoundEvents.ENTITY_RABBIT_AMBIENT;
	}

	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_RABBIT_HURT;
	}

	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_RABBIT_DEATH;
	}

	public boolean tryAttack(Entity target) {
		this.playSound(SoundEvents.ENTITY_RABBIT_ATTACK, 1.0F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
		return target.damage(DamageSource.mob(this), 0.0F);
	}

	public int getAngerTime() {
		return this.dataTracker.get(ANGER_TIME);
	}

	public void setAngerTime(int angerTime) {
		this.dataTracker.set(ANGER_TIME, angerTime);
	}

	public void chooseRandomAngerTime() {
		int angerTime = RandomGenerator.generateInt(400, 1000);
		this.setAngerTime(angerTime);
	}

	@Nullable
	public UUID getAngryAt() {
		return this.angryAt;
	}

	public void setAngryAt(@Nullable UUID angryAt) {
		this.angryAt = angryAt;
	}

	public Type getMaulerType() {
		return MaulerEntity.Type.fromName(this.dataTracker.get(TYPE));
	}

	private void setType(MaulerEntity.Type type) {
		this.dataTracker.set(TYPE, type.name);
	}

	public int getStoredExperiencePoints() {
		return this.dataTracker.get(STORED_EXPERIENCE_POINTS);
	}

	public void setStoredExperiencePoints(int storedExperiencePoints) {
		this.dataTracker.set(STORED_EXPERIENCE_POINTS, storedExperiencePoints);
	}

	public float getSize() {
		return 1.0F + (float) this.getStoredExperiencePoints() / MAXIMUM_STORED_EXPERIENCE_POINTS;
	}

	@Override
	public float getScaleFactor() {
		return this.getSize();
	}

	public Vec3d getLeashOffset() {
		return new Vec3d(0.0D, 0.6F * this.getStandingEyeHeight(), this.getWidth() * 0.4F);
	}

	private int getExperiencePoints(ItemStack stack) {
		int experiencePoints = 0;
		Map<Enchantment, Integer> mappedEnchantments = EnchantmentHelper.get(stack);

		for (Map.Entry<Enchantment, Integer> enchantmentItem : mappedEnchantments.entrySet()) {
			Enchantment enchantment = enchantmentItem.getKey();
			Integer enchantmentExperiencePoints = enchantmentItem.getValue();

			if (enchantmentItem.getKey().isCursed()) {
				continue;
			}

			experiencePoints += enchantment.getMinPower(enchantmentExperiencePoints);
		}

		return experiencePoints;
	}

	private void spawnParticles(
		DefaultParticleType particleType,
		int amount
	) {
		World world = this.getWorld();

		if (world.isClient()) {
			return;
		}

		for (int i = 0; i < amount; i++) {
			((ServerWorld) world).spawnParticles(
				particleType,
				this.getParticleX(1.0D),
				this.getRandomBodyY() + 0.5D,
				this.getParticleZ(1.0D),
				1,
				this.getRandom().nextGaussian() * 0.02D,
				this.getRandom().nextGaussian() * 0.02D,
				this.getRandom().nextGaussian() * 0.02D,
				1.0D
			);
		}
	}

	@Override
	@Environment(EnvType.CLIENT)
	public AnimationContextTracker getAnimationContextTracker() {
		if (this.animationTickTracker == null) {
			this.animationTickTracker = new AnimationContextTracker();
		}

		return this.animationTickTracker;
	}

	public enum Type
	{
		DESERT("desert"),
		BADLANDS("badlands"),
		SWAMP("swamp");

		private final String name;

		Type(String name) {
			this.name = name;
		}

		public String getName() {
			return this.name;
		}

		private static MaulerEntity.Type fromName(String name) {
			MaulerEntity.Type[] types = values();

			for (Type type : types) {
				if (type.name.equals(name)) {
					return type;
				}
			}

			return DESERT;
		}

		public static Type getTypeByBiome(RegistryKey<Biome> biome) {
			if (biome == BiomeKeys.BADLANDS) {
				return BADLANDS;
			} else if (biome == BiomeKeys.SWAMP) {
				return SWAMP;
			}

			return DESERT;
		}
	}
}
