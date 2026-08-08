package dev.dunehd.jellyfinbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackProgressTrackerTest {
	private static final String FILE_NAME = "movie.mkv";
	private static final String TITLE = "Movie";
	private static final long INITIAL_POSITION = 3_600_000L;

	@Test
	public void ignoresStatusFromPreviouslyPlayingMedia() throws Exception {
		PlaybackProgressTracker tracker = tracker();

		PlaybackProgressTracker.Update update = tracker.onStatus(
			status("/mnt/other.mkv", "Other", 500, 7_200),
			0
		);

		assertEquals(PlaybackProgressTracker.Action.NONE, update.action);
		assertFalse(tracker.isPlaybackObserved());
		assertEquals(INITIAL_POSITION, tracker.getLastPositionMillis());
	}

	@Test
	public void retriesSeekUntilPositionIsConfirmed() throws Exception {
		PlaybackProgressTracker tracker = tracker();
		DuneStatus atStart = status("/mnt/movie.mkv", TITLE, 0, 7_200);

		PlaybackProgressTracker.Update first = tracker.onStatus(atStart, 0);
		PlaybackProgressTracker.Update tooSoon = tracker.onStatus(atStart, 1_000);
		PlaybackProgressTracker.Update retry = tracker.onStatus(atStart, 2_000);

		assertEquals(PlaybackProgressTracker.Action.SEEK, first.action);
		assertEquals(1, first.seekAttempt);
		assertEquals(PlaybackProgressTracker.Action.NONE, tooSoon.action);
		assertEquals(PlaybackProgressTracker.Action.SEEK, retry.action);
		assertEquals(2, retry.seekAttempt);
		assertEquals(INITIAL_POSITION, tracker.getLastPositionMillis());
		assertFalse(tracker.isInitialSeekConfirmed());

		assertEquals(
			PlaybackProgressTracker.Action.NONE,
			tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_600, 7_200), 3_000).action
		);
		assertEquals(
			PlaybackProgressTracker.Action.NONE,
			tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_601, 7_200), 4_000).action
		);
		assertEquals(
			PlaybackProgressTracker.Action.CONFIRMED,
			tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_602, 7_200), 5_000).action
		);
		assertTrue(tracker.isInitialSeekConfirmed());
		assertEquals(3_602_000L, tracker.getLastPositionMillis());

		tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_610, 7_200), 6_000);
		assertEquals(3_610_000L, tracker.getLastPositionMillis());
	}

	@Test
	public void waitsForDurationBeforeRequestingSeek() throws Exception {
		PlaybackProgressTracker tracker = tracker();

		PlaybackProgressTracker.Update update = tracker.onStatus(
			statusWithoutDuration("/mnt/movie.mkv", TITLE, 0),
			0
		);

		assertEquals(PlaybackProgressTracker.Action.NONE, update.action);
		assertTrue(tracker.isPlaybackObserved());
		assertEquals(INITIAL_POSITION, tracker.getLastPositionMillis());
	}

	@Test
	public void retriesWhenPositionResetsSoonAfterConfirmation() throws Exception {
		PlaybackProgressTracker tracker = tracker();
		tracker.onStatus(status("/mnt/movie.mkv", TITLE, 0, 7_200), 0);
		tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_600, 7_200), 1_000);
		tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_601, 7_200), 2_000);
		assertEquals(
			PlaybackProgressTracker.Action.CONFIRMED,
			tracker.onStatus(status("/mnt/movie.mkv", TITLE, 3_602, 7_200), 3_000).action
		);

		PlaybackProgressTracker.Update reset = tracker.onStatus(
			status("/mnt/movie.mkv", TITLE, 0, 7_200),
			5_000
		);

		assertEquals(PlaybackProgressTracker.Action.SEEK, reset.action);
		assertEquals(2, reset.seekAttempt);
		assertFalse(tracker.isInitialSeekConfirmed());
		assertEquals(3_602_000L, tracker.getLastPositionMillis());
	}

	private static PlaybackProgressTracker tracker() {
		return new PlaybackProgressTracker(
			INITIAL_POSITION,
			FILE_NAME,
			TITLE,
			"nfs-tcp://nas:/media:/movie.mkv"
		);
	}

	private static DuneStatus status(String url, String title, long position, long duration) throws Exception {
		return DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_url\":\"" + url + "\","
			+ "\"playback_caption\":\"" + title + "\","
			+ "\"playback_position\":" + position + ","
			+ "\"playback_duration\":" + duration
			+ "}");
	}

	private static DuneStatus statusWithoutDuration(String url, String title, long position) throws Exception {
		return DuneStatus.parse("{"
			+ "\"player_state\":\"file_playback\","
			+ "\"playback_url\":\"" + url + "\","
			+ "\"playback_caption\":\"" + title + "\","
			+ "\"playback_position\":" + position
			+ "}");
	}
}
