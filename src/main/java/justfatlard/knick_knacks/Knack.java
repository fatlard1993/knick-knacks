package justfatlard.knick_knacks;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A thing set down: a pile of item displays, one per item, and an interaction that can be hit and
 * clicked, all standing at the same point in front of the face they rest on.
 *
 * <p>The entities are the whole record. Each display holds its one item, its place in the pile,
 * the face it rests on and any bites taken from it as tags; the turn rides on every display; the
 * pile and its interaction find each other by standing at exactly the same spot. A layer's height
 * and twist are in its transformation, not its position, so they never move it off that spot.
 * Nothing is saved anywhere else, and a vanilla client draws all of it.
 *
 * <p>On a floor the spot is on the surface itself. On a wall it is a hair out from the face, so the
 * display takes its light from the air in front rather than the dark inside the wall, and half a
 * knack's height below the middle of what hangs there, because an interaction grows up from its
 * position and the hitbox should cover the thing. Under a ceiling it is a whole knack's height
 * below, for the same reason.
 */
public final class Knack {
	private Knack() {}

	static final String TAG = "knick_knacks";
	private static final String TURN_TAG = "knick_knacks_turn_";
	private static final String LAYER_TAG = "knick_knacks_layer_";
	private static final String FACE_TAG = "knick_knacks_face_";
	/** On the interaction: its surface went under a creative player's hand, so it stays where it was. */
	private static final String FLOATING_TAG = "knick_knacks_floating";

	/** Things that stand upright rather than lie: bottles, above all. A tag, so a server or a mod can add its own. */
	public static final TagKey<Item> STANDS = itemTag("stands");
	/** Things the gesture leaves to vanilla, because what they do from the off hand is aimed past the block clicked: a boat. */
	public static final TagKey<Item> KEEPS_VANILLA = itemTag("keeps_vanilla");
	/** The other way: things set down even where vanilla would have used them on the block. */
	public static final TagKey<Item> SETS_DOWN = itemTag("sets_down");

	/** Every knack is half a block across, so four fit on a block and one fits in the middle. */
	private static final double FOOTPRINT = 0.5;
	private static final double HALF = FOOTPRINT / 2;
	private static final double[] GRID = {0.25, 0.5, 0.75};
	private static final int TURNS = 16;
	private static final float VIEW_RANGE = 0.5f;
	private static final float SCALE = 0.5f;

	/** Lying: lifted clear of the surface by less than its own thickness, each layer a little more than one thickness above the last. */
	private static final float LIFT = 0.02f;
	private static final float LAYER_STEP = 0.035f;
	private static final float HEIGHT = 0.125f;
	static final int MAX_LAYERS = 8;

	/** Standing: vanilla's fixed transform draws a picture a block tall at full size, so half size stands a quarter up. */
	private static final float STANDING_LIFT = 0.25f;
	private static final float STANDING_HEIGHT = 0.5f;

	/** Hanging on a wall or from a ceiling: as tall as it is wide, and on a wall as deep off it as an item is thick. */
	private static final float HANGING_HEIGHT = (float) FOOTPRINT;
	private static final float HANGING_DEPTH = 0.0625f;

	/** A little twist of its own on every item, so a table reads as set down by hand rather than laid on a grid. */
	private static final int JITTER_DEGREES = 6;

