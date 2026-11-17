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
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eating or drinking what is set down, held to like food in the hand, a portion at a time where
 * there are pictures of the bites.
 *
 * <p>Right-click and hold. A held click comes again every few ticks, and eating goes on while it
 * keeps coming; let go and it stops where it was. The whole thing takes as long as eating it from
 * the hand would, cut into portions: one for each picture its mod drew of it being eaten, at
 * models/item/eating/&lt;name&gt;_&lt;n&gt;.json the way Let's Cook draws its food (shown through
 * a custom_model_data string, bites_1 and on), and one more to finish it. Each portion feeds its
 * share; the last is the game's own eating, so effects, stats and the bowl or bottle left over
 * come out as they would from the hand, the bowl staying on the table where the stew was. Anything
 * without pictures is one portion: a held click the length of eating it.
 */
final class Bites {
	private Bites() {}

	private static final String BITES_TAG = "knick_knacks_bites_";
	private static final int MOST_STAGES = 8;
	private static final int CRUMBS = 8;
	/** A held click comes every four ticks; one later than this was let go and clicked again. */
	static final int HELD_GAP = 6;

	private static final Map<Item, Integer> STAGES = new ConcurrentHashMap<>();

	/** Who is eating which display, since when they last clicked, and how far into this portion. */
	private record Eating(java.util.UUID display, int lastTick, int progress) {}

	private static final Map<java.util.UUID, Eating> eating = new ConcurrentHashMap<>();

	static void forget(ServerPlayer player) {
		eating.remove(player.getUUID());
	}

	/** What a display holds, as it would be picked up: without the bites it is drawn with. */
	static ItemStack whole(Display.ItemDisplay shown) {
		ItemStack stack = shown.getSlot(0).get().copy();
		if (bitesOf(shown) > 0) stack.remove(DataComponents.CUSTOM_MODEL_DATA);
		return stack;
	}

	/**
	 * A held click on the top of the pile: eating goes on, and a portion is taken if one is due.
	 * False if it is not something to eat or drink, or not now.
	 */
	static boolean take(ServerPlayer player, ServerLevel level, Knack.Pile pile) {
		Display.ItemDisplay top = pile.top();
		ItemStack whole = whole(top);
		Consumable consumable = whole.get(DataComponents.CONSUMABLE);
		if (consumable == null || !consumable.canConsume(player, whole)) {
			forget(player);
			return false;
		}

		int now = level.getServer().getTickCount();
		Eating was = eating.get(player.getUUID());
		boolean held = was != null && was.display().equals(top.getUUID()) && now - was.lastTick() <= HELD_GAP;
		int progress = held ? was.progress() + (now - was.lastTick()) : 0;

		Vec3 at = pile.handle().position();
		if (consumable.hasConsumeParticles()) {
			level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, whole.getItem()),
				at.x, at.y + 0.1, at.z, CRUMBS, 0.1, 0.05, 0.1, 0.05);
		}
		// The same pitch spread LivingEntity gives an eaten item, so each click sounds like eating
		RandomSource random = level.getRandom();
		level.playSound(null, at.x, at.y, at.z, consumable.sound().value(), SoundSource.PLAYERS,
			0.5F + 0.5F * random.nextInt(2), (random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F);

		int stages = stagesOf(whole);
		int portions = stages + 1;
		int perPortion = Math.max(1, Math.ceilDiv(consumable.consumeTicks(), portions));
		if (progress < perPortion) {
			eating.put(player.getUUID(), new Eating(top.getUUID(), now, progress));
			return true;
		}
		// Held on, the next portion starts from here.
		eating.put(player.getUUID(), new Eating(top.getUUID(), now, 0));

		int bites = bitesOf(top);
		FoodProperties food = whole.get(DataComponents.FOOD);
		if (bites < stages) {
			if (food != null) player.getFoodData().eat(new FoodProperties(share(food.nutrition(), bites, portions), food.saturation() / portions, food.canAlwaysEat()));
			bites++;
			setBites(top, bites);
			ItemStack bitten = whole.copy();
			bitten.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of("bites_" + bites), List.of()));
			top.setItemStack(bitten);
			return true;
		}

		// The last portion: what is left of the food, then everything else eating it does.
		ItemStack last = whole.copyWithCount(1);
		if (food != null && portions > 1) {
			last.set(DataComponents.FOOD, new FoodProperties(share(food.nutrition(), stages, portions),
				food.saturation() / portions, food.canAlwaysEat()));
		}
		ItemStack left = last.finishUsingItem(level, player);
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

	/** This portion's whole hunger points, so the portions add up to exactly the food's. */
	private static int share(int nutrition, int portion, int portions) {
		return nutrition * (portion + 1) / portions - nutrition * portion / portions;
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
