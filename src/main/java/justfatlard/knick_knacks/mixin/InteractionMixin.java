package justfatlard.knick_knacks.mixin;

import justfatlard.knick_knacks.Aura;
import justfatlard.knick_knacks.Burst;
import justfatlard.knick_knacks.Knack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A knack's heartbeat, on the interaction it is hit through: a rocket's fuse every other tick, motes
 * now and then, and once a second whether it still has something to rest on. Vanilla's
 * interaction does nothing on its tick, so all of this is added, and none of it runs for an
 * interaction that is not a knack.
 */
@Mixin(Interaction.class)
public abstract class InteractionMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void knickKnacks$tick(CallbackInfo ci) {
		Interaction self = (Interaction) (Object) this;
		if (self.isRemoved() || !(self.level() instanceof ServerLevel level) || !Knack.isKnack(self)) return;
		// Offset by the entity's id, so knacks placed or loaded together do not all do their
		// once-a-second work on the same tick.
		int beat = self.tickCount + self.getId();
		if (beat % 2 == 0) Burst.watchFuse(level, self);
		if (self.isRemoved()) return;
		if (beat % 10 == 0) Aura.drift(level, self);
		if (beat % 20 == 0) Knack.checkSupport(level, self, beat % 200 == 0);
	}

	/** Vanilla's interactions are never hit by projectiles; a bottle set down is. */
	@Inject(method = "canBeHitByProjectile", at = @At("HEAD"), cancellable = true)
	private void knickKnacks$brittle(CallbackInfoReturnable<Boolean> cir) {
		if (Burst.isBrittle((Interaction) (Object) this)) cir.setReturnValue(true);
	}
}
