package dev.countercraft.gun;

/**
 * CS2 inaccuracy inputs from weapons.vdata. Pairs are {normal, alternate}: the alternate value applies while
 * scoped, in burst mode, with the silencer removed or for the R8's fan fire. Inaccuracy and spread are
 * tangent-space radii: a shot lands within that cone around the aim.
 *
 * @param spread          m_flSpread: always-on spread
 * @param standing        m_flInaccuracyStand
 * @param crouching       m_flInaccuracyCrouch
 * @param moving          m_flInaccuracyMove: added in full at max speed
 * @param jumping         m_flInaccuracyJump: added while airborne
 * @param firing          m_flInaccuracyFire: added per shot, then recovers
 * @param recoveryStand   m_flRecoveryTimeStand, seconds (first bullets)
 * @param recoveryCrouch  m_flRecoveryTimeCrouch
 * @param recoveryStandFinal  m_flRecoveryTimeStandFinal (sustained fire)
 * @param recoveryCrouchFinal m_flRecoveryTimeCrouchFinal
 * @param transitionStart m_nRecoveryTransitionStartBullet
 * @param transitionEnd   m_nRecoveryTransitionEndBullet
 * @param maxSpeed        m_flMaxSpeed, Hammer units per second
 * @param ladder          m_flInaccuracyLadder: added on a ladder
 * @param landing         m_flInaccuracyLand: x the fall speed (units/s), added on touchdown
 * @param jumpInitial     m_flInaccuracyJumpInitial: in the air, x how fast the player rises or falls
 */
public record AccuracySpec(
		float[] spread,
		float[] standing,
		float[] crouching,
		float[] moving,
		float[] jumping,
		float[] firing,
		float recoveryStand,
		float recoveryCrouch,
		float recoveryStandFinal,
		float recoveryCrouchFinal,
		int transitionStart,
		int transitionEnd,
		float[] maxSpeed,
		float[] ladder,
		float[] landing,
		float jumpInitial) {

	public static float pick(float[] pair, boolean alt) {
		return pair[alt && pair.length > 1 ? 1 : 0];
	}
}
