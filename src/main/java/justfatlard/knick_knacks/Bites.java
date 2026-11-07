package justfatlard.knick_knacks;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eating or drinking what is set down, a bite at a time where there are pictures of the bites.
 *
 * <p>Each right-click is a bite: the food's own sound, and crumbs of it off the table. An item
 * whose mod drew it being eaten, at models/item/eating/&lt;name&gt;_&lt;n&gt;.json the way Let's
 * Cook draws its food, shows those stages one bite at a time through a custom_model_data string,
 * bites_1 and on. Anything without them goes in one. The last bite is the game's own eating, so
 * hunger, effects, stats and the bowl or bottle left over all come out as they would from the hand;
 * the bowl stays on the table where the stew was.
 */
final class Bites {
	private Bites() {}

	private static final String BITES_TAG = "knick_knacks_bites_";
	private static final int MOST_STAGES = 8;
	private static final int CRUMBS = 8;

	private static final Map<Item, Integer> STAGES = new ConcurrentHashMap<>();

	/** What a display holds, as it would be picked up: without the bites it is drawn with. */
	static ItemStack whole(Display.ItemDisplay shown) {
		ItemStack stack = shown.getSlot(0).get().copy();
		if (bitesOf(shown) > 0) stack.remove(DataComponents.CUSTOM_MODEL_DATA);
		return stack;
	}

	/** One bite off the top of the pile; false if it is not something to eat or drink, or not now. */
	static boolean take(ServerPlayer player, ServerLevel level, Knack.Pile pile) {
		Display.ItemDisplay top = pile.top();
		ItemStack whole = whole(top);
		Consumable consumable = whole.get(DataComponents.CONSUMABLE);
		if (consumable == null || !consumable.canConsume(player, whole)) return false;

		Vec3 at = pile.handle().position();
		if (consumable.hasConsumeParticles()) {
			level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, whole.getItem()),
				at.x, at.y + 0.1, at.z, CRUMBS, 0.1, 0.05, 0.1, 0.05);
		}

		int bites = bitesOf(top);
		if (bites < stagesOf(whole)) {
			bites++;
			setBites(top, bites);
			ItemStack bitten = whole.copy();
			bitten.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of("bites_" + bites), List.of()));
			top.setItemStack(bitten);
			// The same pitch spread LivingEntity gives an eaten item, so a bite sounds like one
			RandomSource random = level.getRandom();
			level.playSound(null, at.x, at.y, at.z, consumable.sound().value(), SoundSource.PLAYERS,
				1.0F, 1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
			return true;
		}

		ItemStack left = whole.copyWithCount(1).finishUsingItem(level, player);
		boolean leftOver = !left.isEmpty() && !ItemStack.isSameItem(left, whole);
		if (leftOver && pile.size() == 1) {
			setBites(top, 0);
			Knack.show(level, pile, top, left);
		} else {
			Knack.takeOff(level, pile.handle(), top);
			if (leftOver) Knack.give(player, left);
		}
		return true;
	}

	/** How many pictures of being eaten this item's mod drew; none where it carries a custom model of its own already. */
	private static int stagesOf(ItemStack stack) {
		if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) return 0;
		return STAGES.computeIfAbsent(stack.getItem(), item -> {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			ClassLoader loader = Bites.class.getClassLoader();
			int stages = 0;
			while (stages < MOST_STAGES && loader.getResource("assets/" + id.getNamespace()
					+ "/models/item/eating/" + id.getPath() + "_" + stages + ".json") != null) {
				stages++;
			}
			return stages;
		});
	}

	private static int bitesOf(Display.ItemDisplay shown) {
		return Knack.numberAfter(shown, BITES_TAG);
	}

	private static void setBites(Display.ItemDisplay shown, int bites) {
		shown.entityTags().removeIf(tag -> tag.startsWith(BITES_TAG));
		if (bites > 0) shown.addTag(BITES_TAG + bites);
	}
}
