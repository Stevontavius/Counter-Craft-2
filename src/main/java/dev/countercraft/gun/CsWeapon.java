package dev.countercraft.gun;

/** Any Counter-Craft weapon item (gun, knife, grenade): its id names its converted viewmodel and icon. */
public interface CsWeapon {
	String weaponId();

	/** CS2 m_flDeployDuration: seconds after switching to it before the weapon can be used (knives, grenades: 1.0). */
	default float deploySeconds() {
		return 1.0f;
	}
}
