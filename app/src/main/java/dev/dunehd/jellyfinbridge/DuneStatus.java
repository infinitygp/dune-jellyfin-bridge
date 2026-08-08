package dev.dunehd.jellyfinbridge;

import org.json.JSONException;
import org.json.JSONObject;

final class DuneStatus {
	private final String commandStatus;
	private final String playerState;
	private final String playbackUrl;
	private final String playbackCaption;
	private final Long playbackPositionSeconds;
	private final Long playbackDurationSeconds;

	private DuneStatus(
		String commandStatus,
		String playerState,
		String playbackUrl,
		String playbackCaption,
		Long playbackPositionSeconds,
		Long playbackDurationSeconds
	) {
		this.commandStatus = commandStatus;
		this.playerState = playerState;
		this.playbackUrl = playbackUrl;
		this.playbackCaption = playbackCaption;
		this.playbackPositionSeconds = playbackPositionSeconds;
		this.playbackDurationSeconds = playbackDurationSeconds;
	}

	static DuneStatus parse(String json) throws JSONException {
		JSONObject object = new JSONObject(json);
		return new DuneStatus(
			object.optString("command_status", ""),
			object.optString("player_state", ""),
			object.optString("playback_url", ""),
			object.optString("playback_caption", ""),
			readNonNegativeLong(object, "playback_position"),
			readNonNegativeLong(object, "playback_duration")
		);
	}

	String getPlayerState() {
		return playerState;
	}

	String getCommandStatus() {
		return commandStatus;
	}

	boolean isCommandAccepted() {
		return "ok".equals(commandStatus) || "timeout".equals(commandStatus);
	}

	Long getPlaybackPositionMillis() {
		return playbackPositionSeconds == null ? null : playbackPositionSeconds * 1_000L;
	}

	Long getPlaybackDurationMillis() {
		return playbackDurationSeconds == null ? null : playbackDurationSeconds * 1_000L;
	}

	boolean isPlaybackActive() {
		return playbackPositionSeconds != null || playerState.endsWith("_playback");
	}

	boolean isReadyForSeek() {
		return isPlaybackActive() && playbackDurationSeconds != null && playbackDurationSeconds > 0;
	}

	boolean matchesMedia(String expectedFileName, String expectedTitle, String expectedUrl) {
		if (!isPlaybackActive()) return false;

		if (hasText(expectedFileName) && expectedFileName.equals(baseName(playbackUrl))) return true;
		if (hasText(expectedTitle) && expectedTitle.equals(playbackCaption)) return true;
		if (hasText(expectedUrl) && expectedUrl.equals(playbackUrl)) return true;

		return !hasText(expectedFileName) && !hasText(expectedTitle) && !hasText(expectedUrl);
	}

	private static String baseName(String value) {
		if (!hasText(value)) return "";

		int end = value.length();
		int query = value.indexOf('?');
		int fragment = value.indexOf('#');
		if (query >= 0) end = Math.min(end, query);
		if (fragment >= 0) end = Math.min(end, fragment);

		String path = value.substring(0, end);
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isEmpty();
	}

	private static Long readNonNegativeLong(JSONObject object, String key) {
		Object value = object.opt(key);
		if (value == null || value == JSONObject.NULL) return null;

		try {
			long parsed = value instanceof Number
				? ((Number) value).longValue()
				: Long.parseLong(value.toString().trim());
			return parsed >= 0 ? parsed : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
