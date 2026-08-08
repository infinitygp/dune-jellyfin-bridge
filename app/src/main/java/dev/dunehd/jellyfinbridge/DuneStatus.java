package dev.dunehd.jellyfinbridge;

import org.json.JSONException;
import org.json.JSONObject;

final class DuneStatus {
	private final String commandStatus;
	private final String playerState;
	private final Long playbackPositionSeconds;
	private final Long playbackDurationSeconds;

	private DuneStatus(
		String commandStatus,
		String playerState,
		Long playbackPositionSeconds,
		Long playbackDurationSeconds
	) {
		this.commandStatus = commandStatus;
		this.playerState = playerState;
		this.playbackPositionSeconds = playbackPositionSeconds;
		this.playbackDurationSeconds = playbackDurationSeconds;
	}

	static DuneStatus parse(String json) throws JSONException {
		JSONObject object = new JSONObject(json);
		return new DuneStatus(
			object.optString("command_status", ""),
			object.optString("player_state", ""),
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
