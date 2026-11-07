package justfatlard.knick_knacks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * What a knack gives off: light from the things that glow, and a few motes from the things that
 * are not quite of this world.
 *
 * <p>Light is vanilla's invisible light block, in the air the knack sits in, and only there: a
 * block of anything else is never replaced, and a light block is only ever taken away by the
 * knack that put it there being gone. Several glowing knacks in one block share the one light.
 * Placing a block there replaces the light like any light block; the knack puts it back the
 * next time it looks, once a second.
 *
 * <p>Motes come from an item tag per kind, and anything enchanted gives off the glyphs an
 * enchanting table draws toward its books.
 */
public final class Aura {
	private Aura() {}

	public static final TagKey<Item> GLOWS = Knack.itemTag("glows");
	private static final int LIGHT = 10;

	private static final String GLOW_TAG = "knick_knacks_glow";
	private static final String MOTES_TAG = "knick_knacks_motes_";

	private record Motes(String name, TagKey<Item> tag, SimpleParticleType particle, double speed) {}

	private static final List<Motes> MOTES = List.of(
		new Motes("portal", Knack.itemTag("motes/portal"), ParticleTypes.PORTAL, 0.4),
		new Motes("end_rod", Knack.itemTag("motes/end_rod"), ParticleTypes.END_ROD, 0.01),
		new Motes("smoke", Knack.itemTag("motes/smoke"), ParticleTypes.SMOKE, 0.01),
		new Motes("glow", Knack.itemTag("motes/glow"), ParticleTypes.GLOW, 0.02),
		new Motes("enchant", Knack.itemTag("motes/enchant"), ParticleTypes.ENCHANT, 0.6));

	static void mark(Interaction handle, ItemStack top) {
		if (top.is(GLOWS)) handle.addTag(GLOW_TAG);
		else handle.removeTag(GLOW_TAG);
		handle.entityTags().removeIf(tag -> tag.startsWith(MOTES_TAG));
		Motes motes = motesFor(top);
		if (motes != null) handle.addTag(MOTES_TAG + motes.name());
	}

	private static Motes motesFor(ItemStack top) {
		if (top.isEmpty()) return null;
		for (Motes motes : MOTES) if (top.is(motes.tag())) return motes;
		return top.hasFoil() ? MOTES.get(MOTES.size() - 1) : null;
	}

	/** As far as the game sends a particle to anyone. */
	private static final double SEEN_WITHIN = 32;

	/** From the interaction's tick, now and then: a mote or two, for a knack that gives them off and somebody close enough to see it. */
	public static void drift(ServerLevel level, Interaction handle) {
		if (level.getRandom().nextInt(3) != 0) return;
		if (!level.hasNearbyAlivePlayer(handle.getX(), handle.getY(), handle.getZ(), SEEN_WITHIN)) return;
		for (String tag : handle.entityTags()) {
			if (!tag.startsWith(MOTES_TAG)) continue;
			String name = tag.substring(MOTES_TAG.length());
			for (Motes motes : MOTES) {
				if (!motes.name().equals(name)) continue;
				Vec3 at = Knack.anchorOf(handle).add(Knack.out(Knack.faceOf(handle)).scale(0.15));
				level.sendParticles(motes.particle(), at.x, at.y, at.z, 1, 0.12, 0.08, 0.12, motes.speed());
			}
			return;
		}
	}

	/** Put the light back if something took it, for a knack that glows. */
	static void keepLit(ServerLevel level, Interaction handle) {
		if (!handle.entityTags().contains(GLOW_TAG)) return;
		BlockPos cell = cellOf(level, handle);
		if (level.getBlockState(cell).isAir()) relight(level, handle);
	}

	/** Light the air this knack sits in to the brightest glowing knack there, or put it out. */
	static void relight(ServerLevel level, Interaction handle) {
		BlockPos cell = cellOf(level, handle);
		BlockState there = level.getBlockState(cell);
		boolean ours = there.is(Blocks.LIGHT);
		if (!ours && !there.isAir()) return;

		boolean lit = !level.getEntitiesOfClass(Interaction.class, new AABB(cell).inflate(1), other ->
			Knack.isKnack(other) && !other.isRemoved() && other.entityTags().contains(GLOW_TAG) && cellOf(level, other).equals(cell)).isEmpty();
		if (lit) {
			if (!ours || there.getValue(LightBlock.LEVEL) != LIGHT) {
				level.setBlock(cell, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LIGHT), Block.UPDATE_ALL);
			}
		} else if (ours) {
			level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	/** The air a knack sits in: the block in front of where it rests, or the next one out when that one is the surface itself (a slab's top). */
	private static BlockPos cellOf(ServerLevel level, Interaction handle) {
		Direction face = Knack.faceOf(handle);
		BlockPos cell = BlockPos.containing(Knack.anchorOf(handle).add(Knack.out(face).scale(0.1)));
		BlockState there = level.getBlockState(cell);
		return there.isAir() || there.is(Blocks.LIGHT) ? cell : cell.relative(face);
	}
}
