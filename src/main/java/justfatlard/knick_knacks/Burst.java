package justfatlard.knick_knacks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Set dressing that does something. A bottle set down breaks when something is shot or thrown at
 * it, and what is in it goes where a thrown one would: a potion splashes, a lingering potion leaves
 * its cloud, a bottle o' enchanting spills its experience. A rocket set down goes up when the block
 * under it, or the air it stands in, is powered: one rocket a pulse, from the top of the pile.
 *
 * <p>Both are vanilla's own: the thrown bottle and the rocket are the real entities, spawned where
 * the knack was, so every effect, sound and cloud is exactly what the game already does. The
 * interaction carries a tag saying which a knack is, set whenever its pile changes, because both
 * are asked about far more often than a pile is worth looking up for.
 */
public final class Burst {
	private Burst() {}

	/** What breaks when hit: bottles. */
	public static final TagKey<Item> SHATTERS = Knack.itemTag("shatters");
	/** What goes up on a redstone pulse. */
	public static final TagKey<Item> LAUNCHES = Knack.itemTag("launches");

	private static final String BRITTLE_TAG = "knick_knacks_brittle";
	private static final String FUSE_TAG = "knick_knacks_fuse";
	private static final String POWERED_TAG = "knick_knacks_powered";

	/** Mark the interaction for what is now on top. Glue holds a bottle together and a rocket down. */
	static void mark(Interaction handle, ItemStack top) {
		boolean glued = Glue.isGlued(handle);
		setTag(handle, BRITTLE_TAG, !glued && top.is(SHATTERS));
		setTag(handle, FUSE_TAG, !glued && top.is(LAUNCHES));
		if (!handle.entityTags().contains(FUSE_TAG)) handle.removeTag(POWERED_TAG);
	}

	/** For the projectile mixin: whether an arrow or a snowball should hit this at all. */
	public static boolean isBrittle(Interaction handle) {
		return handle.entityTags().contains(BRITTLE_TAG);
	}

	/** A projectile has hit a brittle knack: break the bottle and let what was in it go. */
	public static void shatter(ServerLevel level, Interaction handle, Entity shooter) {
		Knack.Pile pile = Knack.pileOf(level, handle);
		if (pile == null) {
			handle.discard();
			return;
		}
		Direction face = Knack.faceOf(handle);
		Vec3 anchor = Knack.anchorOf(handle);
		for (var shown : pile.layers()) spill(level, Bites.whole(shown), anchor, face, shooter);
		Knack.clear(level, pile);
	}

	private static void spill(ServerLevel level, ItemStack stack, Vec3 anchor, Direction face, Entity shooter) {
		// Thrown at the face it stood on from just in front, so it lands right there next tick.
		Vec3 from = anchor.add(Knack.out(face).scale(0.3));
		ThrowableItemProjectile thrown;
		if (stack.is(Items.LINGERING_POTION)) {
			thrown = new ThrownLingeringPotion(level, from.x, from.y, from.z, stack);
		} else if (stack.is(Items.SPLASH_POTION)) {
			thrown = new ThrownSplashPotion(level, from.x, from.y, from.z, stack);
		} else if (stack.is(Items.POTION)) {
			thrown = new ThrownSplashPotion(level, from.x, from.y, from.z, stack.transmuteCopy(Items.SPLASH_POTION));
		} else if (stack.is(Items.EXPERIENCE_BOTTLE)) {
			thrown = new ThrownExperienceBottle(level, from.x, from.y, from.z, stack);
		} else {
			level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack.getItem()), anchor.x, anchor.y + 0.15, anchor.z, 8, 0.1, 0.1, 0.1, 0.08);
			level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.8F, 1.2F);
			return;
		}
		thrown.setOwner(shooter);
		thrown.setDeltaMovement(Knack.out(face).scale(-0.5));
		level.addFreshEntity(thrown);
	}

	/**
	 * From the interaction's tick, for a knack with a rocket on top: on the rising edge of a signal,
	 * one goes up. Every other tick is enough: the shortest pulse there is, an observer's, lasts two.
	 */
	public static void watchFuse(ServerLevel level, Interaction handle) {
		if (!handle.entityTags().contains(FUSE_TAG)) return;
		Direction face = Knack.faceOf(handle);
		Vec3 anchor = Knack.anchorOf(handle);
		BlockPos air = BlockPos.containing(anchor.add(Knack.out(face).scale(0.1)));
		BlockPos behind = BlockPos.containing(anchor.subtract(Knack.out(face).scale(0.1)));
		boolean powered = level.hasNeighborSignal(air) || level.hasNeighborSignal(behind);
		boolean was = handle.entityTags().contains(POWERED_TAG);
		if (powered != was) setTag(handle, POWERED_TAG, powered);
		if (!powered || was) return;

		Knack.Pile pile = Knack.pileOf(level, handle);
		if (pile == null) return;
		var top = pile.top();
		ItemStack rocket = top.getSlot(0).get().copy();
		Vec3 from = anchor.add(Knack.out(face).scale(0.15));
		level.addFreshEntity(new FireworkRocketEntity(level, from.x, from.y, from.z, rocket));
		Knack.takeOff(level, handle, top);
	}

	private static void setTag(Interaction handle, String tag, boolean on) {
		if (on) handle.addTag(tag);
		else handle.removeTag(tag);
	}
}
