package dev.dunehd.jellyfinbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

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
	private static final String EXTRA_TITLE = "title";
	private static final String EXTRA_VLC_POSITION = "extra_position";
	private static final String DUNE_SKIP_RESUME_POSITION = "skip_resume_position:1";
	private static final long RESUME_FALLBACK_DELAY_MILLIS = 1_000L;
	private static final long PLAYER_LAUNCH_GRACE_MILLIS = 5_000L;

	private final AtomicBoolean resultDelivered = new AtomicBoolean();
	private final DuneStatusClient statusClient = new DuneStatusClient();
	private final NfsMediaResolver nfsMediaResolver = new NfsMediaResolver();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private volatile long initialPositionMillis;
	private volatile PlaybackProgressTracker progressTracker;
	private volatile long playerLaunchElapsedRealtime;
	private boolean playerLaunchRequested;

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

		boolean canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
			|| Settings.canDrawOverlays(this);
		if (needsOverlayPermission(Build.VERSION.SDK_INT, canDrawOverlays)) {
			requestOverlayPermission();
			return;
		}

		initialPositionMillis = readInitialPositionMillis(getIntent());
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

		PlaybackProgressTracker tracker = progressTracker;
		long launchGraceMillis = remainingLaunchGraceMillis(
			playerLaunchElapsedRealtime,
			SystemClock.elapsedRealtime()
		);
		if (launchGraceMillis == 0 && tracker != null && tracker.isPlaybackObserved()) {
			deliverResult();
		} else {
			mainHandler.postDelayed(
				resumeFallback,
				Math.max(RESUME_FALLBACK_DELAY_MILLIS, launchGraceMillis)
			);
		}
	}

	@Override
	protected void onPause() {
		mainHandler.removeCallbacks(resumeFallback);
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
		progressTracker = new PlaybackProgressTracker(
			initialPositionMillis,
			duneIntent.getStringExtra(EXTRA_FILENAME),
			duneIntent.getStringExtra(EXTRA_TITLE),
			duneIntent.getDataString()
		);

		try {
			playerLaunchRequested = true;
			playerLaunchElapsedRealtime = SystemClock.elapsedRealtime();
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
			PlaybackProgressTracker tracker = progressTracker;
			if (tracker == null) return;

			PlaybackProgressTracker.Update update = tracker.onStatus(status, SystemClock.elapsedRealtime());
			handlePlaybackUpdate(update);
		} catch (Exception error) {
			Log.d(TAG, "Dune status poll failed", error);
		}
	}

	private void handlePlaybackUpdate(PlaybackProgressTracker.Update update) throws Exception {
		if (update.action == PlaybackProgressTracker.Action.SEEK) {
			statusClient.seekToSeconds(update.seekPositionSeconds);
			Log.i(TAG, "Requested initial playback position " + update.seekPositionSeconds
				+ " s (attempt " + update.seekAttempt + ")");
		} else if (update.action == PlaybackProgressTracker.Action.CONFIRMED) {
			Log.i(TAG, "Confirmed initial playback position");
		} else if (update.action == PlaybackProgressTracker.Action.USER_OVERRIDE) {
			Log.i(TAG, "Stopped initial seek retries after a manual position change");
		} else if (update.action == PlaybackProgressTracker.Action.GAVE_UP) {
			Log.w(TAG, "Initial playback position was not confirmed after repeated attempts");
		} else if (update.action == PlaybackProgressTracker.Action.PLAYBACK_ENDED) {
			Log.i(TAG, "Detected end of Dune playback outside the bridge task");
			mainHandler.post(this::returnToJellyfin);
		}
	}

	private void returnToJellyfin() {
		if (!setPlaybackResult()) return;

		try {
			ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
			if (activityManager != null) {
				Log.i(TAG, "Bringing the existing Jellyfin task to the foreground");
				activityManager.moveTaskToFront(getTaskId(), 0);
			}
		} catch (SecurityException error) {
			Log.w(TAG, "Unable to bring the existing Jellyfin task to the foreground", error);
		}
		finish();
	}

	private void deliverResult() {
		if (!setPlaybackResult()) return;
		finish();
	}

	private boolean setPlaybackResult() {
		if (!resultDelivered.compareAndSet(false, true)) return false;

		PlaybackProgressTracker tracker = progressTracker;
		long positionMillis = tracker == null
			? initialPositionMillis
			: tracker.getLastPositionMillis();
		Intent result = new Intent()
			.putExtra(EXTRA_POSITION, positionMillis)
			.putExtra(EXTRA_VLC_POSITION, positionMillis);
		Log.i(TAG, "Returning playback position " + positionMillis + " ms");
		setResult(RESULT_OK, result);
		return true;
	}

	private void cancelAndFinish() {
		if (!resultDelivered.compareAndSet(false, true)) return;
		setResult(RESULT_CANCELED);
		finish();
	}

	private void requestOverlayPermission() {
		Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
		Intent settingsIntent = new Intent(
			Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
			Uri.parse("package:" + getPackageName())
		);
		try {
			startActivity(settingsIntent);
		} catch (ActivityNotFoundException | SecurityException error) {
			Log.e(TAG, "Unable to open overlay permission settings", error);
		}
		cancelAndFinish();
	}

	private static long readInitialPositionMillis(Intent intent) {
		return Math.max(0, intent.getIntExtra(EXTRA_POSITION, 0));
	}

	static long remainingLaunchGraceMillis(long launchElapsedRealtime, long currentElapsedRealtime) {
		long elapsed = Math.max(0, currentElapsedRealtime - launchElapsedRealtime);
		return Math.max(0, PLAYER_LAUNCH_GRACE_MILLIS - elapsed);
	}

	static boolean needsOverlayPermission(int sdkVersion, boolean canDrawOverlays) {
		return sdkVersion >= Build.VERSION_CODES.Q && !canDrawOverlays;
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
