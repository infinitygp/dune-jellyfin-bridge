package dev.dunehd.jellyfinbridge;

import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BridgeActivityTest {
	@Test
	public void keepsBridgeAliveDuringPlayerLaunchGracePeriod() {
		assertEquals(3_000, BridgeActivity.remainingLaunchGraceMillis(10_000, 12_000));
		assertEquals(0, BridgeActivity.remainingLaunchGraceMillis(10_000, 15_000));
	}

	@Test
	public void requiresOverlayPermissionForRestrictedBackgroundLaunches() {
		assertFalse(BridgeActivity.needsOverlayPermission(28, false));
		assertTrue(BridgeActivity.needsOverlayPermission(29, false));
		assertFalse(BridgeActivity.needsOverlayPermission(29, true));
	}

	@Test
	public void removesActivityResultRoutingFlagsBeforeLaunchingDune() {
		int preserved = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
		int incoming = preserved
			| Intent.FLAG_ACTIVITY_NEW_TASK
			| Intent.FLAG_ACTIVITY_FORWARD_RESULT
			| Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP;

		assertEquals(preserved, BridgeActivity.flagsForDunePlayer(incoming));
	}
}
