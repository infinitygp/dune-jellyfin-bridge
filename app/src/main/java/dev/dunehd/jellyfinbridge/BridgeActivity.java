package dev.dunehd.jellyfinbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BridgeActivity extends Activity {
	private static final String TAG = "DuneJellyfinBridge";
	private static final String DUNE_PACKAGE = "com.dunehd.app";
	private static final String DUNE_PLAYER_ACTIVITY = "com.dunehd.shell.PlayerProxyActivity";
	private static final String EXTRA_DUNE_PARAMS = "com.dunehd.playback.dune_params";
	private static final String EXTRA_FILENAME = "filename";
	private static final String EXTRA_POSITION = "position";
	private static final String EXTRA_SUBTITLES = "subs";
	private static final String EXTRA_VLC_SUBTITLES = "subtitles_location";
	private static final String EXTRA_VLC_POSITION = "extra_position";
	private static final String DUNE_SKIP_RESUME_POSITION = "skip_resume_position:1";
	private static final long SEEK_TOLERANCE_MILLIS = 2_000L;
	private static final long RESUME_FALLBACK_DELAY_MILLIS = 1_000L;

	private final AtomicBoolean resultDelivered = new AtomicBoolean();
	private final DuneStatusClient statusClient = new DuneStatusClient();
	private final NfsMediaResolver nfsMediaResolver = new NfsMediaResolver();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private volatile long initialPositionMillis;
	private volatile long lastPositionMillis;
	private volatile boolean playbackObserved;
	private boolean initialSeekApplied;
	private boolean playerLaunchRequested;
	private boolean leftForPlayer;

	private final ScheduledExecutorService statusPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "dune-status-poller");
		thread.setDaemon(true);
		return thread;
	});
	private final ScheduledExecutorService mediaResolver = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "dune-media-resolver");
		thread.setDaemon(true);
		return thread;
	});

	private final Runnable resumeFallback = () -> {
		if (playerLaunchRequested && hasWindowFocus()) deliverResult();
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		initialPositionMillis = readInitialPositionMillis(getIntent());
		lastPositionMillis = initialPositionMillis;
		if (getIntent().getData() == null) {
			Log.e(TAG, "Missing media URL");
			cancelAndFinish();
			return;
		}

		resolveMediaAndLaunchPlayer();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (!playerLaunchRequested) return;

		if (leftForPlayer || playbackObserved) {
			deliverResult();
		} else {
			mainHandler.postDelayed(resumeFallback, RESUME_FALLBACK_DELAY_MILLIS);
		}
	}

	@Override
	protected void onPause() {
		mainHandler.removeCallbacks(resumeFallback);
		if (playerLaunchRequested) leftForPlayer = true;
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		mainHandler.removeCallbacks(resumeFallback);
		mediaResolver.shutdownNow();
		statusPoller.shutdownNow();
		super.onDestroy();
	}

	private void resolveMediaAndLaunchPlayer() {
		Intent incomingIntent = getIntent();
		if (!hasExternalSubtitles(incomingIntent)) {
			launchDunePlayer(null);
			return;
		}

		String mediaUrl = incomingIntent.getDataString();
		String fileName = incomingIntent.getStringExtra(EXTRA_FILENAME);
		mediaResolver.execute(() -> {
			String nfsUrl = null;
			try {
				nfsUrl = nfsMediaResolver.resolve(mediaUrl, fileName);
			} catch (RuntimeException error) {
				Log.w(TAG, "NFS media resolution failed; using Jellyfin stream", error);
			}
			String resolvedNfsUrl = nfsUrl;
			mainHandler.post(() -> {
				if (!isFinishing() && !isDestroyed()) {
					launchDunePlayer(resolvedNfsUrl == null ? null : Uri.parse(resolvedNfsUrl));
				}
			});
		});
	}

	private void launchDunePlayer(Uri replacementUri) {
		Intent duneIntent = new Intent(getIntent());
		duneIntent.setComponent(new ComponentName(DUNE_PACKAGE, DUNE_PLAYER_ACTIVITY));
		duneIntent.setFlags(flagsForDunePlayer(duneIntent.getFlags()));
		if (replacementUri != null) duneIntent.setDataAndType(replacementUri, duneIntent.getType());
		addDuneParam(duneIntent, DUNE_SKIP_RESUME_POSITION);

		try {
			playerLaunchRequested = true;
			Log.i(TAG, "Launching Dune player for " + describeDestination(duneIntent));
			startActivity(duneIntent);
			statusPoller.scheduleWithFixedDelay(this::pollStatus, 0, 1, TimeUnit.SECONDS);
		} catch (ActivityNotFoundException | SecurityException error) {
			Log.e(TAG, "Unable to launch Dune player", error);
			cancelAndFinish();
		}
	}

	private void pollStatus() {
		try {
			DuneStatus status = statusClient.fetch();
			Long positionMillis = status.getPlaybackPositionMillis();
			if (positionMillis != null) lastPositionMillis = positionMillis;
			if (status.isPlaybackActive()) {
				playbackObserved = true;
				applyInitialPosition(status);
			}
		} catch (Exception error) {
			Log.d(TAG, "Dune status poll failed", error);
		}
	}

	private void applyInitialPosition(DuneStatus status) {
		if (initialSeekApplied || initialPositionMillis <= 0) return;

		Long currentPositionMillis = status.getPlaybackPositionMillis();
		if (currentPositionMillis != null
			&& Math.abs(currentPositionMillis - initialPositionMillis) <= SEEK_TOLERANCE_MILLIS) {
			initialSeekApplied = true;
			return;
		}

		try {
			long positionSeconds = initialPositionMillis / 1_000L;
			statusClient.seekToSeconds(positionSeconds);
			initialSeekApplied = true;
			lastPositionMillis = initialPositionMillis;
			Log.i(TAG, "Applied initial playback position " + positionSeconds + " s");
		} catch (Exception error) {
			Log.d(TAG, "Initial playback seek failed; will retry", error);
		}
	}

	private void deliverResult() {
		if (!resultDelivered.compareAndSet(false, true)) return;

		long positionMillis = Math.max(0, lastPositionMillis);
		Intent result = new Intent()
			.putExtra(EXTRA_POSITION, positionMillis)
			.putExtra(EXTRA_VLC_POSITION, positionMillis);
		Log.i(TAG, "Returning playback position " + positionMillis + " ms");
		setResult(RESULT_OK, result);
		finish();
	}

	private void cancelAndFinish() {
		if (!resultDelivered.compareAndSet(false, true)) return;
		setResult(RESULT_CANCELED);
		finish();
	}

	private static long readInitialPositionMillis(Intent intent) {
		return Math.max(0, intent.getIntExtra(EXTRA_POSITION, 0));
	}

	private static boolean hasExternalSubtitles(Intent intent) {
		String[] subtitles = intent.getStringArrayExtra(EXTRA_SUBTITLES);
		if (subtitles != null && subtitles.length > 0) return true;

		String vlcSubtitle = intent.getStringExtra(EXTRA_VLC_SUBTITLES);
		return vlcSubtitle != null && !vlcSubtitle.isEmpty();
	}

	static int flagsForDunePlayer(int flags) {
		return flags & ~(Intent.FLAG_ACTIVITY_NEW_TASK
			| Intent.FLAG_ACTIVITY_FORWARD_RESULT
			| Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
	}

	private static void addDuneParam(Intent intent, String param) {
		String existing = intent.getStringExtra(EXTRA_DUNE_PARAMS);
		if (existing == null || existing.isEmpty()) {
			intent.putExtra(EXTRA_DUNE_PARAMS, param);
		} else if (!existing.contains(param)) {
			intent.putExtra(EXTRA_DUNE_PARAMS, existing + "," + param);
		}
	}

	private static String describeDestination(Intent intent) {
		if (intent.getData() == null) return "<missing URL>";

		String scheme = intent.getData().getScheme();
		String host = intent.getData().getHost();
		return (scheme == null ? "unknown" : scheme) + "://" + (host == null ? "local" : host);
	}
}