	static TagKey<Item> itemTag(String path) {
		return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Main.MOD_ID, path));
	}

	/**
	 * Whether the gesture may set this down at all, rather than always leaving the click to vanilla.
	 *
	 * <p>Almost everything may: the gesture lets vanilla go first, so a candle is still placed as a
	 * candle and a poppy is still planted in grass, and only where they would place nothing (a
	 * poppy on a table) are they set down. A bucket's use looks past the block for water to fill
	 * from or a spot to pour into, which vanilla going first cannot see, so it is never set down.
	 */
	public static boolean canSetDown(ItemStack stack) {
		return !stack.is(KEEPS_VANILLA) && !(stack.getItem() instanceof BucketItem);
	}

	static boolean stands(ItemStack stack) {
		return stack.is(STANDS);
	}

	public static boolean isKnack(Entity entity) {
		return entity.entityTags().contains(TAG);
	}

	/** A knack's interaction and its displays, from the bottom of the pile up. */
	record Pile(Interaction handle, List<Display.ItemDisplay> layers) {
		Display.ItemDisplay top() {
			return this.layers.get(this.layers.size() - 1);
		}

		ItemStack topItem() {
			return this.top().getSlot(0).get();
		}

		int size() {
			return this.layers.size();
		}
	}

	static Pile pileOf(ServerLevel level, Interaction handle) {
		Vec3 at = handle.position();
		List<Display.ItemDisplay> layers = new ArrayList<>(level.getEntitiesOfClass(Display.ItemDisplay.class,
			new AABB(at, at).inflate(0.01), shown -> isKnack(shown) && !shown.isRemoved() && shown.position().distanceToSqr(at) < 1.0E-6));
		if (layers.isEmpty()) return null;
		layers.sort(Comparator.comparingInt(Knack::layerOf));
		return new Pile(handle, layers);
	}

	/** Set one of the off hand's stack down where the player clicked, or the nearest free spot on that face. */
	static boolean place(ServerPlayer player, ServerLevel level, BlockHitResult hit) {
		Direction face = hit.getDirection();
		BlockPos pos = hit.getBlockPos();
		if (!level.mayInteract(player, pos)) return false;

		ItemStack held = player.getOffhandItem();
		boolean standing = face == Direction.UP && stands(held);
		float depth = face == Direction.DOWN ? HANGING_HEIGHT : face != Direction.UP ? HANGING_DEPTH : standing ? STANDING_HEIGHT : HEIGHT;
		Vec3 anchor = freeSpot(level, pos, face, hit.getLocation(), depth);
		if (anchor == null) return false;

		Interaction handle = new Interaction(EntityTypes.INTERACTION, level);
		handle.setPos(positionFor(anchor, face));
		handle.setWidth((float) FOOTPRINT);
		handle.setResponse(true);
		handle.addTag(TAG);
		setFace(handle, face);
		fitHeight(handle, 1, standing);
		level.addFreshEntity(handle);
		addLayer(level, handle, held.copyWithCount(1), face.getAxis() == Direction.Axis.Y ? facing(player) : 0, 0);

		if (!player.hasInfiniteMaterials()) held.shrink(1);
		refresh(level, handle);
		level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
		return true;
	}

	/** A click on the face a knack rests on, right where the knack is: what clicking a knack stands for. */
	static BlockHitResult surfaceHit(Pile pile) {
		Direction face = faceOf(pile.handle());
		Vec3 anchor = anchorOf(pile.handle());
		BlockPos surface = BlockPos.containing(anchor.subtract(out(face).scale(0.01)));
		return new BlockHitResult(anchor, face, surface, false);
	}

	/** One more of the same on top of a pile; false if it is not the same thing, does not pile, or the pile is full. */
	static boolean pileOn(ServerPlayer player, ServerLevel level, Pile pile) {
		ItemStack held = player.getOffhandItem();
		if (!level.mayInteract(player, pile.handle().blockPosition())) return false;
		if (faceOf(pile.handle()) != Direction.UP) return false;
		if (stands(held) || held.getMaxStackSize() <= 1 || pile.size() >= MAX_LAYERS) return false;
		if (!ItemStack.isSameItemSameComponents(held, Bites.whole(pile.top()))) return false;

		addLayer(level, pile.handle(), held.copyWithCount(1), turnOf(pile.top()), pile.size());
		fitHeight(pile.handle(), pile.size() + 1, false);
		if (!player.hasInfiniteMaterials()) held.shrink(1);
		Vec3 at = pile.handle().position();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.1F);
		return true;
	}

	/**
	 * The top of the pile back into the hitter's pack, or the whole pile when they are sneaking.
	 * What their pack has no room for lands at their feet; a creative player only gets one they lack.
	 */
	static boolean pickUp(ServerPlayer player, ServerLevel level, Interaction handle) {
		if (!level.mayInteract(player, handle.blockPosition())) return false;
		Pile pile = pileOf(level, handle);
		if (pile == null) {
			handle.discard();
			return true;
		}
		int taking = player.isSecondaryUseActive() ? pile.size() : 1;
		for (int i = 0; i < taking; i++) {
			Display.ItemDisplay shown = pile.layers().get(pile.size() - 1 - i);
			give(player, Bites.whole(shown));
			shown.discard();
		}
		settle(level, handle, pile.size() - taking, pile.layers().get(0).getSlot(0).get());
		level.playSound(null, handle.getX(), handle.getY(), handle.getZ(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
		return true;
	}

	/** Takes one display off its pile without giving it to anyone, for a taker that does its own giving. */
	static void takeOff(ServerLevel level, Interaction handle, Display.ItemDisplay shown) {
		shown.discard();
		Pile pile = pileOf(level, handle);
		settle(level, handle, pile == null ? 0 : pile.size(), pile == null ? ItemStack.EMPTY : pile.layers().get(0).getSlot(0).get());
	}

	/** Takes the whole pile away without giving it to anyone: it was spent, launched or broken. */
	static void clear(ServerLevel level, Pile pile) {
		for (Display.ItemDisplay shown : pile.layers()) shown.discard();
		settle(level, pile.handle(), 0, ItemStack.EMPTY);
	}

	static boolean turn(ServerLevel level, Pile pile) {
		int turn = (turnOf(pile.top()) + 1) % TURNS;
		for (Display.ItemDisplay shown : pile.layers()) {
			setTurn(shown, turn);
			shown.setTransformation(transformation(shown, turn));
		}
		Vec3 at = pile.handle().position();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
		return true;
	}

	/**
	 * Called now and then from the interaction's tick: a knack with nothing behind it falls as items.
	 *
	 * <p>Not one left floating by a creative player's break, so a table can be rebuilt without
	 * clearing it first, and not one glued down. Either way, once something is behind it again it
	 * is an ordinary knack, and goes the next time its surface does.
	 *
	 * <p>Cheap while all is well, because it runs for every knack loaded: a look at the block
	 * behind and nothing else. The pile is only looked up when it is needed, or when asked to make
	 * sure there still is one (its displays killed by a command, say), which is far rarer.
	 */
	public static void checkSupport(ServerLevel level, Interaction handle, boolean lookForPile) {
		if (lookForPile && pileOf(level, handle) == null) {
			handle.discard();
			Aura.relight(level, handle);
			return;
		}
		Aura.keepLit(level, handle);
		if (hasSupport(level, anchorOf(handle), faceOf(handle))) {
			handle.removeTag(FLOATING_TAG);
			return;
		}
		if (handle.entityTags().contains(FLOATING_TAG) || Glue.isGlued(handle)) return;
		Pile pile = pileOf(level, handle);
		if (pile != null) {
			for (Display.ItemDisplay shown : pile.layers()) {
				ItemStack stack = Bites.whole(shown);
				shown.discard();
				if (!stack.isEmpty()) handle.spawnAtLocation(level, stack);
			}
		}
		handle.discard();
		Aura.relight(level, handle);
	}

	/** A creative player broke this block: whatever rests on it stays put rather than falling. */
	public static void letFloat(ServerLevel level, BlockPos pos) {
		AABB block = new AABB(pos).inflate(0.05);
		for (Interaction handle : level.getEntitiesOfClass(Interaction.class, block.inflate(1), Knack::isKnack)) {
			if (block.contains(anchorOf(handle))) handle.addTag(FLOATING_TAG);
		}
	}

	/** What a knack is, for a name: the top of its pile, and how many there are. */
	public static String describe(ServerLevel level, Interaction handle) {
		Pile pile = pileOf(level, handle);
		if (pile == null) return null;
		String name = pile.topItem().getHoverName().getString();
		return pile.size() > 1 ? name + " x" + pile.size() : name;
	}

	/** Change what a display shows, keeping its place in the pile, and draw it the way the new thing is drawn. */
	static void show(ServerLevel level, Pile pile, Display.ItemDisplay shown, ItemStack stack) {
		shown.setItemStack(stack);
		shown.setTransformation(transformation(shown, turnOf(shown)));
		fitHeight(pile.handle(), pile.size(), stands(stack));
		refresh(level, pile.handle());
	}

	/** Bring what the interaction says about its pile up to date with what is on top of it. */
	public static void refresh(ServerLevel level, Interaction handle) {
		Pile pile = handle.isRemoved() ? null : pileOf(level, handle);
		Burst.mark(handle, pile == null ? ItemStack.EMPTY : pile.topItem());
		Aura.mark(handle, pile == null ? ItemStack.EMPTY : pile.topItem());
		Aura.relight(level, handle);
	}

	private static void addLayer(ServerLevel level, Interaction handle, ItemStack stack, int turn, int layer) {
		Display.ItemDisplay shown = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		shown.setPos(handle.position());
		shown.setItemStack(stack);
		shown.setItemTransform(ItemDisplayContext.FIXED);
		shown.setViewRange(VIEW_RANGE);
		shown.addTag(TAG);
		shown.addTag(LAYER_TAG + layer);
		setFace(shown, faceOf(handle));
		setTurn(shown, turn);
		shown.setTransformation(transformation(shown, turn));
		level.addFreshEntity(shown);
	}

	private static Transformation transformation(Display.ItemDisplay shown, int turn) {
		Direction face = faceOf(shown);
		int jitter = Math.floorMod(shown.getUUID().hashCode(), 2 * JITTER_DEGREES + 1) - JITTER_DEGREES;
		float twist = turn * Mth.TWO_PI / TURNS + jitter * Mth.DEG_TO_RAD;

		if (face == Direction.DOWN) {
			// Hanging from a ceiling: upright, facing whoever hung it, its top just under the block.
			return new Transformation(new Vector3f(0, HANGING_HEIGHT / 2, 0), new Quaternionf().rotateY(-twist), new Vector3f(SCALE), new Quaternionf());
		}
		if (face != Direction.UP) {
			// A fixed item faces south as it comes: swing it round to face out from the wall, then
			// turn it about that facing the way a framed item turns.
			Quaternionf rotation = new Quaternionf()
				.rotateY((float) Math.atan2(face.getStepX(), face.getStepZ()))
				.rotateZ(-twist);
			return new Transformation(new Vector3f(0, HANGING_HEIGHT / 2, 0), rotation, new Vector3f(SCALE), new Quaternionf());
		}

		boolean standing = stands(shown.getSlot(0).get());
		Quaternionf rotation = new Quaternionf().rotateY(-twist);
		if (!standing) rotation.rotateX(-Mth.HALF_PI);
		float lift = standing ? STANDING_LIFT : LIFT + layerOf(shown) * LAYER_STEP;
		return new Transformation(new Vector3f(0, lift, 0), rotation, new Vector3f(SCALE), new Quaternionf());
	}

	/** The interaction grows and shrinks with the pile, so its top is what gets hit; with nothing left it goes. */
	private static void settle(ServerLevel level, Interaction handle, int left, ItemStack bottom) {
		if (left <= 0) handle.discard();
		else fitHeight(handle, left, stands(bottom));
		refresh(level, handle);
	}

	private static void fitHeight(Interaction handle, int layers, boolean standing) {
		if (faceOf(handle) != Direction.UP) handle.setHeight(HANGING_HEIGHT);
		else handle.setHeight(standing ? STANDING_HEIGHT : HEIGHT + (layers - 1) * LAYER_STEP);
	}

	static void give(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (player.hasInfiniteMaterials()) {
			if (!player.getInventory().contains(stack)) player.getInventory().add(stack);
		} else {
			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		}
	}

	/** The face a knack rests on: the top of a block unless it says otherwise. */
	static Direction faceOf(Entity entity) {
		for (String tag : entity.entityTags()) {
			if (!tag.startsWith(FACE_TAG)) continue;
			Direction face = Direction.byName(tag.substring(FACE_TAG.length()));
			if (face != null) return face;
		}
		return Direction.UP;
	}

	private static void setFace(Entity entity, Direction face) {
		if (face != Direction.UP) entity.addTag(FACE_TAG + face.getSerializedName());
	}

	/** The point on the face a knack rests on, in the middle of where it rests. */
	static Vec3 anchorOf(Interaction handle) {
		Direction face = faceOf(handle);
		if (face == Direction.UP) return handle.position();
		if (face == Direction.DOWN) return handle.position().add(0, HANGING_HEIGHT, 0);
		return handle.position().add(0, HANGING_HEIGHT / 2, 0).subtract(out(face).scale(LIFT));
	}

	private static Vec3 positionFor(Vec3 anchor, Direction face) {
		if (face == Direction.UP) return anchor;
		if (face == Direction.DOWN) return anchor.subtract(0, HANGING_HEIGHT, 0);
		return anchor.add(out(face).scale(LIFT)).subtract(0, HANGING_HEIGHT / 2, 0);
	}

	static Vec3 out(Direction face) {
		return face.getUnitVec3();
	}

	/**
	 * The clicked spot snapped to the grid on that face if it is free, otherwise the nearest free
	 * one: a corner before any edge or the middle, because a knack nudged to an edge's middle shuts
	 * both corners beside it, and a block meant to take four takes three.
	 *
	 * <p>Free means no other knack on the same face within a footprint, something solid behind it,
	 * room in front of it for what goes there, and no water or lava where it would sit.
	 */
	private static Vec3 freeSpot(ServerLevel level, BlockPos pos, Direction face, Vec3 clicked, float depth) {
		Direction.Axis normal = face.getAxis();
		double plane = clicked.get(normal);

		List<Vec3> spots = new ArrayList<>();
		for (double gu : GRID) for (double gv : GRID) spots.add(gridSpot(pos, normal, plane, gu, gv));
		spots.sort(Comparator.comparingDouble(spot -> spot.distanceToSqr(clicked)));
		Vec3 snapped = spots.get(0);
		spots.sort(Comparator.<Vec3>comparingInt(spot -> spot == snapped ? 0 : isCorner(spot, normal) ? 1 : 2)
			.thenComparingDouble(spot -> spot.distanceToSqr(clicked)));

		AABB around = new AABB(pos).inflate(0.3);
		List<Vec3> taken = new ArrayList<>();
		for (Interaction other : level.getEntitiesOfClass(Interaction.class, around.inflate(1), other -> isKnack(other) && faceOf(other) == face)) {
			Vec3 at = anchorOf(other);
			if (around.contains(at)) taken.add(at);
		}

		for (Vec3 spot : spots) {
			if (overlaps(spot, taken)) continue;
			if (!hasSupport(level, spot, face)) continue;
			if (!level.noCollision(slab(spot, face, 0.001, depth, HALF - 0.01))) continue;
			if (!level.getFluidState(BlockPos.containing(spot.add(out(face).scale(0.05)))).isEmpty()) continue;
			return spot;
		}
		return null;
	}

	/** A point on the plane of a face, at a grid step across the block in each of the face's two directions. */
	private static Vec3 gridSpot(BlockPos pos, Direction.Axis normal, double plane, double gu, double gv) {
		return switch (normal) {
			case X -> new Vec3(plane, pos.getY() + gu, pos.getZ() + gv);
			case Y -> new Vec3(pos.getX() + gu, plane, pos.getZ() + gv);
			case Z -> new Vec3(pos.getX() + gu, pos.getY() + gv, plane);
		};
	}

	private static boolean isCorner(Vec3 spot, Direction.Axis normal) {
		for (Direction.Axis axis : Direction.Axis.values()) {
			if (axis != normal && Math.abs(Mth.frac(spot.get(axis)) - 0.5) < 0.1) return false;
		}
		return true;
	}

	private static boolean overlaps(Vec3 spot, List<Vec3> taken) {
		for (Vec3 other : taken) {
			double apart = Math.max(Math.abs(spot.x - other.x), Math.max(Math.abs(spot.y - other.y), Math.abs(spot.z - other.z)));
			if (apart < FOOTPRINT - 1.0E-4) return true;
		}
		return false;
	}

	/** Whether some block's shape comes up to meet this point from behind the face, the way a cushion asks. */
	private static boolean hasSupport(ServerLevel level, Vec3 anchor, Direction face) {
		AABB probe = slab(anchor, face, -0.02, -0.001, 0.05);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(probe.minX, probe.minY, probe.minZ),
				BlockPos.containing(probe.maxX, probe.maxY, probe.maxZ))) {
			BlockState state = level.getBlockState(pos);
			VoxelShape shape = state.getShape(level, pos);
			if (shape.isEmpty()) continue;
			if (Block.isShapeFullBlock(shape)) return true;
			for (AABB box : shape.toAabbs()) {
				if (box.move(pos).intersects(probe)) return true;
			}
		}
		return false;
	}

	/** A box reaching out from a face between two distances, and a half-width across it. */
	private static AABB slab(Vec3 anchor, Direction face, double from, double to, double half) {
		Vec3 out = out(face);
		Vec3 a = anchor.add(out.scale(from));
		Vec3 b = anchor.add(out.scale(to));
		Vec3 across = new Vec3(half, half, half).subtract(new Vec3(Math.abs(out.x), Math.abs(out.y), Math.abs(out.z)).scale(half));
		return new AABB(a, b).inflate(across.x, across.y, across.z);
	}

	/** The sixteenth of a turn that faces the player, so what is set down reads the right way up to them. */
	private static int facing(ServerPlayer player) {
		return Math.floorMod(Math.round((player.getYRot() + 180.0F) / (360.0F / TURNS)), TURNS);
	}

	static int turnOf(Display.ItemDisplay shown) {
		return Math.floorMod(numberAfter(shown, TURN_TAG), TURNS);
	}

	private static int layerOf(Display.ItemDisplay shown) {
		return numberAfter(shown, LAYER_TAG);
	}

	private static void setTurn(Display.ItemDisplay shown, int turn) {
		shown.entityTags().removeIf(tag -> tag.startsWith(TURN_TAG));
		shown.addTag(TURN_TAG + turn);
	}

	static int numberAfter(Entity entity, String prefix) {
		for (String tag : entity.entityTags()) {
			if (!tag.startsWith(prefix)) continue;
			try {
				return Integer.parseInt(tag.substring(prefix.length()));
			} catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return 0;
	}
}
