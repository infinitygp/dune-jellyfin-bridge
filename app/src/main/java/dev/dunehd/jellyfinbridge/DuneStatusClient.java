package dev.dunehd.jellyfinbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;

final class DuneStatusClient {
	private static final String API_URL = "http://127.0.0.1/cgi-bin/do?";
	private static final String STATUS_QUERY = "cmd=status&result_syntax=json";
	private static final int TIMEOUT_MILLIS = 2_000;
	private static final int MAX_RESPONSE_CHARS = 64 * 1_024;

	DuneStatus fetch() throws IOException {
		return request(STATUS_QUERY);
	}

	void seekToSeconds(long positionSeconds) throws IOException {
		if (positionSeconds < 0) throw new IllegalArgumentException("Negative playback position");

		DuneStatus result = request(
			"cmd=set_playback_state&position=" + positionSeconds + "&timeout=1&result_syntax=json"
		);
		if (!result.isCommandAccepted()) {
			throw new IOException("Dune seek command failed with status " + result.getCommandStatus());
		}
	}

	private DuneStatus request(String query) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_URL + query).openConnection();
		connection.setConnectTimeout(TIMEOUT_MILLIS);
		connection.setReadTimeout(TIMEOUT_MILLIS);
		connection.setRequestMethod("GET");
		connection.setRequestProperty("Accept", "application/json");
		connection.setUseCaches(false);

		try {
			int responseCode = connection.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				throw new IOException("Dune IP Control API returned HTTP " + responseCode);
			}

			StringBuilder body = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				connection.getInputStream(),
				StandardCharsets.UTF_8
			))) {
				char[] buffer = new char[1_024];
				int count;
				while ((count = reader.read(buffer)) != -1) {
					if (body.length() + count > MAX_RESPONSE_CHARS) {
						throw new IOException("Dune status API response is too large");
					}
					body.append(buffer, 0, count);
				}
			}

			try {
				return DuneStatus.parse(body.toString());
			} catch (JSONException error) {
				throw new IOException("Dune IP Control API returned invalid JSON", error);
			}
		} finally {
			connection.disconnect();
		}
	}
}
