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
			+ "\"playback_url\":\"/mnt/media/episode.mkv\","
			+ "\"playback_caption\":\"Episode\","
			+ "\"playback_position\":\"123\","
			+ "\"playback_duration\":\"456\""
			+ "}");

		assertEquals("file_playback", status.getPlayerState());
		assertEquals("ok", status.getCommandStatus());
		assertTrue(status.isCommandAccepted());
		assertEquals(Long.valueOf(123_000), status.getPlaybackPositionMillis());
		assertEquals(Long.valueOf(456_000), status.getPlaybackDurationMillis());
		assertTrue(status.isPlaybackActive());
		assertTrue(status.isReadyForSeek());
		assertTrue(status.matchesMedia("episode.mkv", "Episode", null));
		assertFalse(status.matchesMedia("other.mkv", "Other", null));
	}

	@Test
	public void matchesHttpPlaybackByTitleOrExactUrl() throws Exception {
		String url = "http://jellyfin/Videos/id/stream?static=true";
		DuneStatus status = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_url\":\"" + url + "\","
			+ "\"playback_caption\":\"Movie\","
			+ "\"playback_position\":0,"
			+ "\"playback_duration\":100"
			+ "}");

		assertTrue(status.matchesMedia("movie.mkv", "Movie", null));
		assertTrue(status.matchesMedia("movie.mkv", null, url));
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
