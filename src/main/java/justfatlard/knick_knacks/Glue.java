package justfatlard.knick_knacks;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Kragle on a knack: the same bottle of glue, the same rules, on a thing set down rather than a
 * block. Right-click a knack with it and nobody else picks it up, eats it, turns it, piles on it or
 * shoots it, and it stays put when what it rests on goes. Sneak and use it again to take it off.
 * The one who glued it and operators can do anything with it, as with a glued block.
 *
 * <p>Found by the bottle's name rather than compiled against Kragle, so nothing here loads it, and
 * written in Kragle's own words so a glued knack and a glued block say the same thing. The glue is
 * two tags on the interaction: who, and what they were called.
 */
public final class Glue {
	private Glue() {}

	private static final Identifier KRAGLE = Identifier.fromNamespaceAndPath("kragle", "kragle");
	private static final String OWNER_TAG = "knick_knacks_kragled_";
	private static final String NAME_TAG = "knick_knacks_kragled_by_";

	static boolean isKragle(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(KRAGLE);
	}

	public static boolean isGlued(Interaction handle) {
		return owner(handle) != null;
	}

	/** Who glued it, for the card; null if nobody did. */
	public static String gluedBy(Interaction handle) {
		for (String tag : handle.entityTags()) {
			if (tag.startsWith(NAME_TAG)) return tag.substring(NAME_TAG.length());
		}
		return isGlued(handle) ? "somebody" : null;
	}

	/** Whether this player may take, eat, turn, pile on or unglue it; says why not when they may not. */
	static boolean allows(ServerPlayer player, Interaction handle) {
		if (mayTouch(player, handle)) return true;
		player.sendSystemMessage(Component.translatable("kragle.refused", gluedBy(handle)).withStyle(ChatFormatting.RED), true);
		player.level().playSound(null, handle.getX(), handle.getY(), handle.getZ(), SoundEvents.SHULKER_BOX_CLOSE, SoundSource.BLOCKS, 0.5F, 1.6F);
		return false;
	}

	static boolean mayTouch(ServerPlayer player, Interaction handle) {
		UUID owner = owner(handle);
		return owner == null || owner.equals(player.getUUID()) || isOperator(player);
	}

	/** The bottle used on a knack: glue it, or with a sneak take the glue off. */
	static boolean use(ServerPlayer player, ServerLevel level, Knack.Pile pile, ItemStack bottle) {
		Interaction handle = pile.handle();
		UUID owner = owner(handle);
		Vec3 at = handle.position();

		if (player.isSecondaryUseActive()) {
			if (owner == null) return false;
			if (!mayTouch(player, handle)) {
				player.sendSystemMessage(Component.translatable("kragle.not_yours", gluedBy(handle)).withStyle(ChatFormatting.RED), true);
				return true;
			}
			handle.entityTags().removeIf(tag -> tag.startsWith(OWNER_TAG));
			Knack.refresh(level, handle);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
			player.sendSystemMessage(Component.translatable("kragle.unglued").withStyle(ChatFormatting.GRAY), true);
			return true;
		}

		if (owner != null) {
			player.sendSystemMessage(Component.translatable(owner.equals(player.getUUID()) ? "kragle.already_yours" : "kragle.already_theirs",
				gluedBy(handle)).withStyle(ChatFormatting.GRAY), true);
			return true;
		}

		handle.addTag(OWNER_TAG + player.getUUID());
		handle.addTag(NAME_TAG + player.getName().getString());
		if (!player.getAbilities().instabuild) bottle.shrink(1);
		Knack.refresh(level, handle);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.HONEY_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 1.4F);
		Vec3 middle = Knack.anchorOf(handle);
		level.sendParticles(ParticleTypes.WHITE_SMOKE, middle.x, middle.y + 0.1, middle.z, 6, 0.15, 0.1, 0.15, 0.01);
		player.sendSystemMessage(Component.translatable("kragle.glued", pile.topItem().getHoverName()).withStyle(ChatFormatting.WHITE), true);
		return true;
	}

	private static UUID owner(Interaction handle) {
		for (String tag : handle.entityTags()) {
			if (!tag.startsWith(OWNER_TAG) || tag.startsWith(NAME_TAG)) continue;
			try {
				return UUID.fromString(tag.substring(OWNER_TAG.length()));
			} catch (IllegalArgumentException ignored) {
				// Not ours after all.
			}
		}
		return null;
	}

	/** Operators can unglue anything, as with a glued block: the only way back from a player who left. */
	private static boolean isOperator(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.createCommandSourceStack().permissions());
	}
}
