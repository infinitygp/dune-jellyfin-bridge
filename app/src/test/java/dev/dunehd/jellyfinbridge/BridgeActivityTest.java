package dev.dunehd.jellyfinbridge;

import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BridgeActivityTest {
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
