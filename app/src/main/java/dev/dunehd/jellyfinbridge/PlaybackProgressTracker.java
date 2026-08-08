package dev.dunehd.jellyfinbridge;

final class PlaybackProgressTracker {
	private static final long SEEK_CONFIRMATION_TOLERANCE_MILLIS = 5_000L;
	private static final long SEEK_RETRY_INTERVAL_MILLIS = 2_000L;
	private static final long SEEK_STABILITY_WINDOW_MILLIS = 15_000L;
	private static final int REQUIRED_CONFIRMATION_POLLS = 3;
	private static final int MAX_SEEK_ATTEMPTS = 10;

	private final long initialPositionMillis;
	private final String expectedFileName;
	private final String expectedTitle;
	private final String expectedUrl;

	private long lastPositionMillis;
	private long lastSeekAttemptMillis;
	private long seekConfirmedMillis;
	private int seekAttempts;
	private int confirmationPolls;
	private boolean playbackObserved;
	private boolean initialSeekConfirmed;
	private boolean giveUpReported;

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
		this.initialSeekConfirmed = this.initialPositionMillis == 0;
	}

	synchronized Update onStatus(DuneStatus status, long elapsedRealtimeMillis) {
		if (!status.matchesMedia(expectedFileName, expectedTitle, expectedUrl)) return Update.none();

		playbackObserved = true;
		Long positionMillis = status.getPlaybackPositionMillis();
		if (initialSeekConfirmed) {
			if (!positionResetDuringStabilityWindow(positionMillis, elapsedRealtimeMillis)) {
				if (positionMillis != null) lastPositionMillis = positionMillis;
				return Update.none();
			}
			initialSeekConfirmed = false;
			confirmationPolls = 0;
		}

		if (isNearInitialPosition(positionMillis)) {
			confirmationPolls++;
			if (confirmationPolls >= REQUIRED_CONFIRMATION_POLLS) {
				initialSeekConfirmed = true;
				seekConfirmedMillis = elapsedRealtimeMillis;
				lastPositionMillis = positionMillis;
				return Update.confirmed();
			}
			return Update.none();
		}

		confirmationPolls = 0;
		if (!status.isReadyForSeek()) return Update.none();
		if (seekAttempts >= MAX_SEEK_ATTEMPTS) {
			if (giveUpReported) return Update.none();
			giveUpReported = true;
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

	private boolean positionResetDuringStabilityWindow(Long positionMillis, long elapsedRealtimeMillis) {
		return initialPositionMillis > 0
			&& positionMillis != null
			&& elapsedRealtimeMillis - seekConfirmedMillis <= SEEK_STABILITY_WINDOW_MILLIS
			&& positionMillis < initialPositionMillis - SEEK_CONFIRMATION_TOLERANCE_MILLIS;
	}

	enum Action {
		NONE,
		SEEK,
		CONFIRMED,
		GAVE_UP,
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

		static Update gaveUp() {
			return new Update(Action.GAVE_UP, 0, 0);
		}
	}
}
