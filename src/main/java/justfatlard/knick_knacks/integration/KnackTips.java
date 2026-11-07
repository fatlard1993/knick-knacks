package justfatlard.knick_knacks.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.knick_knacks.Glue;
import justfatlard.knick_knacks.Knack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;

/**
 * Naming what is set down by what it is. The card would otherwise find the interaction a knack is
 * hit through and call it an Interaction.
 *
 * <p>Compiled against block-tip's API and guarded at the call site by a mod-loaded check, so a
 * server without it never loads this class.
 */
public final class KnackTips {
	private KnackTips() {}

	public static void register() {
		BlockTipApi.nameEntity(KnackTips::name);
		BlockTipApi.describeEntity(KnackTips::glue);
	}

	/** Glue is invisible: the card says whose it is before anybody stands there swinging, in Kragle's words. */
	private static String glue(Entity entity, ServerPlayer player) {
		if (!(entity instanceof Interaction handle) || !Knack.isKnack(handle)) return null;
		String by = Glue.gluedBy(handle);
		return by == null ? null : "Kragled by " + by;
	}

	private static String name(Entity entity, ServerPlayer player) {
		if (!(entity instanceof Interaction handle) || !Knack.isKnack(handle)) return null;
		return Knack.describe(player.level(), handle);
	}
}
