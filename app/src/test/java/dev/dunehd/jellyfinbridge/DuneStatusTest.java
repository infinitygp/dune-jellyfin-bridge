package dev.dunehd.jellyfinbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DuneStatusTest {
	@Test
	public void parsesPlaybackStatusWithStringValues() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"command_status\":\"ok\","
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_position\":\"123\","
			+ "\"playback_duration\":\"456\""
			+ "}");

		assertEquals("file_playback", status.getPlayerState());
		assertEquals("ok", status.getCommandStatus());
		assertTrue(status.isCommandAccepted());
		assertEquals(Long.valueOf(123_000), status.getPlaybackPositionMillis());
		assertEquals(Long.valueOf(456_000), status.getPlaybackDurationMillis());
		assertTrue(status.isPlaybackActive());
	}

	@Test
	public void parsesNumericValues() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"player_state\":\"bluray_playback\","
			+ "\"playback_position\":12,"
			+ "\"playback_duration\":34"
			+ "}");

		assertEquals(Long.valueOf(12_000), status.getPlaybackPositionMillis());
		assertEquals(Long.valueOf(34_000), status.getPlaybackDurationMillis());
		assertTrue(status.isPlaybackActive());
	}

	@Test
	public void navigatorStatusHasNoPlaybackPosition() throws Exception {
		DuneStatus status = DuneStatus.parse("{\"player_state\":\"navigator\"}");

		assertNull(status.getPlaybackPositionMillis());
		assertNull(status.getPlaybackDurationMillis());
		assertFalse(status.isPlaybackActive());
	}

	@Test
	public void ignoresUnknownAndNegativePositions() throws Exception {
		DuneStatus unknown = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_position\":\"unknown\""
			+ "}");
		DuneStatus negative = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_position\":\"-1\""
			+ "}");

		assertNull(unknown.getPlaybackPositionMillis());
		assertNull(negative.getPlaybackPositionMillis());
		assertTrue(unknown.isPlaybackActive());
		assertTrue(negative.isPlaybackActive());
	}

	@Test
	public void acceptsTimeoutBecauseDuneContinuesExecutingTheCommand() throws Exception {
		DuneStatus status = DuneStatus.parse("{\"command_status\":\"timeout\"}");

		assertTrue(status.isCommandAccepted());
	}

	@Test
	public void rejectsFailedCommand() throws Exception {
		DuneStatus status = DuneStatus.parse("{\"command_status\":\"failed\"}");

		assertFalse(status.isCommandAccepted());
	}
}
