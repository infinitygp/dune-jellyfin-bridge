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
	public void stoppedPlaybackIsInactiveEvenWithStalePosition() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_state\":\"stopped\","
			+ "\"playback_url\":\"/mnt/media/episode.mkv\","
			+ "\"playback_position\":123,"
			+ "\"playback_duration\":456"
			+ "}");

		assertFalse(status.isPlaybackActive());
		assertFalse(status.isReadyForSeek());
		assertFalse(status.matchesMedia("episode.mkv", null, null));
	}

	@Test
	public void hiddenPlaybackIsNotReadyForSeek() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_state\":\"paused\","
			+ "\"playback_position\":123,"
			+ "\"playback_duration\":456,"
			+ "\"video_enabled\":\"0\","
			+ "\"playback_window_fullscreen\":\"0\""
			+ "}");

		assertTrue(status.isPlaybackActive());
		assertFalse(status.isPlaybackVisible());
		assertFalse(status.isReadyForSeek());
	}

	@Test
	public void visiblePlaybackIsReadyForSeek() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_position\":123,"
			+ "\"playback_duration\":456,"
			+ "\"video_enabled\":1,"
			+ "\"playback_window_fullscreen\":0"
			+ "}");

		assertTrue(status.isPlaybackVisible());
		assertTrue(status.isReadyForSeek());
	}

	@Test
	public void nativeDunePlaybackIsVisibleDespiteDisabledAndroidVideoFlags() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"android_app_active\":\"0\","
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_state\":\"playing\","
			+ "\"playback_position\":123,"
			+ "\"playback_duration\":456,"
			+ "\"video_enabled\":\"0\","
			+ "\"playback_window_fullscreen\":\"0\""
			+ "}");

		assertTrue(status.isPlaybackVisible());
		assertTrue(status.isReadyForSeek());
	}

	@Test
	public void matchingPlaybackBehindAndroidAppIsNotVisible() throws Exception {
		DuneStatus status = DuneStatus.parse("{"
			+ "\"android_app_active\":\"1\","
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_state\":\"paused\","
			+ "\"playback_position\":123,"
			+ "\"playback_duration\":456"
			+ "}");

		assertFalse(status.isPlaybackVisible());
		assertFalse(status.isReadyForSeek());
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
