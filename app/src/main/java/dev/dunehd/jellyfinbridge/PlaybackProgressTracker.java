package dev.dunehd.jellyfinbridge;

final class PlaybackProgressTracker {
	private static final long SEEK_CONFIRMATION_TOLERANCE_MILLIS = 5_000L;
	private static final long SEEK_RETRY_INTERVAL_MILLIS = 2_000L;
	private static final long SEEK_STABILITY_WINDOW_MILLIS = 15_000L;
	private static final long STARTUP_RESET_MAX_POSITION_MILLIS = 15_000L;
	private static final long MIN_USER_OVERRIDE_POSITION_MILLIS = 30_000L;
	private static final int MAX_SEEK_ATTEMPTS = 10;
	private static final long PLAYBACK_END_CONFIRMATION_MILLIS = 1_000L;

	private final long initialPositionMillis;
	private final String expectedFileName;
	private final String expectedTitle;
	private final String expectedUrl;
	private final boolean startupResetExpected;

	private long lastPositionMillis;
	private long lastSeekAttemptMillis;
	private long seekConfirmedMillis = -1;
	private int seekAttempts;
	private long unmatchedSinceMillis = -1;
	private boolean playbackObserved;
	private boolean initialSeekConfirmed;
	private boolean playbackEndReported;

	PlaybackProgressTracker(
		long initialPositionMillis,
		String expectedFileName,
		String expectedTitle,
		String expectedUrl
	) {
		this.initialPositionMillis = Math.max(0, initialPositionMillis);
		this.lastPositionMillis = this.initialPositionMillis;
		this.expectedFileName = NfsMediaResolver.baseName(expectedFileName);
		this.expectedTitle = expectedTitle;
		this.expectedUrl = expectedUrl;
		// shortcut: a confirmed seek was never seen to reset over NFS (10 starts, 45 GB remux
		// included), so only streams are guarded; extend this if a reset shows up elsewhere.
		this.startupResetExpected = expectedUrl != null
			&& (expectedUrl.startsWith("http://") || expectedUrl.startsWith("https://"));
		this.initialSeekConfirmed = this.initialPositionMillis == 0;
	}

	synchronized Update onStatus(DuneStatus status, long elapsedRealtimeMillis) {
		if (!status.matchesMedia(expectedFileName, expectedTitle, expectedUrl)) {
			return onUnmatchedStatus(elapsedRealtimeMillis);
		}

		unmatchedSinceMillis = -1;
		if (status.isPlaybackVisible()) playbackObserved = true;
		if (!playbackObserved) return Update.none();

		Long positionMillis = status.getPlaybackPositionMillis();
		Update update = initialSeekConfirmed
			&& !positionResetDuringStabilityWindow(positionMillis, elapsedRealtimeMillis)
			? Update.none()
			: applyInitialSeek(status, positionMillis, elapsedRealtimeMillis);
		// Dune reports positions near zero until the resume seek settles; returning
		// one of those would erase the resume point stored by Jellyfin.
		if (positionMillis != null && (initialSeekConfirmed || positionMillis > lastPositionMillis)) {
			lastPositionMillis = positionMillis;
		}
		return update;
	}

	private Update applyInitialSeek(DuneStatus status, Long positionMillis, long elapsedRealtimeMillis) {
		initialSeekConfirmed = false;
		seekConfirmedMillis = -1;
		if (!status.isReadyForSeek()) return Update.none();

		if (isNearInitialPosition(positionMillis)) {
			initialSeekConfirmed = true;
			seekConfirmedMillis = elapsedRealtimeMillis;
			return Update.confirmed();
		}
		if (isLikelyUserSeek(positionMillis)) {
			initialSeekConfirmed = true;
			seekConfirmedMillis = -1;
			return Update.userOverride();
		}

		if (seekAttempts >= MAX_SEEK_ATTEMPTS) {
			initialSeekConfirmed = true;
			return Update.gaveUp();
		}
		if (seekAttempts > 0
			&& elapsedRealtimeMillis - lastSeekAttemptMillis < SEEK_RETRY_INTERVAL_MILLIS) {
			return Update.none();
		}

		seekAttempts++;
		lastSeekAttemptMillis = elapsedRealtimeMillis;
		return Update.seek(initialPositionMillis / 1_000L, seekAttempts);
	}

	private Update onUnmatchedStatus(long elapsedRealtimeMillis) {
		if (!playbackObserved || playbackEndReported) return Update.none();

		if (unmatchedSinceMillis < 0) unmatchedSinceMillis = elapsedRealtimeMillis;
		if (elapsedRealtimeMillis - unmatchedSinceMillis < PLAYBACK_END_CONFIRMATION_MILLIS) return Update.none();

		playbackEndReported = true;
		return Update.playbackEnded();
	}

	synchronized long getLastPositionMillis() {
		return lastPositionMillis;
	}

	synchronized boolean isPlaybackObserved() {
		return playbackObserved;
	}

	synchronized boolean isInitialSeekConfirmed() {
		return initialSeekConfirmed;
	}

	private boolean isNearInitialPosition(Long positionMillis) {
		return positionMillis != null
			&& Math.abs(positionMillis - initialPositionMillis) <= SEEK_CONFIRMATION_TOLERANCE_MILLIS;
	}

	private boolean isLikelyUserSeek(Long positionMillis) {
		return seekAttempts > 0
			&& positionMillis != null
			&& positionMillis >= MIN_USER_OVERRIDE_POSITION_MILLIS;
	}

	// shortcut: a user jumping back to the start within the window is indistinguishable
	// from Dune's startup reset and gets re-seeked, shorten the window if that annoys.
	private boolean positionResetDuringStabilityWindow(Long positionMillis, long elapsedRealtimeMillis) {
		return startupResetExpected
			&& initialPositionMillis > MIN_USER_OVERRIDE_POSITION_MILLIS
			&& seekConfirmedMillis >= 0
			&& positionMillis != null
			&& positionMillis <= STARTUP_RESET_MAX_POSITION_MILLIS
			&& elapsedRealtimeMillis - seekConfirmedMillis <= SEEK_STABILITY_WINDOW_MILLIS;
	}

	enum Action {
		NONE,
		SEEK,
		CONFIRMED,
		USER_OVERRIDE,
		GAVE_UP,
		PLAYBACK_ENDED,
	}

	static final class Update {
		final Action action;
		final long seekPositionSeconds;
		final int seekAttempt;

		private Update(Action action, long seekPositionSeconds, int seekAttempt) {
			this.action = action;
			this.seekPositionSeconds = seekPositionSeconds;
			this.seekAttempt = seekAttempt;
		}

		static Update none() {
			return new Update(Action.NONE, 0, 0);
		}

		static Update seek(long positionSeconds, int attempt) {
			return new Update(Action.SEEK, positionSeconds, attempt);
		}

		static Update confirmed() {
			return new Update(Action.CONFIRMED, 0, 0);
		}

		static Update userOverride() {
			return new Update(Action.USER_OVERRIDE, 0, 0);
		}

		static Update gaveUp() {
			return new Update(Action.GAVE_UP, 0, 0);
		}

		static Update playbackEnded() {
			return new Update(Action.PLAYBACK_ENDED, 0, 0);
		}
	}
}
